package br.ps2ponte.core.block

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Dispositivo de blocos (pendrive cru, exFAT virtual ou imagem de disco).
 * Implementações devem ser thread-safe (UDPBD e UDPFS podem ler ao mesmo tempo).
 */
interface BlockDevice : Closeable {
    val sectorSize: Int
    val sectorCount: Long
    val readOnly: Boolean

    /** Lê [count] setores a partir de [lba] para buf[off ...]. Lança IOException em erro. */
    @Throws(IOException::class)
    fun read(lba: Long, count: Int, buf: ByteArray, off: Int)

    /** Grava [count] setores a partir de [lba] vindos de buf[off ...]. Lança IOException em erro. */
    @Throws(IOException::class)
    fun write(lba: Long, count: Int, buf: ByteArray, off: Int)

    fun flush() {}

    override fun close() {}
}

/** Imagem de disco num arquivo comum (útil em testes e para servir um .img). */
class FileBlockDevice(file: File, override val readOnly: Boolean = false) : BlockDevice {
    private val raf = RandomAccessFile(file, if (readOnly) "r" else "rw")
    override val sectorSize = 512
    override val sectorCount = raf.length() / 512

    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) = synchronized(raf) {
        checkRange(lba, count)
        raf.seek(lba * 512)
        raf.readFully(buf, off, count * 512)
    }

    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) = synchronized(raf) {
        if (readOnly) throw IOException("somente leitura")
        checkRange(lba, count)
        raf.seek(lba * 512)
        raf.write(buf, off, count * 512)
    }

    private fun checkRange(lba: Long, count: Int) {
        if (lba < 0 || count < 0 || lba + count > sectorCount) throw IOException("setor fora do disco: $lba+$count")
    }

    override fun close() = raf.close()
}

/**
 * Expõe setores de 512 bytes sobre um dispositivo com setor físico maior (ex.: HD USB de 4 KiB).
 * O PS2 (BDM/UDPBD) só trabalha com 512.
 */
class Sector512Adapter(private val inner: BlockDevice) : BlockDevice {
    private val ratio = inner.sectorSize / 512
    override val sectorSize = 512
    override val sectorCount = inner.sectorCount * ratio
    override val readOnly get() = inner.readOnly

    init {
        require(inner.sectorSize % 512 == 0 && inner.sectorSize >= 512) { "setor físico inválido: ${inner.sectorSize}" }
    }

    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) {
        if (ratio == 1) return inner.read(lba, count, buf, off)
        val first = lba / ratio
        val last = (lba + count - 1) / ratio
        val n = (last - first + 1).toInt()
        val tmp = ByteArray(n * inner.sectorSize)
        inner.read(first, n, tmp, 0)
        val skip = ((lba - first * ratio) * 512).toInt()
        System.arraycopy(tmp, skip, buf, off, count * 512)
    }

    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) = synchronized(this) {
        if (ratio == 1) return inner.write(lba, count, buf, off)
        val first = lba / ratio
        val last = (lba + count - 1) / ratio
        val n = (last - first + 1).toInt()
        val tmp = ByteArray(n * inner.sectorSize)
        inner.read(first, n, tmp, 0)
        val skip = ((lba - first * ratio) * 512).toInt()
        System.arraycopy(buf, off, tmp, skip, count * 512)
        inner.write(first, n, tmp, 0)
    }

    override fun flush() = inner.flush()
    override fun close() = inner.close()
}
