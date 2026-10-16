package br.ps2ponte.core.exfat

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.TimeZone
import java.util.zip.CRC32

/**
 * Somas e tabelas conferidas contra o FatFs do ps2sdk compilado no PC
 * (scripts/fatfs-harness: `harness_mbr --vectors` e `harness_mbr --mkfs-ref`).
 */
class ExFatFormatTest {
    @Test
    fun nameHashIgualAoFatFs() {
        // xname_sum() do FatFs
        assertEquals(0xE025, ExFat.nameHash("PS2"))
        assertEquals(0xE02E, ExFat.nameHash("DVD"))
        assertEquals(0xA594, ExFat.nameHash("conf_opl.cfg"))
        assertEquals(0xA60B, ExFat.nameHash("SLUS_200.62.Jogo de Teste.iso"))
        assertEquals(0x9A46, ExFat.nameHash("Ação"))
        assertEquals(0x4742, ExFat.nameHash("Coleção é ç"))
        assertEquals(0xF033, ExFat.nameHash("ÿµı"))
        assertEquals(0xC82B, ExFat.nameHash("aBc"))
        assertEquals(ExFat.nameHash("ABC"), ExFat.nameHash("abc"))
    }

    @Test
    fun setChecksumIgualAoFatFs() {
        val b = ByteArray(96) { (it * 7 + 3).toByte() }
        b[1] = 2
        assertEquals(0x3F40, ExFat.setChecksum(b, 0, 96)) // xdir_sum()
        b[2] = 0; b[3] = 0 // o próprio campo não entra na soma
        assertEquals(0x3F40, ExFat.setChecksum(b, 0, 96))
    }

    @Test
    fun bootChecksumIgualAoFatFs() {
        val b = ByteArray(11 * 512) { (it * 13 + 5).toByte() }
        assertEquals(0x185A0A5D, ExFat.bootChecksum(b)) // xsum32() do f_mkfs
        b[106] = 1; b[107] = 2; b[112] = 99 // VolumeFlags e PercentInUse ficam de fora
        assertEquals(0x185A0A5D, ExFat.bootChecksum(b))
        b[105] = 0x77
        assertNotEquals(0x185A0A5D, ExFat.bootChecksum(b))
    }

    @Test
    fun tabelaUpCaseIgualADoMkfsDoFatFs() {
        val t = UpCase.table
        assertEquals(4104, t.size)
        assertEquals(0x38F509B0, UpCase.tableChecksum)
        assertEquals(0x16B481CAL, CRC32().apply { update(t) }.value)
        assertArrayEquals(UpCase.map, UpCase.decompress(t))
        // Não é a tabela recomendada pela Microsoft (5836 bytes, 0xE619D30D): é a do FatFs, que o PS2 usa.
        assertNotEquals(0xE619D30D.toInt(), UpCase.tableChecksum)
    }

    @Test
    fun tableChecksumNumaTabelaPequena() {
        // soma = rotr(soma) + byte: 0x41 -> 0x00000041; 0x00 -> 0x80000020
        assertEquals(0x80000020.toInt(), ExFat.tableChecksum(byteArrayOf(0x41, 0x00), 0, 2))
    }

    @Test
    fun maiusculasComoOFatFs() {
        for (c in 0 until 128) assertEquals(Character.toUpperCase(c.toChar()), UpCase.map[c], "U+%04X".format(c))
        for (c in "áàâãéêíóôõúüçñ") assertEquals(Character.toUpperCase(c), UpCase.map[c.code], "$c")
        assertEquals(0x0178, UpCase.upper(0x00FF)) // ÿ -> Ÿ
        assertEquals(0x00B5, UpCase.upper(0x00B5)) // µ fica (o FatFs não converte)
        assertEquals(0x0131, UpCase.upper(0x0131)) // ı fica
        assertEquals(0x03A3, UpCase.upper(0x03C2)) // ς -> Σ
        assertEquals(0x1F88, UpCase.upper(0x1F80))
        assertEquals(0xFF21, UpCase.upper(0xFF41))
    }

