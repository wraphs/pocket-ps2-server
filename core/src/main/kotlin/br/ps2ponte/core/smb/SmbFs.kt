package br.ps2ponte.core.smb

import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.fs.VFile

/**
 * Resolução de caminhos para o SMB. Usa [LocalTree.resolve] (barras e maiúsculas) e, se o caminho
 * tiver '?' e o item não existir, casa '?' com qualquer caractere: com Unicode ligado o PS2
 * troca todo caractere não-ASCII do nome listado por '?' e depois pede o arquivo com esse nome.
 */
internal class SmbPaths(private val tree: LocalTree) {
    val root: VFile get() = tree.root
    val readOnly: Boolean get() = tree.readOnly

    /** null = caminho fora da raiz. */
    fun resolve(path: String): VFile? {
        val f = tree.resolve(path) ?: return null
        if ('?' !in path || f.exists()) return f  // sem '?' não gasta um exists() a mais (no SAF é IPC)
        return fuzzy(path) ?: f
    }

    private fun fuzzy(path: String): VFile? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        var cur = root
        for (part in parts) {
            val exact = cur.child(part)
            if (exact.exists()) {
                cur = exact
                continue
            }
            val list = cur.listFiles() ?: return null
            cur = list.firstOrNull { it.name.equals(part, ignoreCase = true) }
                ?: list.firstOrNull { qmMatch(part, it.name) }
                ?: return null
        }
        return cur
    }
}

/** Nome do PS2 com '?' no lugar de caracteres não-ASCII (um '?' por caractere). */
private fun qmMatch(pattern: String, name: String): Boolean {
    val p = fold(pattern)
    val n = fold(name)
    if (p.size != n.size) return false
    for (i in p.indices) if (p[i] != '?'.code && p[i] != n[i]) return false
    return true
}

private fun fold(s: String): IntArray =
    s.codePoints().map { Character.toLowerCase(Character.toUpperCase(it)) }.toArray()

internal fun hasWildcards(s: String): Boolean = s.any { it == '*' || it == '?' || it == '<' || it == '>' || it == '"' }

/** Casa nomes com '*', '?' e os curingas DOS ('<' '>' '"'), sem diferenciar maiúsculas. */
internal fun wildMatch(pattern: String, name: String): Boolean {
    if (pattern == "*" || pattern == "*.*" || pattern == "<.*") return true
    val p = fold(pattern)
    val n = fold(name)
    var pi = 0
    var ni = 0
    var star = -1
    var mark = 0
    while (ni < n.size) {
        val pc = if (pi < p.size) p[pi] else -1
        if (pc == '?'.code || pc == '>'.code || pc == n[ni] || (pc == '"'.code && n[ni] == '.'.code)) {
            pi++; ni++
        } else if (pc == '*'.code || pc == '<'.code) {
            star = pi++; mark = ni
        } else if (star >= 0) {
            pi = star + 1; ni = ++mark
        } else {
            return false
        }
    }
    while (pi < p.size && (p[pi] == '*'.code || p[pi] == '<'.code || p[pi] == '>'.code || p[pi] == '"'.code)) pi++
    return pi == p.size
}

/** Entrada de uma busca ("." e ".." apontam para a pasta e a mãe). */
internal class DirEntry(val name: String, val file: VFile)

/** Busca aberta por FIND_FIRST2: fotografia da pasta, entregue aos poucos. */
internal class Search(val sid: Int, val tid: Int, val entries: List<DirEntry>) {
    var pos = 0
}

/** Lista [dir] filtrando por [mask]; "." e ".." primeiro, depois em ordem alfabética. */
internal fun listDir(dir: VFile, mask: String, includeDirs: Boolean): List<DirEntry> {
    val out = ArrayList<DirEntry>()
    if (hasWildcards(mask)) {
        if (includeDirs) {
            if (wildMatch(mask, ".")) out += DirEntry(".", dir)
            if (wildMatch(mask, "..")) out += DirEntry("..", dir.parentFile ?: dir)  // na raiz, a própria raiz
        }
        val children = dir.listFiles() ?: return out
        children.filter { wildMatch(mask, it.name) && (includeDirs || !it.isDirectory) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { out += DirEntry(it.name, it) }
    } else {
        val exact = dir.child(mask)
        val f = if (exact.exists()) exact else dir.listFiles()?.firstOrNull { it.name.equals(mask, ignoreCase = true) }
        if (f != null && (includeDirs || !f.isDirectory)) out += DirEntry(f.name, f)
    }
    return out
}

/** Atributos estendidos (32 bits) e SMB (16 bits): pasta, arquivo, somente leitura. */
internal fun attrsOf(isDir: Boolean, readOnly: Boolean): Int = when {
    isDir -> 0x10
    readOnly -> 0x21
    else -> 0x20
}

/** Tamanho alocado: múltiplo de 4 KiB. */
internal fun allocOf(size: Long): Long = (size + 4095L) / 4096L * 4096L

/** Níveis de FIND_FIRST2/FIND_NEXT2 suportados e o tamanho fixo de cada entrada. */
internal fun findHeaderSize(level: Int): Int = when (level) {
    0x101 -> 64   // SMB_FIND_FILE_DIRECTORY_INFO
    0x102 -> 68   // SMB_FIND_FILE_FULL_DIRECTORY_INFO
    0x103 -> 12   // SMB_FIND_FILE_NAMES_INFO
    0x104 -> 94   // SMB_FIND_FILE_BOTH_DIRECTORY_INFO (o que o OPL usa)
    0x105 -> 80   // SMB_FIND_FILE_ID_FULL_DIRECTORY_INFO
    0x106 -> 104  // SMB_FIND_FILE_ID_BOTH_DIRECTORY_INFO
    else -> -1
}

/**
 * Escreve uma entrada de busca. NextEntryOffset fica 0 (quem chama ajusta).
 * FileNameLength não conta o terminador, que vai em seguida (o PS2 aceita os dois jeitos).
 * Cada metadado é lido uma vez só (no SAF cada chamada pode ser IPC).
 */
internal fun writeFindEntry(o: Out, level: Int, e: DirEntry, name: ByteArray, unicode: Boolean, readOnly: Boolean) {
    o.u32(0)  // NextEntryOffset
    o.u32(0)  // FileIndex
    if (level == 0x103) {
        o.u32(name.size)
    } else {
        val f = e.file
        val dir = f.isDirectory
        val size = if (dir) 0L else f.length()
        val t = fileTime(f.lastModified())
        o.u64(t); o.u64(t); o.u64(t); o.u64(t)  // criação, acesso, escrita, mudança
        o.u64(size)
        o.u64(allocOf(size))
        o.u32(attrsOf(dir, readOnly))
        o.u32(name.size)
        if (level != 0x101) o.u32(0)  // EaSize
        when (level) {
            0x104 -> {
                o.u8(0); o.u8(0); o.zeros(24)  // sem nome curto 8.3
            }
            0x105 -> {
                o.u32(0); o.u64(fileId(f))
            }
            0x106 -> {
                o.u8(0); o.u8(0); o.zeros(24); o.u16(0); o.u64(fileId(f))
            }
        }
    }
    o.bytes(name)
    if (unicode) o.u16(0) else o.u8(0)
}

/** FileId estável derivado de [VFile.path] (a identidade do item). */
internal fun fileId(f: VFile): Long = (f.path.hashCode().toLong() and 0xFFFFFFFFL) or 0x1_0000_0000L
