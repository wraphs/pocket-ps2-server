package br.ps2ponte.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import br.ps2ponte.core.block.BlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pendrive/HD USB lido direto pela USB Host API (sem root, sem a montagem do Android):
 * USB Mass Storage Bulk-Only Transport + comandos SCSI (READ/WRITE 10 e 16).
 *
 * Ao abrir, o app toma a interface do driver do sistema (claimInterface force), então o Android
 * "desmonta" o pendrive enquanto o servidor roda.
 */
class UsbMassStorage private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : BlockDevice {
    private val lock = Any()
    private var tag = 1
    private var lun = 0
    private var closed = false

    override var sectorSize = 512; private set
    override var sectorCount = 0L; private set
    override var readOnly = false; private set
    var description = ""; private set

    private fun init() {
        // Leitores de cartão (inclusive o que vem em muito hub USB-C) e pendrives U3 têm várias LUNs:
        // usa a primeira que for um disco com mídia.
        val maxLun = maxLun()
        var erro: IOException? = null
        for (l in 0..maxLun) {
            lun = l
            try {
                initLun()
                return
            } catch (e: IOException) {
                if (erro == null) erro = if (maxLun == 0) e else IOException("LUN $l: ${e.message}", e)
            }
        }
        throw erro ?: IOException("o dispositivo USB não ficou pronto")
    }

    private fun initLun() {
        val periferico = inquiry()
        // Qualificador != 0: LUN vazia; tipo 5 = CD/DVD (a partição virtual dos pendrives U3)
        if (periferico >= 0 && ((periferico shr 5) != 0 || (periferico and 0x1F) !in TIPOS_DISCO)) {
            throw IOException("não é um disco (tipo de periférico ${periferico and 0x1F})")
        }
        // Alguns pendrives respondem "unit attention"/"not ready" nas primeiras vezes; HD externo pode
        // levar vários segundos para girar o disco.
        val limite = System.currentTimeMillis() + 10_000
        while (command(cdb(0x00), 0, true, null, 0, quiet = true, sense = false) != 0) {
            val s = requestSense()
            val key = s shr 16
            val asc = (s shr 8) and 0xFF
            if (key == 2 && asc == 0x3A) throw IOException("sem mídia (cartão ou disco ausente)")
            if (key == 2 && asc == 0x04 && (s and 0xFF) == 0x02) {
                // "initializing command required": START STOP UNIT (START=1, IMMED=1), como o Linux faz
                command(cdb(0x1B).also { it[1] = 0x01; it[4] = 0x01 }, 0, true, null, 0, quiet = true)
            }
            if (System.currentTimeMillis() > limite) throw IOException("o dispositivo USB não ficou pronto")
            Thread.sleep(200)
        }
        readCapacity()
        readOnly = try { modeSenseWriteProtect() } catch (e: IOException) { false }
    }

    /** GET MAX LUN (classe Bulk-Only). Dispositivo de uma LUN só pode responder com STALL. */
    private fun maxLun(): Int {
        val b = ByteArray(1)
        val r = conn.controlTransfer(0xA1, 0xFE, 0, intf.id, b, 1, TIMEOUT_MS)
        return if (r == 1) b[0].toInt() and 0x0F else 0
    }

