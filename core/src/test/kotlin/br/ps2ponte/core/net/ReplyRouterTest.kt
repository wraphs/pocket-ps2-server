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

    private val eth0 = net("eth0", "192.168.42.129", "192.168.42.255")
    private val wlan0 = net("wlan0", "192.168.1.35", "192.168.1.255")
    private val router = ReplyRouter { listOf(wlan0, eth0) }

    @Test
    fun `PS2 na sub-rede do cabo recebe unicast`() {
        assertEquals(listOf(peer("192.168.42.200")), router.targets(peer("192.168.42.200")))
    }

    @Test
    fun `PS2 fora de qualquer sub-rede recebe broadcast pelo cabo`() {
        assertEquals(listOf(InetSocketAddress(ip("192.168.42.255"), 0xBDBE)), router.targets(peer("10.0.0.10")))
    }

    @Test
    fun `IP do PS2 na sub-rede do Wi-Fi tambem vai em broadcast pelo cabo`() {
        assertEquals(
            listOf(peer("192.168.1.10"), InetSocketAddress(ip("192.168.42.255"), 0xBDBE)),
            router.targets(peer("192.168.1.10")),
        )
    }

    @Test
    fun `sem cabo responde direto`() {
        val soWifi = ReplyRouter { listOf(wlan0) }
        assertEquals(listOf(peer("192.168.1.10")), soWifi.targets(peer("192.168.1.10")))
        assertEquals(listOf(peer("10.0.0.10")), soWifi.targets(peer("10.0.0.10")))
    }
}
