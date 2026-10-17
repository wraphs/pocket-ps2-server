package br.ps2ponte.core.exfat

import br.ps2ponte.core.fs.LocalFile
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * VFile de teste que se comporta como o do SAF: caminho próprio ("saf://..."), atributos guardados na
 * listagem da pasta-mãe e contadores das "consultas". Por dentro usa LocalFile, mas quem recebe este
 * objeto não tem como chegar a um java.io.File (o caminho nem é um caminho do sistema).
 */
class SafLikeFile private constructor(
    private val inner: VFile,
    override val path: String,
    override val parentFile: VFile?,
    private val calls: Calls,
    private val cache: Attrs?,
) : VFile {
    class Calls {
        val listFiles = AtomicInteger()
        val exists = AtomicInteger()
        /** Atributos pedidos fora do que veio na listagem (no SAF, uma consulta cada). */
        val uncached = AtomicInteger()
        val rootAttrs = AtomicInteger()
        val opens = AtomicInteger()
        val openHandles = AtomicInteger()
    }

    private class Attrs(val name: String, val isDir: Boolean, val isFile: Boolean, val length: Long, val modified: Long)

    private fun miss() {
        calls.uncached.incrementAndGet()
        if (parentFile == null) calls.rootAttrs.incrementAndGet()
    }

    override val name: String get() = cache?.name ?: inner.name
    override val isDirectory: Boolean get() = cache?.isDir ?: run { miss(); inner.isDirectory }
    override val isFile: Boolean get() = cache?.isFile ?: run { miss(); inner.isFile }
    override fun length(): Long = cache?.length ?: run { miss(); inner.length() }
    override fun lastModified(): Long = cache?.modified ?: run { miss(); inner.lastModified() }

    override fun exists(): Boolean {
        calls.exists.incrementAndGet()
        return inner.exists()
    }

    override fun listFiles(): List<VFile>? {
        calls.listFiles.incrementAndGet()
        return inner.listFiles()?.map { c ->
            SafLikeFile(c, "$path/${c.name}", this, calls, Attrs(c.name, c.isDirectory, c.isFile, c.length(), c.lastModified()))
        }
    }

    override fun child(name: String): VFile = SafLikeFile(inner.child(name), "$path/$name", this, calls, null)
    override fun mkdir() = inner.mkdir()
    override fun delete() = inner.delete()
    override fun renameTo(dest: VFile) = dest is SafLikeFile && inner.renameTo(dest.inner)

    override fun open(write: Boolean): VHandle {
        calls.opens.incrementAndGet()
        val h = inner.open(write)
        calls.openHandles.incrementAndGet()
        return object : VHandle by h {
            override fun close() {
                calls.openHandles.decrementAndGet()
                h.close()
            }
        }
    }

    override fun equals(other: Any?) = other is SafLikeFile && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path

    companion object {
        fun root(dir: File, calls: Calls): SafLikeFile = SafLikeFile(LocalFile.root(dir), "saf://arvore", null, calls, null)
    }
}
