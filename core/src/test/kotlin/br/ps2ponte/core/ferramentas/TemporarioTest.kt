package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.LocalFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporarioTest {
    @TempDir lateinit var dir: File

    @Test
    fun marcaEsperaETraz() {
        val jogos = File(dir, "sd").apply { mkdirs() }
        File(jogos, "DVD").mkdirs()
        val temp = File(dir, "interno/temporario").apply { mkdirs() }
        val dados = ByteArray(300_000) { (it % 251).toByte() }
        File(temp, "Jogo.zso.part").writeBytes(dados)

        val t = LocalFile.root(temp)
        // Pronto: nome final no temporário + anotação do destino
        val pronto = Temporario.marcar(t.child("Jogo.zso.part"), "Jogo.zso", "DVD/Jogo.zso")
        assertTrue(File(temp, "Jogo.zso").exists())
        assertFalse(File(temp, "Jogo.zso.part").exists())
        assertEquals(listOf("Jogo.zso" to "DVD/Jogo.zso"), Temporario.pendentes(t).map { it.first.name to it.second })

        // Sem espaço no destino: recusa antes de copiar e o arquivo continua esperando, inteiro
        val raiz = LocalFile.root(jogos)
        val destino = Temporario.destinoEm(raiz, "DVD/Jogo.zso")
        val e = assertFailsWith<Temporario.SemEspaco> { Temporario.trazer(pronto, destino, livre = 1000) { _, _ -> } }
        assertTrue(e.faltam > 0)
        assertFalse(File(jogos, "DVD/Jogo.zso").exists())
        assertFalse(File(jogos, "DVD/Jogo.zso.part").exists())
        assertEquals(1, Temporario.pendentes(t).size)

        // Com espaço: vem com o nome certo (sem ".part") e o temporário fica vazio
        Temporario.trazer(pronto, destino, livre = 0) { _, _ -> }
        assertContentEquals(dados, File(jogos, "DVD/Jogo.zso").readBytes())
        assertFalse(File(jogos, "DVD/Jogo.zso.part").exists())
        assertTrue(temp.listFiles()!!.isEmpty())
        assertTrue(Temporario.pendentes(t).isEmpty())
    }
}
