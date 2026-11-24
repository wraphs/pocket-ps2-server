package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.LocalFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FerramentasTest {
    @TempDir lateinit var dir: File

    /** ISO 9660 mínima: descritor primário + raiz com um arquivo SYSTEM.CNF;1 (no setor 20). */
    private fun isoCom(cnf: String, outros: Int = 3): ByteArray {
        val img = ByteArray(2048 * 24)
        val pvd = 16 * 2048
        img[pvd] = 1
        "CD001".toByteArray().copyInto(img, pvd + 1)
        fun le32(off: Int, v: Int) { img[off] = v.toByte(); img[off + 1] = (v ushr 8).toByte(); img[off + 2] = (v ushr 16).toByte(); img[off + 3] = (v ushr 24).toByte() }
        // Raiz no setor 18, um setor
        le32(pvd + 156 + 2, 18)
        le32(pvd + 156 + 10, 2048)
        var i = 18 * 2048
        fun registro(nome: String, lba: Int, tam: Int) {
            val n = nome.toByteArray()
            val len = 33 + n.size + (if (n.size % 2 == 0) 1 else 0)
            img[i] = len.toByte()
            le32(i + 2, lba)
            le32(i + 10, tam)
            img[i + 32] = n.size.toByte()
            n.copyInto(img, i + 33)
            i += len
        }
        registro("\u0000", 18, 2048)
        registro("\u0001", 18, 2048)
        repeat(outros) { registro("ARQ$it.IRX;1", 21, 10) }
        val c = cnf.toByteArray()
        registro("SYSTEM.CNF;1", 20, c.size)
        c.copyInto(img, 20 * 2048)
        return img
    }

    @Test
    fun leIdDoSystemCnf() {
        File(dir, "a.iso").writeBytes(isoCom("BOOT2 = cdrom0:\\SLUS_209.46;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n"))
        File(dir, "b.iso").writeBytes(ByteArray(40000))
        val raiz = LocalFile.root(dir)
        assertEquals("SLUS_209.46", raiz.child("a.iso").open(false).use { IdJogo.ler(it) })
        assertNull(raiz.child("b.iso").open(false).use { IdJogo.ler(it) })
        assertEquals("SCES_502.94", IdJogo.deCnf("BOOT2=cdrom0:\\sces_502.94;1"))
        assertEquals("SLUS-20946", IdJogo.serial("SLUS_209.46"))
    }

    @Test
    fun catalogoAchaJogosEReaproveita() {
        File(dir, "DVD").mkdirs()
        File(dir, "POPS").mkdirs()
        File(dir, "ART").mkdirs()
        File(dir, "Baixados/sub").mkdirs()
        File(dir, "DVD/a.iso").writeBytes(isoCom("BOOT2 = cdrom0:\\SLUS_209.46;1"))
        File(dir, "solto.iso").writeBytes(isoCom("BOOT2 = cdrom0:\\SCES_502.94;1"))
        File(dir, "Baixados/sub/b.zso").writeBytes(ByteArray(10))
        File(dir, "POPS/ps1.iso").writeBytes(ByteArray(10)) // PS1: fora do catálogo
        File(dir, "ART/SLUS_209.46_COV.png").writeBytes(ByteArray(10))
        val raiz = LocalFile.root(dir)
        var lidos = 0
        val c1 = Catalogo.varrer(raiz, null) { lidos++ }
        assertEquals(listOf("a.iso", "b.zso", "solto.iso"), c1.jogos.map { it.nome })
        assertEquals(3, lidos)
        val a = c1.jogos.first { it.nome == "a.iso" }
        assertEquals("SLUS_209.46", a.id)
        assertTrue(c1.temCapa(a))
        assertEquals(2, c1.semCapa)
        assertEquals(2, c1.foraDoLugar)
        assertEquals(listOf("DVD/a.iso"), c1.isos.map { it.rel })
        assertTrue(c1.tarDesatualizado)

        // Pelo texto salvo, a segunda varredura não abre nada que não mudou
        val c2 = Catalogo.varrer(raiz, Catalogo.ler(c1.texto())) { lidos++ }
        assertEquals(3, lidos)
        assertEquals(c1.jogos.map { it.rel to it.id }, c2.jogos.map { it.rel to it.id })
        // Arquivo alterado é lido de novo
        File(dir, "solto.iso").writeBytes(isoCom("BOOT2 = cdrom0:\\SCES_999.99;1", outros = 5))
        val c3 = Catalogo.varrer(raiz, c2) { lidos++ }
        assertEquals(4, lidos)
        assertEquals("SCES_999.99", c3.jogos.first { it.nome == "solto.iso" }.id)
    }

    @Test
    fun png8LegivelEPequeno() {
        val l = 140
        val a = 200
        val px = IntArray(l * a) { i ->
            val x = i % l; val y = i / l
            if (x < 10 && y < 10) 0x00000000 else (0xFF shl 24) or ((x * 255 / l) shl 16) or ((y * 255 / a) shl 8) or ((x + y) and 255)
        }
        val png = Png8.codificar(px, l, a)
        assertTrue(Png8.jaEh8Bits(png))
        val f = File(dir, "c.png").apply { writeBytes(png) }
        val img = ImageIO.read(f)
        assertEquals(l, img.width)
        assertEquals(a, img.height)
        // Pixel transparente continua transparente; o resto fica perto da cor original
        assertEquals(0, img.getRGB(0, 0) ushr 24)
        val orig = px[150 * l + 100]
        val lido = img.getRGB(100, 150)
        for (s in listOf(16, 8, 0)) assertTrue(Math.abs(((orig ushr s) and 255) - ((lido ushr s) and 255)) < 40, "canal $s longe demais")
        assertEquals(140 to 200, Png8.caber(280, 400, 140, 200))
        assertEquals(100 to 50, Png8.caber(100, 50, 140, 200))
    }

    @Test
    fun tarComCabecalhoValido() {
        val raiz = LocalFile.root(dir)
        raiz.child("art.tar").open(true).use { h ->
            val t = Tar(h)
            t.adicionar("SLUS_209.46_COV.png", ByteArray(700) { 1 })
            t.adicionar("SLUS_209.46_LGO.png", ByteArray(512) { 2 })
            t.terminar()
        }
        val b = File(dir, "art.tar").readBytes()
        assertEquals(512 + 1024 + 512 + 512 + 1024, b.size)
        assertEquals("SLUS_209.46_COV.png", String(b, 0, 19))
        assertEquals("ustar", String(b, 257, 5))
        assertEquals(700, String(b, 124, 11).toInt(8))
        // Soma de verificação: bytes do cabeçalho com o campo da soma como espaços
        val h = b.copyOf(512)
        val gravada = String(h, 148, 6).toInt(8)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        assertEquals(gravada, h.sumOf { it.toInt() and 0xFF })
        assertEquals("SLUS_209.46_LGO.png", String(b, 1536, 19))
    }
}
