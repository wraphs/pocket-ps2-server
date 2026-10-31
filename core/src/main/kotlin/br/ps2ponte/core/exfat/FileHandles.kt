package br.ps2ponte.core.exfat

import br.ps2ponte.core.t
import br.ps2ponte.core.fs.VHandle
import java.io.IOException

/**
 * Arquivos reais abertos (LRU pequeno de [VHandle]). Leituras e gravações são posicionais, então
 * várias threads podem usar o mesmo arquivo; um arquivo tirado do cache só é fechado quando
 * ninguém mais o está usando.
 */
internal class FileHandles(private val max: Int) {
    internal class H(val handle: VHandle, val writable: Boolean) {
        var users = 0
        var retired = false
    }

    private val map = LinkedHashMap<Node, H>(32, 0.75f, true)
    private var closed = false

    fun acquire(n: Node, write: Boolean): H = synchronized(this) {
        if (closed) throw IOException(t("disco fechado", "disk closed"))
        val cur = map[n]
        if (cur != null && (cur.writable || !write)) {
            cur.users++
            return cur
        }
        // open(true) cria o arquivo se ele sumiu; aí a gravação não deve acontecer.
        if (write && !n.file.exists()) throw IOException(t("arquivo não existe mais", "file no longer exists"))
        val h = H(n.file.open(write), write)
        if (cur != null) {
            map.remove(n)
            retire(cur)
        }
        map[n] = h
        h.users++
        while (map.size > max) {
            val it = map.entries.iterator()
            val eldest = it.next()
            it.remove()
            retire(eldest.value)
        }
        h
    }

    fun release(h: H) = synchronized(this) {
        h.users--
        if (h.retired && h.users == 0) closeQuietly(h)
    }

    /** Lê até len bytes a partir de pos; devolve quantos leu (menos no fim do arquivo). */
    fun read(n: Node, pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val h = acquire(n, false)
        try {
            return h.handle.readFully(pos, buf, off, len)
        } finally {
            release(h)
        }
    }

    fun write(n: Node, pos: Long, buf: ByteArray, off: Int, len: Int) {
        val h = acquire(n, true)
        try {
            h.handle.write(pos, buf, off, len)
        } finally {
            release(h)
        }
    }

    fun close() = synchronized(this) {
        closed = true
        for (h in map.values) retire(h)
        map.clear()
    }

    private fun retire(h: H) {
        h.retired = true
        if (h.users == 0) closeQuietly(h)
    }

    private fun closeQuietly(h: H) {
        try { h.handle.close() } catch (_: IOException) {}
    }
}
