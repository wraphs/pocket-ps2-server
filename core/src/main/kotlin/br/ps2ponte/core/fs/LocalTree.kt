package br.ps2ponte.core.fs

import java.io.File

/**
 * A pasta servida para o PS2 (SMB1 e UDPFS no modo arquivo).
 *
 * [resolve] converte o caminho pedido pelo PS2 ("\\DVD\\Jogo.iso", "/DVD/Jogo.iso", "DVD/Jogo.iso")
 * num arquivo dentro de [root]. Cada parte do caminho é procurada primeiro com o nome exato e,
 * se não existir, sem diferenciar maiúsculas de minúsculas (o PS2 pede "DVD", a pasta pode ser "dvd").
 * Partes que não existem são mantidas como vieram (para criar arquivos novos).
 * Retorna null se o caminho tentar sair de [root] ("..").
 */
class LocalTree(root: File, val readOnly: Boolean = false) {
    val root: File = root.absoluteFile

    fun resolve(path: String): File? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        var cur = root
        for (part in parts) {
            val exact = File(cur, part)
            cur = if (exact.exists()) exact else findIgnoringCase(cur, part) ?: exact
        }
        return cur
    }

    private fun findIgnoringCase(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
