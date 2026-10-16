package br.ps2ponte.core.fs

import java.io.File

/**
 * A pasta servida para o PS2 (SMB1 e UDPFS no modo arquivo).
 *
 * [resolve] converte o caminho pedido pelo PS2 ("\\DVD\\Jogo.iso", "/DVD/Jogo.iso", "DVD/Jogo.iso")
 * num item dentro de [root]. Cada parte do caminho é procurada primeiro com o nome exato e,
 * se não existir, sem diferenciar maiúsculas de minúsculas (o PS2 pede "DVD", a pasta pode ser "dvd").
 * Partes que não existem são mantidas como vieram (para criar arquivos novos).
 * Retorna null se o caminho tentar sair de [root] ("..").
 */
class LocalTree(val root: VFile, val readOnly: Boolean = false) {
    constructor(root: File, readOnly: Boolean = false) : this(LocalFile.root(root), readOnly)

    fun resolve(path: String): VFile? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        var cur = root
        for (part in parts) {
            val exact = cur.child(part)
            cur = if (exact.exists()) exact else findIgnoringCase(cur, part) ?: exact
        }
        return cur
    }

    // Ponte temporária enquanto SMB e exFAT migram de java.io.File para VFile.
    @Deprecated("usar root") val rootFile: File get() = (root as LocalFile).file
    @Deprecated("usar resolve") fun resolveFile(path: String): File? = (resolve(path) as LocalFile?)?.file

    private fun findIgnoringCase(dir: VFile, name: String): VFile? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
