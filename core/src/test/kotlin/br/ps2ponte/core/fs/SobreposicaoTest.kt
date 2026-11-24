package br.ps2ponte.core.fs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SobreposicaoTest {
    @TempDir lateinit var dir: File

    @Test
    fun leJuntoEGravaEmCima() {
        val pen = File(dir, "pen").apply { mkdirs() }
        val apoio = File(dir, "apoio").apply { mkdirs() }
        File(pen, "DVD").mkdirs()
        File(pen, "DVD/jogo.iso").writeBytes(ByteArray(5000) { 1 })
        File(pen, "VMC").mkdirs()
        File(pen, "VMC/slot.bin").writeBytes(ByteArray(1000) { 2 })
        File(pen, "ART").mkdirs()
        File(pen, "ART/A_COV.png").writeText("velha")
        val s = Sobreposicao.raiz(LocalFile.root(pen), LocalFile.root(apoio))

        // Capa nova vai para o apoio, criando ART/ lá; a pasta mostra as duas
        s.child("art").child("B_COV.png").open(true).use { it.write(0, "nova".toByteArray(), 0, 4) }
        assertEquals("nova", File(apoio, "ART/B_COV.png").readText())
        assertEquals(listOf("A_COV.png", "B_COV.png"), s.child("ART").listFiles()!!.map { it.name }.sorted())
        assertEquals(listOf("ART", "DVD", "VMC"), s.listFiles()!!.map { it.name }.sorted())

        // Gravar num VMC do pendrive: copia para o apoio e grava lá; o pendrive fica intacto
        s.child("VMC").child("slot.bin").open(true).use { it.write(10, byteArrayOf(9, 9), 0, 2) }
        val vmc = File(apoio, "VMC/slot.bin").readBytes()
        assertEquals(1000, vmc.size)
        assertEquals(9, vmc[10].toInt())
        assertTrue(File(pen, "VMC/slot.bin").readBytes().all { it.toInt() == 2 })
        val lido = ByteArray(1000)
        s.child("VMC").child("slot.bin").open(false).use { it.readFully(0, lido, 0, 1000) }
        assertContentEquals(vmc, lido)

        // O que está só no pendrive não apaga nem renomeia; o do apoio sim
        assertFalse(s.child("DVD").child("jogo.iso").delete())
        assertTrue(s.child("ART").child("B_COV.png").renameTo(s.child("ART").child("C_COV.png")))
        assertTrue(File(apoio, "ART/C_COV.png").exists())
        assertTrue(s.child("ART").child("C_COV.png").delete())
        assertEquals(5000, s.child("DVD").child("jogo.iso").length())
    }
}
