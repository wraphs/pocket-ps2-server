package br.ps2ponte.core.net

import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals

/** Interfaces como num Galaxy com a ancoragem Ethernet ligada (eth0) e o Wi-Fi de casa (wlan0). */
class ReplyRouterTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun net(nome: String, end: String, bcast: String?) = ReplyRouter.Subnet(nome, ip(end), 24, bcast?.let { ip(it) })
    private fun peer(s: String) = InetSocketAddress(ip(s), 0xBDBE)
    private fun bcast(s: String) = InetSocketAddress(ip(s), 0xBDBE)

    private val eth0 = net("eth0", "192.168.2.1", "192.168.2.255")
    private val wlan0 = net("wlan0", "192.168.1.35", "192.168.1.255")
    private val router = ReplyRouter { listOf(wlan0, eth0) }

    @Test
    fun `PS2 na sub-rede do cabo recebe broadcast do cabo (unicast UDP sairia pelo Wi-Fi)`() {
        assertEquals(listOf(bcast("192.168.2.255")), router.targets(peer("192.168.2.200")))
    }

    @Test
    fun `PS2 fora de qualquer sub-rede recebe broadcast pelo cabo`() {
        assertEquals(listOf(bcast("192.168.2.255")), router.targets(peer("10.0.0.10")))
    }

    @Test
    fun `IP do PS2 na sub-rede do Wi-Fi vai so pelo cabo`() {
        assertEquals(listOf(bcast("192.168.2.255")), router.targets(peer("192.168.1.10")))
    }

    @Test
    fun `sem cabo usa o broadcast da rede que contem o PS2 ou unicast`() {
        val soWifi = ReplyRouter { listOf(wlan0) }
        assertEquals(listOf(bcast("192.168.1.255")), soWifi.targets(peer("192.168.1.10")))
        assertEquals(listOf(peer("10.0.0.10")), soWifi.targets(peer("10.0.0.10")))
    }

    @Test
    fun `loopback responde direto`() {
        assertEquals(listOf(peer("127.0.0.1")), router.targets(peer("127.0.0.1")))
    }
}