    /** INQUIRY: preenche [description] e devolve o byte 0 (qualificador e tipo do periférico), ou -1 se falhou. */
    private fun inquiry(): Int {
        val buf = ByteArray(36)
        val cb = cdb(0x12).also { it[4] = 36 }
        if (command(cb, 36, true, buf, 0) != 0) {
            description = "USB"
            return -1
        }
        val vendor = String(buf, 8, 8, Charsets.US_ASCII).trim()
        val product = String(buf, 16, 16, Charsets.US_ASCII).trim()
        description = listOf(vendor, product).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "USB" }
        return buf[0].toInt() and 0xFF
    }

    private fun readCapacity() {
        val buf = ByteArray(8)
        // O primeiro READ CAPACITY depois do TEST UNIT READY ainda pode voltar com "unit attention"
        if ((0 until 3).none { command(cdb(0x25, 10), 8, true, buf, 0) == 0 }) throw IOException("READ CAPACITY falhou")
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        val last = bb.getInt(0).toLong() and 0xFFFFFFFFL
        sectorSize = bb.getInt(4)
        if (last == 0xFFFFFFFFL) {
            // Disco > 2 TiB: READ CAPACITY(16)
            val b16 = ByteArray(32)
            val cb = cdb(0x9E, 16).also { it[1] = 0x10; it[13] = 32 }
            if (command(cb, 32, true, b16, 0) != 0) throw IOException("READ CAPACITY(16) falhou")
            val bb16 = ByteBuffer.wrap(b16).order(ByteOrder.BIG_ENDIAN)
            sectorCount = bb16.getLong(0) + 1
            sectorSize = bb16.getInt(8)
        } else {
            sectorCount = last + 1
        }
        if (sectorSize < 512 || sectorSize % 512 != 0) throw IOException("tamanho de setor não suportado: $sectorSize")
    }

    private fun modeSenseWriteProtect(): Boolean {
        val buf = ByteArray(192)
        val cb = cdb(0x1A).also { it[2] = 0x3F; it[4] = 192.toByte() }
        if (command(cb, 192, true, buf, 0, quiet = true) != 0) return false
        return buf[2].toInt() and 0x80 != 0
    }

    /**
     * REQUEST SENSE: limpa a condição de erro do dispositivo (necessário depois de um CSW com falha).
     * Devolve (chave shl 16) or (ASC shl 8) or ASCQ, ou 0 se o próprio REQUEST SENSE falhou.
     */
    private fun requestSense(): Int {
        val buf = ByteArray(18)
        val cb = cdb(0x03).also { it[4] = 18 }
        if (command(cb, 18, true, buf, 0, quiet = true, sense = false) != 0) return 0
        val desc = (buf[0].toInt() and 0x7F) >= 0x72 // formato descritor (0x72/0x73)
        val key = (if (desc) buf[1] else buf[2]).toInt() and 0x0F
        val asc = (if (desc) buf[2] else buf[12]).toInt() and 0xFF
        val ascq = (if (desc) buf[3] else buf[13]).toInt() and 0xFF
        return (key shl 16) or (asc shl 8) or ascq
    }

    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) = transfer(lba, count, buf, off, write = false)

    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) {
        if (readOnly) throw IOException("pendrive protegido contra gravação")
        transfer(lba, count, buf, off, write = true)
    }

    private fun transfer(lba: Long, count: Int, buf: ByteArray, off: Int, write: Boolean) {
        if (lba < 0 || count < 0 || lba + count > sectorCount) throw IOException("setor fora do disco: $lba+$count")
        var done = 0
        val maxBlocks = maxOf(1, MAX_BYTES_PER_CMD / sectorSize)
        while (done < count) {
            val n = minOf(maxBlocks, count - done)
            val at = lba + done
            val cb = if (at + n <= 0xFFFFFFFFL) {
                cdb(if (write) 0x2A else 0x28, 10).also {
                    putBe32(it, 2, at)
                    it[7] = (n shr 8).toByte(); it[8] = n.toByte()
                }
            } else {
                cdb(if (write) 0x8A else 0x88, 16).also {
                    putBe32(it, 2, at ushr 32); putBe32(it, 6, at)
                    putBe32(it, 10, n.toLong())
                }
            }
            var status = -1
            for (attempt in 0 until 3) {
                if (attempt > 0) Thread.sleep(100) // dá um respiro ao dispositivo depois da recuperação
                status = command(cb, n * sectorSize, !write, buf, off + done * sectorSize, exato = true)
                if (status == 0) break
            }
            if (status != 0) throw IOException("${if (write) "gravação" else "leitura"} USB falhou no setor $at")
            done += n
        }
    }

    /**
     * Um comando SCSI completo: CBW, dados, CSW. Devolve o status do CSW (0 = ok, 1 = falhou)
     * ou -1 em erro de transporte (depois de tentar a recuperação).
     * [exato]: status "ok" com menos dados que o pedido (fase de dados curta ou residue) conta como falha.
     */
    private fun command(
        cb: ByteArray, len: Int, dirIn: Boolean, buf: ByteArray?, off: Int,
        quiet: Boolean = false, sense: Boolean = true, exato: Boolean = false,
    ): Int = synchronized(lock) {
        if (closed) throw IOException("dispositivo USB fechado")
        val t = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355).putInt(t).putInt(len).put(if (dirIn) 0x80.toByte() else 0).put(lun.toByte()).put(cb.size.toByte())
        cbw.put(cb)
        if (conn.bulkTransfer(epOut, cbw.array(), 31, TIMEOUT_MS) != 31) {
            resetRecovery()
            return -1
        }
        var done = 0
        if (len > 0 && buf != null) {
            val ep = if (dirIn) epIn else epOut
            while (done < len) {
                val chunk = minOf(CHUNK, len - done)
                val r = conn.bulkTransfer(ep, buf, off + done, chunk, TIMEOUT_MS)
                if (r < 0 || (r == 0 && !dirIn)) {
                    // STALL (ou erro): limpa o endpoint e vai para o CSW, como manda o BOT
                    clearHalt(ep)
                    break
                }
                done += r
                // Transferência curta, inclusive o pacote de tamanho zero (r == 0): fim dos dados, endpoint sadio
                if (dirIn && r < chunk) break
            }
        }
        val csw = ByteArray(13)
        var r: Int
        if (dirIn && done == 13 && len > 13 && pareceCsw(buf!!, off, t)) {
            // Alguns dispositivos pulam a fase de dados e mandam o CSW no lugar (o Linux trata igual)
            System.arraycopy(buf, off, csw, 0, 13)
            done = 0
            r = 13
        } else {
            r = conn.bulkTransfer(epIn, csw, 13, TIMEOUT_MS)
            if (r != 13) {
                clearHalt(epIn)
                r = conn.bulkTransfer(epIn, csw, 13, TIMEOUT_MS)
            }
        }
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (r != 13 || bb.getInt(0) != 0x53425355 || bb.getInt(4) != t) {
            resetRecovery()
            return -1
        }
        val status = csw[12].toInt() and 0xFF
        if (status == 2) { // phase error
            resetRecovery()
            return -1
        }
        if (status == 1 && sense) requestSense()
        if (status == 0 && exato && (done != len || bb.getInt(8) != 0)) return 1
        return status
    }

    private fun pareceCsw(b: ByteArray, o: Int, t: Int): Boolean {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return bb.getInt(o) == 0x53425355 && bb.getInt(o + 4) == t
    }

    private fun clearHalt(ep: UsbEndpoint) {
        // CLEAR_FEATURE(ENDPOINT_HALT). Depois de um STALL de verdade o xHCI já zerou o lado do Android,
        // então os dois lados ficam em DATA0.
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT_MS)
    }

    private fun resetRecovery() {
        // Bulk-Only Mass Storage Reset + limpar os dois endpoints
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, TIMEOUT_MS)
        clearHalt(epIn)
        clearHalt(epOut)
        // O CLEAR_FEATURE mandado por controlTransfer só zera o data toggle do dispositivo; o do Android
        // continua onde estava (o USBDEVFS_CLEAR_HALT, que zera os dois, não tem API pública: o libaums usa
        // JNI). Num endpoint que não tinha travado isso dessincroniza o toggle e o próximo pacote some.
        // O SET_INTERFACE zera os dois lados (e, se o dispositivo recusar, o kernel faz o clear halt de
        // verdade em cada endpoint).
        conn.setInterface(intf)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { conn.releaseInterface(intf) } catch (_: Exception) {}
            conn.close()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5000
        /** 120 KiB por comando, qualquer que seja o setor (o mesmo do Linux nos dispositivos USB 2.0). */
        private const val MAX_BYTES_PER_CMD = 120 * 1024

        // Até o Android 8.1 o bulkTransfer aceitava no máximo 16 KiB por chamada; do 9 em diante, o comando
        // inteiro numa chamada só (menos idas e voltas no barramento: mais MB/s)
        private val CHUNK = if (Build.VERSION.SDK_INT >= 28) MAX_BYTES_PER_CMD else 16 * 1024

        /** Disco (0x00) e disco RBC (0x0E). */
        private val TIPOS_DISCO = setOf(0x00, 0x0E)

        private fun cdb(op: Int, size: Int = 6) = ByteArray(size).also { it[0] = op.toByte() }

        private fun putBe32(b: ByteArray, o: Int, v: Long) {
            b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
        }

        /** Interface de armazenamento em massa do tipo Bulk-Only (classe 8, protocolo 0x50). */
        fun massStorageInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol == 0x50 && it.endpointCount >= 2
            }

        fun list(usb: UsbManager): List<UsbDevice> = usb.deviceList.values.filter { massStorageInterface(it) != null }

        fun label(device: UsbDevice): String {
            val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").trim()
            return name.ifEmpty { "Dispositivo USB %04x:%04x".format(device.vendorId, device.productId) }
        }

        /** Abre o dispositivo (a permissão USB já precisa ter sido concedida). */
        fun open(usb: UsbManager, device: UsbDevice): UsbMassStorage {
            val intf = massStorageInterface(device) ?: throw IOException("não é um pendrive/HD USB compatível")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("endpoints USB não encontrados")
            val conn = usb.openDevice(device) ?: throw IOException("sem permissão para abrir o USB")
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("não consegui assumir o pendrive (outro app está usando?)")
            }
            conn.setInterface(intf)
            val dev = UsbMassStorage(conn, intf, epIn, epOut)
            try {
                dev.init()
            } catch (e: Exception) {
                dev.close()
                throw if (e is IOException) e else IOException(e.message, e)
            }
            return dev
        }
    }
}
