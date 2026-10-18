package br.ps2ponte.core.udpbd

import br.ps2ponte.core.EventLog
import br.ps2ponte.core.NoLog
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.block.BlockDevice
import br.ps2ponte.core.net.ReplyRouter
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * UDPBD v2 clássico (porta 0xBDBD), o protocolo do `udpbd.irx` do Neutrino.
 *
 * Replica o `udpbd-server` original do Maximus32: o PS2 manda tudo em broadcast (porta de origem 0xBDBE),
 * o servidor responde ao endereço de origem. Leitura: rajada de pacotes RDMA (sem controle de fluxo) com o
 * tamanho de bloco escolhido para usar o mínimo de pacotes. Escrita: pacotes RDMA de 512 bytes e WRITE_DONE.
 *
 * Cabeçalho (u16 little-endian): bits 0-4 cmd, 5-7 cmdid, 8-15 cmdpkt.
 */
class UdpbdServer(
    private val device: BlockDevice,
    val port: Int = PORT,
    private val log: EventLog = NoLog,
    private val stats: TransferStats = TransferStats(),
    private val router: ReplyRouter = ReplyRouter(),
) : PonteServer {
    override val name = "UDPBD"

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var lastPeer: String? = null
    private var leuDesdeInfo = false

    // Estado da escrita em andamento
    private var wCmdId = -1
    private var wLba = 0L
    private var wCount = 0
    private var wBuf = ByteArray(0)
    private var wPos = 0
    private var wNextPkt = 1

    override fun start() {
        require(device.sectorSize == 512) { "UDPBD exige setores de 512 bytes" }
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.broadcast = true
            s.receiveBufferSize = 1 shl 20
            s.sendBufferSize = 1 shl 20
            s.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
        running = true
        Thread({ loop(s) }, "udpbd").apply { isDaemon = true; start() }
    }

    override fun close() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.setData(buf, 0, buf.size)
                s.receive(pkt)
                handle(s, buf, pkt.length, InetSocketAddress(pkt.address, pkt.port))
            } catch (e: SocketException) {
                if (running) log.log("UDPBD: erro de rede: ${e.message}")
                break
            } catch (e: Exception) {
                log.log("UDPBD: erro: ${e.message}")
            }
        }
    }

    private fun handle(s: DatagramSocket, buf: ByteArray, len: Int, peer: InetSocketAddress) {
        if (len < 2) return
        val hdr = u16(buf, 0)
        val cmd = hdr and 0x1F
        val cmdId = (hdr shr 5) and 0x7
        when (cmd) {
            CMD_INFO -> handleInfo(s, cmdId, peer)
            CMD_READ -> if (len >= 8) handleRead(s, cmdId, u32(buf, 2), u16(buf, 6), peer)
            CMD_WRITE -> if (len >= 8) handleWrite(cmdId, u32(buf, 2), u16(buf, 6))
            CMD_WRITE_RDMA -> if (len >= 6) handleWriteRdma(s, buf, len, cmdId, (hdr shr 8) and 0xFF, peer)
        }
    }

    private fun handleInfo(s: DatagramSocket, cmdId: Int, peer: InetSocketAddress) {
        val count = minOf(device.sectorCount, 0xFFFFFFFFL)
        val ip = peer.address.hostAddress
        if (ip != lastPeer) {
            lastPeer = ip
            log.log("UDPBD: PS2 $ip pediu o disco (${count * 512 / (1024 * 1024)} MiB); respondendo para ${router.targets(peer).joinToString { it.address.hostAddress ?: "" }}")
        }
        leuDesdeInfo = false
        val r = ByteArray(10)
        putU16(r, 0, header(CMD_INFO_REPLY, cmdId, 1))
        putU32(r, 2, 512L)
        putU32(r, 6, count)
        send(s, r, r.size, peer)
    }

    private fun handleRead(s: DatagramSocket, cmdId: Int, lba: Long, count: Int, peer: InetSocketAddress) {
        if (count <= 0) return
        if (!leuDesdeInfo) {
            leuDesdeInfo = true
            log.log("UDPBD: PS2 começou a ler o disco")
        }
        val data = ByteArray(count * 512)
        try {
            device.read(lba, count, data, 0)
        } catch (e: IOException) {
            // Sem resposta: o PS2 tenta de novo (até 4 vezes) e depois desconecta.
            log.log("UDPBD: erro ao ler setor $lba (+$count): ${e.message}")
            return
        }
        stats.bytesRead.addAndGet(data.size.toLong())

        val shift = blockShiftFor(count)
        val blockSize = 1 shl (shift + 2)
        val blocksPerPacket = RDMA_MAX_PAYLOAD / blockSize
        var blocksLeft = data.size / blockSize
        var offset = 0
        var cmdPkt = 1
        val out = ByteArray(6 + blocksPerPacket * blockSize)
        val targets = router.targets(peer)
        while (blocksLeft > 0) {
            val n = minOf(blocksLeft, blocksPerPacket)
            blocksLeft -= n
            putU16(out, 0, header(CMD_READ_RDMA, cmdId, cmdPkt and 0xFF))
            putU32(out, 2, (shift or (n shl 4)).toLong())
            val bytes = n * blockSize
            System.arraycopy(data, offset, out, 6, bytes)
            offset += bytes
            for (t in targets) s.send(DatagramPacket(out, 6 + bytes, t))
            cmdPkt++
        }
    }

    private fun handleWrite(cmdId: Int, lba: Long, count: Int) {
        wCmdId = cmdId
        wLba = lba
        wCount = count
        wBuf = ByteArray(count * 512)
        wPos = 0
        wNextPkt = 1
    }

    private fun handleWriteRdma(s: DatagramSocket, buf: ByteArray, len: Int, cmdId: Int, cmdPkt: Int, peer: InetSocketAddress) {
        if (cmdId != wCmdId || wPos >= wBuf.size) return
        if (cmdPkt != (wNextPkt and 0xFF)) {
            log.log("UDPBD: pacote de escrita perdido (esperado ${wNextPkt and 0xFF}, veio $cmdPkt); escrita descartada")
            wCmdId = -1
            return
        }
        wNextPkt++
        val bt = u32(buf, 2).toInt()
        val size = ((bt shr 4) and 0x1FF) shl ((bt and 0xF) + 2)
        val n = minOf(size, wBuf.size - wPos, len - 6)
        System.arraycopy(buf, 6, wBuf, wPos, n)
        wPos += n
        if (wPos < wBuf.size) return

        val result = try {
            if (device.readOnly) -30 // EROFS
            else {
                device.write(wLba, wCount, wBuf, 0)
                stats.bytesWritten.addAndGet(wBuf.size.toLong())
                0
            }
        } catch (e: IOException) {
            log.log("UDPBD: erro ao gravar setor $wLba (+$wCount): ${e.message}")
            -5 // EIO
        }
        val r = ByteArray(6)
        putU16(r, 0, header(CMD_WRITE_DONE, cmdId, (cmdId + 1) and 0xFF))
        putU32(r, 2, result.toLong() and 0xFFFFFFFFL)
        send(s, r, r.size, peer)
        wCmdId = -1
    }

    private fun send(s: DatagramSocket, data: ByteArray, len: Int, peer: InetSocketAddress) {
        for (t in router.targets(peer)) s.send(DatagramPacket(data, len, t))
    }

    companion object {
        const val PORT = 0xBDBD
        const val CMD_INFO = 0x00
        const val CMD_INFO_REPLY = 0x01
        const val CMD_READ = 0x02
        const val CMD_READ_RDMA = 0x03
        const val CMD_WRITE = 0x04
        const val CMD_WRITE_RDMA = 0x05
        const val CMD_WRITE_DONE = 0x06

        /** UDP_MAX_PAYLOAD (1472) - cabeçalho (2) - block_type (4). */
        const val RDMA_MAX_PAYLOAD = 1466

        fun header(cmd: Int, cmdId: Int, cmdPkt: Int) = (cmd and 0x1F) or ((cmdId and 0x7) shl 5) or ((cmdPkt and 0xFF) shl 8)

        /** Igual a set_block_shift_sectors do udpbd-server: menos pacotes primeiro, depois o maior bloco. */
        fun blockShiftFor(sectors: Int): Int {
            val size = sectors * 512
            fun packets(per: Int) = (size + per - 1) / per
            val min = packets(1440)
            return when (min) {
                packets(1024) -> 7 // blocos de 512
                packets(1280) -> 6 // 256
                packets(1408) -> 5 // 128
                else -> 3 // 32
            }
        }

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
        fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun putU32(b: ByteArray, o: Int, v: Long) { putU16(b, o, (v and 0xFFFF).toInt()); putU16(b, o + 2, ((v shr 16) and 0xFFFF).toInt()) }
    }
}
