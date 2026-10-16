package br.ps2ponte

import br.ps2ponte.core.net.ReplyRouter

/** Endereços IPv4 do celular, para mostrar na tela e sugerir o IP do PS2. */
object Rede {
    data class Ip(val nome: String, val ip: String, val prefixo: Int, val cabo: Boolean)

    fun enderecos(): List<Ip> = try {
        ReplyRouter.localSubnets().map { Ip(it.ifName, it.address.hostAddress ?: "", it.prefix, ReplyRouter.isWired(it.ifName)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun cabo(): Ip? = enderecos().firstOrNull { it.cabo }

    /** Mesmo /24 do celular terminando em .200 (ou .201 se o celular já for .200). */
    fun sugestaoPs2(ip: String): String {
        val p = ip.split('.')
        if (p.size != 4) return "192.168.0.200"
        val fim = if (p[3] == "200") "201" else "200"
        return "${p[0]}.${p[1]}.${p[2]}.$fim"
    }

    fun mascara(prefixo: Int): String {
        val m = if (prefixo <= 0) 0 else (-1 shl (32 - prefixo.coerceAtMost(32)))
        return "${(m ushr 24) and 255}.${(m ushr 16) and 255}.${(m ushr 8) and 255}.${m and 255}"
    }
}
