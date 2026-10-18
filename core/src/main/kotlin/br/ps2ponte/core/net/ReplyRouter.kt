package br.ps2ponte.core.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Decide para onde mandar as respostas UDP ao PS2.
 *
 * Responde em broadcast da sub-rede do cabo (ex.: 192.168.2.255), não em unicast. No Android, um socket
 * UDP de app não é amarrado a nenhuma rede: o unicast para o PS2 sai pela rede padrão (Wi-Fi/dados) e
 * se perde, enquanto o TCP do SMB funciona porque herda a interface da conexão que chegou. Visto no S25:
 * o PS2 descobria o servidor (broadcast chega) e nada mais acontecia. O broadcast de sub-rede é
 * resolvido pela tabela "local" do Linux, antes das regras do Android, e sai sempre pela interface certa.
 * A pilha do PS2 (ministack) não confere o IP de destino, só a porta.
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

    /** Destinos para a resposta (um só, para o PS2 não receber pacotes duplicados). */
    fun targets(peer: InetSocketAddress): List<InetSocketAddress> {
        val ip = peer.address as? Inet4Address ?: return listOf(peer)
        if (ip.isLoopbackAddress) return listOf(peer)
        val nets = subnets().filter { it.broadcast != null }
        val wired = nets.filter { isWired(it.ifName) }
        // 1) sub-rede do cabo que contém o PS2; 2) qualquer cabo (IP do PS2 fora da sub-rede, ex.: o padrão
        // do Neutrino 192.168.1.10); 3) outra interface que contenha o PS2; 4) unicast.
        val alvo = wired.filter { it.contains(ip) }.ifEmpty { wired }.ifEmpty { nets.filter { it.contains(ip) } }
        if (alvo.isEmpty()) return listOf(peer)
        return alvo.map { InetSocketAddress(it.broadcast, peer.port) }.distinct()
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
