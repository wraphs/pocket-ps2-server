package br.ps2ponte.core.exfat

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Arquivos reais abertos (LRU pequeno). Leituras e gravações usam posição explícita no FileChannel,
 * então várias threads podem usar o mesmo arquivo; um arquivo tirado do cache só é fechado
 * quando ninguém mais o está usando.
 */
internal class FileHandles(private val max: Int) {
    internal class H(val raf: RandomAccessFile, val writable: Boolean) {
        var users = 0
        var retired = false
    }

    private val map = LinkedHashMap<Node, H>(32, 0.75f, true)
    private var closed = false

    fun acquire(n: Node, write: Boolean): H = synchronized(this) {
        if (closed) throw IOException("disco fechado")
        val cur = map[n]
        if (cur != null && (cur.writable || !write)) {
            cur.users++
            return cur
        }
        if (write && !n.file.isFile) throw IOException("arquivo não existe mais")
        val h = H(RandomAccessFile(n.file, if (write) "rw" else "r"), write)
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
            val bb = ByteBuffer.wrap(buf, off, len)
            val ch = h.raf.channel
            var p = pos
            while (bb.hasRemaining()) {
                val r = ch.read(bb, p)
                if (r <= 0) break
                p += r
            }
            return bb.position() - off
        } finally {
            release(h)
        }
    }

    fun write(n: Node, pos: Long, buf: ByteArray, off: Int, len: Int) {
        val h = acquire(n, true)
        try {
            val bb = ByteBuffer.wrap(buf, off, len)
            val ch = h.raf.channel
            var p = pos
            while (bb.hasRemaining()) p += ch.write(bb, p)
        } finally {
            release(h)
        }
    }

    fun flush() {
        val list = synchronized(this) { map.values.filter { it.writable }.onEach { it.users++ } }
        try {
            for (h in list) h.raf.channel.force(false)
        } finally {
            for (h in list) release(h)
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
        try { h.raf.close() } catch (_: IOException) {}
    }
}
