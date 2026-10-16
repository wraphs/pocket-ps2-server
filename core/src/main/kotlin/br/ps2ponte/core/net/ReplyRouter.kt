package br.ps2ponte.core.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Decide para onde mandar as respostas UDP ao PS2.
 *
 * Se o IP do PS2 está numa sub-rede de alguma interface do celular, responde direto (unicast).
 * Se não está (ex.: Neutrino com o IP padrão 192.168.1.10 e o celular em 192.168.x.1), responde em
 * broadcast pela(s) interface(s) cabeada(s): a pilha do PS2 (ministack) não confere o IP de destino,
 * só a porta, então o pacote chega. Sem isso o Android mandaria a resposta pela rede móvel.
 */
class ReplyRouter(private val interfaces: () -> List<Subnet> = ::localSubnets) {

    data class Subnet(val ifName: String, val address: Inet4Address, val prefix: Int, val broadcast: Inet4Address?) {
        fun contains(ip: Inet4Address): Boolean {
            if (prefix <= 0) return false
            val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
            return (toInt(ip) and mask) == (toInt(address) and mask)
        }
    }

    @Volatile private var cache: List<Subnet> = emptyList()
    @Volatile private var cacheTime = 0L

    private fun subnets(): List<Subnet> {
        val now = System.currentTimeMillis()
        if (now - cacheTime > 5_000) {
            cache = try { interfaces() } catch (e: Exception) { emptyList() }
            cacheTime = now
        }
        return cache
    }

    /** Destinos para a resposta: o próprio PS2, ou os broadcasts das interfaces cabeadas. */
    fun targets(peer: InetSocketAddress): List<InetSocketAddress> {
        val ip = peer.address as? Inet4Address ?: return listOf(peer)
        if (ip.isLoopbackAddress) return listOf(peer)
        val nets = subnets()
        if (nets.isEmpty() || nets.any { isWired(it.ifName) && it.contains(ip) }) return listOf(peer)
        val wired = nets.filter { isWired(it.ifName) && it.broadcast != null }
        if (wired.isEmpty()) return listOf(peer)
        val broadcasts = wired.map { InetSocketAddress(it.broadcast, peer.port) }
        // IP dentro da sub-rede de outra interface (ex.: Wi-Fi de casa em 192.168.1.x e o PS2 no cabo com o
        // IP padrão 192.168.1.10): o unicast sairia pelo Wi-Fi. Sem saber por onde o pacote chegou, responde
        // pelos dois caminhos; só um deles chega ao PS2.
        return if (nets.any { it.contains(ip) }) listOf(peer) + broadcasts else broadcasts
    }

    companion object {
        fun toInt(a: InetAddress): Int {
            val b = a.address
            return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
                ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        }

        /** Interfaces de cabo/adaptador USB no Android: eth0, usb0, rndis0, ncm0... */
        fun isWired(name: String): Boolean =
            name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("en")

        fun localSubnets(): List<Subnet> {
            val out = ArrayList<Subnet>()
            for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    out += Subnet(nif.name, a, ia.networkPrefixLength.toInt(), ia.broadcast as? Inet4Address)
                }
            }
            return out
        }
    }
}
