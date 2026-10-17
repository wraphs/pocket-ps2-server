package br.ps2ponte.core.smb

import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.util.concurrent.atomic.AtomicInteger

/**
 * VFile que NÃO é LocalFile, como o do SAF: caminhos "saf:/arvore/...", que não existem no disco,
 * e nenhum acesso a java.io.File por quem usa. Por dentro delega a outro VFile.
 * Conta as chamadas de metadados ([metadataCalls]), que no SAF seriam IPC.
 */
class FakeSafFile private constructor(
    private val inner: VFile,
    override val path: String,
    override val name: String,
    private val parent: FakeSafFile?,
    val metadataCalls: AtomicInteger,
) : VFile {
    override val parentFile: VFile? get() = parent
    override val isDirectory: Boolean get() = count { inner.isDirectory }
    override val isFile: Boolean get() = count { inner.isFile }
    override fun exists() = count { inner.exists() }
    override fun length() = count { inner.length() }
    override fun lastModified() = count { inner.lastModified() }
    override fun listFiles(): List<VFile>? = count { inner.listFiles() }?.map { wrap(it, it.name) }
    override fun child(name: String): VFile = wrap(inner.child(name), name)
    override fun mkdir() = inner.mkdir()
    override fun delete() = inner.delete()
    override fun renameTo(dest: VFile) = dest is FakeSafFile && inner.renameTo(dest.inner)
    override fun open(write: Boolean): VHandle = inner.open(write)
    override fun totalSpace() = inner.totalSpace()
    override fun usableSpace() = inner.usableSpace()

    override fun equals(other: Any?) = other is VFile && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path

    private fun wrap(v: VFile, childName: String) = FakeSafFile(v, "$path/$childName", childName, this, metadataCalls)

    private inline fun <T> count(f: () -> T): T {
        metadataCalls.incrementAndGet()
        return f()
    }

    companion object {
        fun root(inner: VFile) = FakeSafFile(inner, "saf:/arvore", "arvore", null, AtomicInteger())
    }
}
