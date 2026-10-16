package br.ps2ponte.core.udpbd

import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.block.FileBlockDevice
import br.ps2ponte.core.net.ReplyRouter
import br.ps2ponte.core.udpbd.UdpbdServer.Companion.header
import br.ps2ponte.core.udpbd.UdpbdServer.Companion.putU16
import br.ps2ponte.core.udpbd.UdpbdServer.Companion.putU32
import br.ps2ponte.core.udpbd.UdpbdServer.Companion.u16
import br.ps2ponte.core.udpbd.UdpbdServer.Companion.u32
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cliente que imita iop/udpbd/src/udpbd.c do Neutrino (porta 0xBDBE -> servidor). */
class UdpbdServerTest {
    @TempDir lateinit var dir: File
    private lateinit var img: File
    private lateinit var content: ByteArray
    private lateinit var server: UdpbdServer
    private lateinit var device: FileBlockDevice
    private lateinit var client: DatagramSocket
    private var port = 0
    private var cmdId = 0
    private val stats = TransferStats()

    @BeforeEach
    fun setUp() {
        content = Random(42).nextBytes(2048 * 512)
        img = File(dir, "disco.img").apply { writeBytes(content) }
        port = DatagramSocket(0).use { it.localPort }
        device = FileBlockDevice(img)
        server = UdpbdServer(device, port = port, stats = stats, router = ReplyRouter { emptyList() })
        server.start()
        client = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 2000 }
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.close()
        device.close()
    }

    private fun send(b: ByteArray) = client.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), port))

    private fun recv(): ByteArray {
        val p = DatagramPacket(ByteArray(2048), 2048)
        client.receive(p)
        return p.data.copyOf(p.length)
    }

    @Test
    fun `udpbd_init - INFO devolve tamanho do setor e quantidade`() {
        send(ByteArray(2).also { putU16(it, 0, header(UdpbdServer.CMD_INFO, cmdId, 0)) })
        val r = recv()
        assertEquals(10, r.size)
        val h = u16(r, 0)
        assertEquals(UdpbdServer.CMD_INFO_REPLY, h and 0x1F)
        assertEquals(cmdId, (h shr 5) and 7)
        assertEquals(512L, u32(r, 2))
        assertEquals(2048L, u32(r, 6))
    }

    /** _udpbd_read: confere ordem de cmdpkt, tamanho máximo e o conteúdo. */
    private fun read(lba: Long, count: Int): ByteArray {
        cmdId = (cmdId + 1) and 7
        val req = ByteArray(8)
        putU16(req, 0, header(UdpbdServer.CMD_READ, cmdId, 0))
        putU32(req, 2, lba)
        putU16(req, 6, count)
        send(req)
        val out = java.io.ByteArrayOutputStream()
        var expectPkt = 1
        while (out.size() < count * 512) {
            val r = recv()
            val h = u16(r, 0)
            assertEquals(UdpbdServer.CMD_READ_RDMA, h and 0x1F)
            assertEquals(cmdId, (h shr 5) and 7)
            assertEquals(expectPkt and 0xFF, (h shr 8) and 0xFF, "ordem do cmdpkt")
            expectPkt++
            val bt = u32(r, 2).toInt()
            val size = ((bt shr 4) and 0x1FF) shl ((bt and 0xF) + 2)
            assertTrue(size <= UdpbdServer.RDMA_MAX_PAYLOAD)
            assertEquals(r.size - 6, size)
            out.write(r, 6, size)
        }
        assertEquals(count * 512, out.size())
        return out.toByteArray()
    }

    @Test
    fun `leituras de varios tamanhos batem com o disco`() {
        for ((lba, count) in listOf(0L to 1, 5L to 7, 100L to 128, 1000L to 512, 2047L to 1)) {
            val got = read(lba, count)
            assertContentEquals(content.copyOfRange((lba * 512).toInt(), ((lba + count) * 512).toInt()), got, "lba=$lba count=$count")
        }
        assertTrue(stats.bytesRead.get() > 0)
    }

    @Test
    fun `512 setores cabem em 255 pacotes (cmdpkt de 8 bits)`() {
        val shift = UdpbdServer.blockShiftFor(512)
        val per = (UdpbdServer.RDMA_MAX_PAYLOAD / (1 shl (shift + 2))) * (1 shl (shift + 2))
        assertTrue((512 * 512 + per - 1) / per <= 255)
    }

    @Test
    fun `udpbd_write - grava com pacotes de 512 bytes e responde WRITE_DONE`() {
        val data = Random(7).nextBytes(3 * 512)
        cmdId = (cmdId + 1) and 7
        val req = ByteArray(8)
        putU16(req, 0, header(UdpbdServer.CMD_WRITE, cmdId, 0))
        putU32(req, 2, 10)
        putU16(req, 6, 3)
        send(req)
        for (i in 0 until 3) {
            val p = ByteArray(6 + 512)
            putU16(p, 0, header(UdpbdServer.CMD_WRITE_RDMA, cmdId, i + 1))
            putU32(p, 2, (5 or (4 shl 4)).toLong()) // 4 blocos de 128 bytes
            System.arraycopy(data, i * 512, p, 6, 512)
            send(p)
        }
        val r = recv()
        assertEquals(UdpbdServer.CMD_WRITE_DONE, u16(r, 0) and 0x1F)
        assertEquals(cmdId, (u16(r, 0) shr 5) and 7)
        assertEquals(0L, u32(r, 2))
        assertContentEquals(data, read(10, 3))
        server.close()
        device.close()
        assertContentEquals(data, img.readBytes().copyOfRange(10 * 512, 13 * 512))
    }
}
