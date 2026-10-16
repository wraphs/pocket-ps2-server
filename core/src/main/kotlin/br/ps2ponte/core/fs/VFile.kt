package br.ps2ponte.core.fs

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Um arquivo ou pasta da árvore servida ao PS2.
 *
 * Existe porque o Android não deixa apps abrirem pendrive USB por caminho (/storage/...): lá a árvore
 * vem do Storage Access Framework (implementação no app). No PC e na memória interna é [LocalFile].
 * A API imita java.io.File para a troca ser direta. [path] identifica o item (logs, ids, comparações);
 * dois VFile com o mesmo [path] são o mesmo item (equals/hashCode por [path]).
 */
interface VFile {
    val name: String
    val path: String
    /** null na raiz da árvore servida. */
    val parentFile: VFile?
    val isDirectory: Boolean
    val isFile: Boolean
    fun exists(): Boolean
    /** Tamanho em bytes (0 para pastas). */
    fun length(): Long
    /** Milissegundos desde 1970 (0 se desconhecido). */
    fun lastModified(): Long
    /** Conteúdo da pasta; null se não for pasta ou não der para ler. */
    fun listFiles(): List<VFile>?
    /** Item [name] dentro desta pasta; pode não existir ainda (para criar). */
    fun child(name: String): VFile
    fun mkdir(): Boolean
    fun mkdirs(): Boolean = (exists() && isDirectory) || (parentFile?.mkdirs() == true && mkdir())
    /** Apaga um arquivo ou uma pasta vazia. */
    fun delete(): Boolean
    /** Renomeia/move para [dest] (que ainda não existe). */
    fun renameTo(dest: VFile): Boolean
    /** Abre para leitura/gravação posicional. Com [write], cria o arquivo se ele não existir. */
    @Throws(IOException::class)
    fun open(write: Boolean): VHandle

    /** Espaço do volume em bytes (0 se desconhecido). */
    fun totalSpace(): Long = 0
    fun usableSpace(): Long = 0
}

/** Arquivo aberto, com E/S posicional (thread-safe: várias threads podem ler ao mesmo tempo). */
interface VHandle : Closeable {
    /** Lê até [len] bytes a partir de [pos]; devolve -1 no fim do arquivo. */
    @Throws(IOException::class)
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    @Throws(IOException::class)
    fun write(pos: Long, buf: ByteArray, off: Int, len: Int)

    @Throws(IOException::class)
    fun length(): Long

    @Throws(IOException::class)
    fun setLength(n: Long)

    /** Lê exatamente [len] bytes ou até o fim do arquivo; devolve quantos leu. */
    fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = read(pos + n, buf, off + n, len - n)
            if (r <= 0) break
            n += r
        }
        return n
    }
}

/** VFile sobre java.io.File (PC, testes e memória interna do celular). */
class LocalFile private constructor(val file: File, private val root: File) : VFile {
    override val name: String get() = file.name
    override val path: String get() = file.path
    override val parentFile: VFile? get() = if (file == root) null else file.parentFile?.let { LocalFile(it, root) }
    override val isDirectory get() = file.isDirectory
    override val isFile get() = file.isFile
    override fun exists() = file.exists()
    override fun length() = if (file.isDirectory) 0L else file.length()
    override fun lastModified() = file.lastModified()
    override fun listFiles(): List<VFile>? = file.listFiles()?.map { LocalFile(it, root) }
    override fun child(name: String): VFile = LocalFile(File(file, name), root)
    override fun mkdir() = file.mkdir()
    override fun mkdirs() = file.isDirectory || file.mkdirs()
    override fun delete() = file.delete()
    override fun renameTo(dest: VFile) = dest is LocalFile && file.renameTo(dest.file)
    override fun open(write: Boolean): VHandle = LocalHandle(RandomAccessFile(file, if (write) "rw" else "r"))
    override fun totalSpace() = file.totalSpace
    override fun usableSpace() = file.usableSpace

    override fun equals(other: Any?) = other is LocalFile && other.file == file
    override fun hashCode() = file.hashCode()
    override fun toString() = path

    companion object {
        fun root(dir: File): LocalFile = dir.absoluteFile.let { LocalFile(it, it) }
    }
}

private class LocalHandle(private val raf: RandomAccessFile) : VHandle {
    private val ch: FileChannel = raf.channel

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return ch.read(ByteBuffer.wrap(buf, off, len), pos)
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) p += ch.write(bb, p)
    }

    override fun length() = ch.size()

    override fun setLength(n: Long) = raf.setLength(n)

    override fun close() = raf.close()
}
