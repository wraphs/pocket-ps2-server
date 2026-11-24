package br.ps2ponte.core.fs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComprimidoTest {
    @TempDir lateinit var dir: File

    /** Parecido com uma ISO: trechos repetitivos, trechos aleatórios (não compactam) e zeros. */
    private fun imagem(tamanho: Int, semente: Int = 1): ByteArray {
        val r = Random(semente)
        val b = ByteArray(tamanho)
        var i = 0
        while (i < tamanho) {
            val n = minOf(tamanho - i, 1000 + r.nextInt(20000))
            when (r.nextInt(3)) {
                0 -> for (k in 0 until n) b[i + k] = r.nextInt().toByte()
                1 -> { val txt = "SLUS_209.46;1 BOOT2 = cdrom0:\\".toByteArray(); for (k in 0 until n) b[i + k] = txt[k % txt.size] }
                else -> {}
            }
            i += n
        }
        return b
    }

    @Test
    fun lz4IdaEVolta() {
        for (s in 1..20) {
            val src = imagem(2048 + s * 37, s).copyOf(2048)
            val dst = ByteArray(Lz4.limite(src.size))
            val n = Lz4.compactar(src, 0, src.size, dst)
            val volta = ByteArray(src.size)
            assertEquals(src.size, Lz4.descompactar(dst, 0, n, volta, 0, volta.size))
            assertContentEquals(src, volta)
        }
        // Bloco pequeno e bloco todo repetido
        for (src in listOf(ByteArray(5) { it.toByte() }, ByteArray(2048) { 7 })) {
            val dst = ByteArray(Lz4.limite(src.size))
            val n = Lz4.compactar(src, 0, src.size, dst)
            val volta = ByteArray(src.size)
            Lz4.descompactar(dst, 0, n, volta, 0, volta.size)
            assertContentEquals(src, volta)
        }
    }

    private fun idaEVolta(zso: Boolean, alinhamento: Int, tamanho: Int) {
        val orig = imagem(tamanho)
        val iso = File(dir, "jogo.iso").apply { writeBytes(orig) }
        val raiz = LocalFile.root(dir)
        val saida = File(dir, if (zso) "jogo.zso" else "jogo.cso")
        raiz.child("jogo.iso").open(false).use { e ->
            raiz.child(saida.name).open(true).use { s -> Comprimido.compactar(e, s, zso, alinhamento) { _, _ -> true } }
        }
        assertTrue(saida.length() < orig.size, "deveria encolher")
        iso.delete()
        Comprimido.abrir(raiz.child(saida.name).open(false)).use { h ->
            assertEquals(orig.size.toLong(), h.length())
            val tudo = ByteArray(orig.size)
            assertEquals(orig.size, h.readFully(0, tudo, 0, tudo.size))
            assertContentEquals(orig, tudo)
            // Leituras soltas, começando no meio de blocos
            val r = Random(9)
            repeat(300) {
                val pos = r.nextInt(orig.size)
                val n = minOf(1 + r.nextInt(70000), orig.size - pos)
                val b = ByteArray(n)
                assertEquals(n, h.readFully(pos.toLong(), b, 0, n))
                assertContentEquals(orig.copyOfRange(pos, pos + n), b)
            }
            assertEquals(-1, h.read(orig.size.toLong(), ByteArray(4), 0, 4))
        }
        saida.delete()
    }

    @Test fun zso() = idaEVolta(zso = true, alinhamento = 0, tamanho = 3_000_001)
    @Test fun cso() = idaEVolta(zso = false, alinhamento = 0, tamanho = 2_500_000)
    @Test fun zsoAlinhado() = idaEVolta(zso = true, alinhamento = 3, tamanho = 1_000_003)
    @Test fun csoAlinhado() = idaEVolta(zso = false, alinhamento = 2, tamanho = 777_777)

    @Test
    fun arvoreMostraComoIso() {
        val orig = imagem(500_000)
        File(dir, "DVD").mkdirs()
        File(dir, "DVD/a.iso").writeBytes(orig)
        val raiz = LocalFile.root(dir)
        raiz.child("DVD").child("a.iso").open(false).use { e ->
            raiz.child("DVD").child("B.zso").open(true).use { s -> Comprimido.compactar(e, s, true) { _, _ -> true } }
            raiz.child("DVD").child("a.cso").open(true).use { s -> Comprimido.compactar(e, s, false) { _, _ -> true } }
        }
        val arv = ArvoreIso(raiz, zso = true)
        val nomes = arv.child("DVD").listFiles()!!.map { it.name }.sorted()
        // a.cso fica escondido porque já existe a.iso
        assertEquals(listOf("B.iso", "a.iso"), nomes)
        val b = arv.child("DVD").child("B.iso")
        assertEquals(orig.size.toLong(), b.length())
        val lido = ByteArray(orig.size)
        b.open(false).use { it.readFully(0, lido, 0, lido.size) }
        assertContentEquals(orig, lido)

        // Sem o ZSO (SMB: o OPL lê ZSO sozinho), só o CSO vira ISO
        val smb = ArvoreIso(raiz, zso = false)
        assertNotNull(smb.child("DVD").listFiles()!!.firstOrNull { it.name == "B.zso" })
        assertNull(smb.child("DVD").listFiles()!!.firstOrNull { it.name == "B.iso" })
    }
}