    @Test
    fun dataHoraNoFormatoDos() {
        val utc = TimeZone.getTimeZone("UTC")
        // 16/10/2026 12:34:57,120
        val ms = 1_792_154_097_120L
        val t = ExFat.timestamp(ms, utc)
        val ts = (t ushr 8).toInt()
        assertEquals(2026 - 1980, ts ushr 25)
        assertEquals(10, (ts ushr 21) and 0xF)
        assertEquals(16, (ts ushr 16) and 0x1F)
        assertEquals(12, (ts ushr 11) and 0x1F)
        assertEquals(34, (ts ushr 5) and 0x3F)
        assertEquals(28, ts and 0x1F) // 56 s / 2
        assertEquals(112, (t and 0xFF).toInt()) // 1 s + 120 ms em unidades de 10 ms
        // Fuso: 3 h a menos em São Paulo
        val sp = (ExFat.timestamp(ms, TimeZone.getTimeZone("America/Sao_Paulo")) ushr 8).toInt()
        assertEquals(9, (sp ushr 11) and 0x1F)
        // Antes de 1980 vira 01/01/1980
        assertEquals((1 shl 21) or (1 shl 16), (ExFat.timestamp(0, utc) ushr 8).toInt())
    }

    @Test
    fun conjuntoDeEntradas() {
        val name = "Nome comprido de 20c"
        val b = ByteArray(4 * 32)
        val len = ExFat.writeEntrySet(b, 0, name, false, 1234, 5_000_000_000L, (0x5A6B7C8DL shl 8) or 150)
        assertEquals(4 * 32, len)
        assertEquals(0x85, b[0].toInt() and 0xFF)
        assertEquals(3, b[1].toInt())
        assertEquals(ExFat.setChecksum(b, 0, len), ExFat.get16(b, 2))
        assertEquals(0x20, ExFat.get16(b, 4))
        assertEquals(0x5A6B7C8D, ExFat.get32(b, 12))
        assertEquals(150, b[21].toInt() and 0xFF)
        assertEquals(0, b[23].toInt()) // UtcOffset
        assertEquals(0xC0, b[32].toInt() and 0xFF)
        assertEquals(0x01, b[33].toInt()) // AllocationPossible, NoFatChain = 0
        assertEquals(20, b[35].toInt())
        assertEquals(ExFat.nameHash(name), ExFat.get16(b, 36))
        assertEquals(5_000_000_000L, ExFat.get64(b, 40))
        assertEquals(1234, ExFat.get32(b, 52))
        assertEquals(5_000_000_000L, ExFat.get64(b, 56))
        assertEquals(0xC1, b[64].toInt() and 0xFF)
        assertEquals('N'.code, ExFat.get16(b, 66))
        assertEquals('c'.code, ExFat.get16(b, 96 + 2 + 4 * 2)) // 20º caractere
        assertEquals(0, ExFat.get16(b, 96 + 2 + 5 * 2))
    }

    @Test
    fun nomesRecusados() {
        assertNull(TreeSnapshot.badName("Jogo (USA).iso"))
        assertNull(TreeSnapshot.badName("Coleção é ç"))
        assertNull(TreeSnapshot.badName("x".repeat(255)))
        assertEquals("nome com mais de 255 caracteres", TreeSnapshot.badName("x".repeat(256)))
        for (bad in listOf("a:b", "a?b", "a*b", "a|b", "a<b", "a\"b", "a\\b", "a\u0001b", "a\u007Fb"))
            assertEquals("caractere inválido no nome", TreeSnapshot.badName(bad), bad)
        assertEquals("nome termina com ponto ou espaço", TreeSnapshot.badName("abc."))
        assertEquals("nome termina com ponto ou espaço", TreeSnapshot.badName("abc "))
    }
}
