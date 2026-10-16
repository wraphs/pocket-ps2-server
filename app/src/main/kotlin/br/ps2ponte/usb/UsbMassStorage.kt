package br.ps2ponte.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
    private val lun = 0
    private var closed = false

    override var sectorSize = 512; private set
    override var sectorCount = 0L; private set
    override var readOnly = false; private set
    var description = ""; private set

    private fun init() {
        description = inquiry()
        // Alguns pendrives respondem "unit attention"/"not ready" nas primeiras vezes
        var ready = false
        for (i in 0 until 10) {
            if (command(cdb(0x00), 0, true, null, 0, quiet = true) == 0) { ready = true; break }
            requestSense()
            Thread.sleep(200)
        }
        if (!ready) throw IOException("o dispositivo USB não ficou pronto")
        readCapacity()
        readOnly = try { modeSenseWriteProtect() } catch (e: IOException) { false }
    }

    private fun inquiry(): String {
        val buf = ByteArray(36)
        val cb = cdb(0x12).also { it[4] = 36 }
        if (command(cb, 36, true, buf, 0) != 0) return "USB"
        val vendor = String(buf, 8, 8, Charsets.US_ASCII).trim()
        val product = String(buf, 16, 16, Charsets.US_ASCII).trim()
        return listOf(vendor, product).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "USB" }
    }

    private fun readCapacity() {
        val buf = ByteArray(8)
        if (command(cdb(0x25, 10), 8, true, buf, 0) != 0) throw IOException("READ CAPACITY falhou")
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

    private fun requestSense(): Int {
        val buf = ByteArray(18)
        val cb = cdb(0x03).also { it[4] = 18 }
        command(cb, 18, true, buf, 0, quiet = true, sense = false)
        return buf[2].toInt() and 0x0F
    }

    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) = transfer(lba, count, buf, off, write = false)

    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) {
        if (readOnly) throw IOException("pendrive protegido contra gravação")
        transfer(lba, count, buf, off, write = true)
    }

    private fun transfer(lba: Long, count: Int, buf: ByteArray, off: Int, write: Boolean) {
        if (lba < 0 || count < 0 || lba + count > sectorCount) throw IOException("setor fora do disco: $lba+$count")
        var done = 0
        while (done < count) {
            val n = minOf(MAX_BLOCKS_PER_CMD, count - done)
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
                status = command(cb, n * sectorSize, !write, buf, off + done * sectorSize)
                if (status == 0) break
            }
            if (status != 0) throw IOException("${if (write) "gravação" else "leitura"} USB falhou no setor $at")
            done += n
        }
    }

    /**
     * Um comando SCSI completo: CBW, dados, CSW. Devolve o status do CSW (0 = ok, 1 = falhou)
     * ou -1 em erro de transporte (depois de tentar a recuperação).
     */
    private fun command(cb: ByteArray, len: Int, dirIn: Boolean, buf: ByteArray?, off: Int, quiet: Boolean = false, sense: Boolean = true): Int = synchronized(lock) {
        if (closed) throw IOException("dispositivo USB fechado")
        val t = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355).putInt(t).putInt(len).put(if (dirIn) 0x80.toByte() else 0).put(lun.toByte()).put(cb.size.toByte())
        cbw.put(cb)
        if (conn.bulkTransfer(epOut, cbw.array(), 31, TIMEOUT_MS) != 31) {
            resetRecovery()
            return -1
        }
        if (len > 0 && buf != null) {
            var done = 0
            val ep = if (dirIn) epIn else epOut
            while (done < len) {
                val chunk = minOf(CHUNK, len - done)
                val r = conn.bulkTransfer(ep, buf, off + done, chunk, TIMEOUT_MS)
                if (r <= 0) {
                    clearHalt(ep)
                    break
                }
                done += r
                if (dirIn && r < chunk) break // transferência curta
            }
        }
        val csw = ByteArray(13)
        var r = conn.bulkTransfer(epIn, csw, 13, TIMEOUT_MS)
        if (r != 13) {
            clearHalt(epIn)
            r = conn.bulkTransfer(epIn, csw, 13, TIMEOUT_MS)
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
        if (status == 1 && sense) requestSenseLocked()
        return status
    }

    private fun requestSenseLocked() {
        // Limpa a condição de erro do dispositivo (necessário depois de um CSW com falha)
        val buf = ByteArray(18)
        val cb = cdb(0x03).also { it[4] = 18 }
        command(cb, 18, true, buf, 0, quiet = true, sense = false)
    }

    private fun clearHalt(ep: UsbEndpoint) {
        // CLEAR_FEATURE(ENDPOINT_HALT)
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT_MS)
    }

    private fun resetRecovery() {
        // Bulk-Only Mass Storage Reset + limpar os dois endpoints
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, TIMEOUT_MS)
        clearHalt(epIn)
        clearHalt(epOut)
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
        private const val CHUNK = 16 * 1024
        private const val MAX_BLOCKS_PER_CMD = 128

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
