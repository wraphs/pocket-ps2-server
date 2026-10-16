package br.ps2ponte.core.block

import br.ps2ponte.core.exfat.VirtualExFat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class ParticoesTest {
    @TempDir lateinit var dir: File

    /** Disco em memória com setores dados (o resto zerado). */
    private class Disco(private val setores: Map<Long, ByteArray>, override val sectorCount: Long = 100_000) : BlockDevice {
        override val sectorSize = 512
        override val readOnly = true
        override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) {
            for (i in 0 until count) (setores[lba + i] ?: ByteArray(512)).copyInto(buf, off + i * 512)
        }
        override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) = error("somente leitura")
    }

    private fun boot(oem: String) = ByteArray(512).also { oem.toByteArray().copyInto(it, 3) }

    private fun mbr(vararg particoes: Pair<Int, Long>) = ByteArray(512).also { s ->
        particoes.forEachIndexed { i, (tipo, lba) ->
            val e = 446 + i * 16
            s[e + 4] = tipo.toByte()
            for (k in 0 until 4) s[e + 8 + k] = (lba shr (8 * k)).toByte()
        }
        s[510] = 0x55; s[511] = 0xAA.toByte()
    }

    @Test
    fun `MBR do Rufus com NTFS e a particao UEFI_NTFS em FAT`() {
        val fat = ByteArray(512).also { "FAT12   ".toByteArray().copyInto(it, 54) }
        val d = Disco(mapOf(0L to mbr(0x07 to 2048L, 0xEF to 90_000L), 2048L to boot("NTFS    "), 90_000L to fat))
        assertEquals(listOf("NTFS", "FAT"), Particoes.sistemas(d))
    }

    @Test
    fun `GPT com particao exFAT`() {
        val h = ByteArray(512).also { "EFI PART".toByteArray().copyInto(it, 0); it[72] = 2; it[84] = 128.toByte() }
        val e = ByteArray(512).also { it[32] = 0x00; it[33] = 0x08 } // LBA 2048
        val d = Disco(mapOf(0L to mbr(0xEE to 1L), 1L to h, 2L to e, 2048L to boot("EXFAT   ")))
        assertEquals(listOf("EXFAT"), Particoes.sistemas(d))
    }

    @Test
    fun `o disco exFAT virtual e reconhecido como exFAT`() {
        File(dir, "DVD").mkdirs()
        File(dir, "DVD/a.iso").writeBytes(ByteArray(1000))
        VirtualExFat.build(dir, freeBytes = 1L shl 20).use { assertEquals(listOf("EXFAT"), Particoes.sistemas(it)) }
    }
}
