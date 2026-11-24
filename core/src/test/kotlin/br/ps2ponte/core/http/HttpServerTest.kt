package br.ps2ponte.core.http

import br.ps2ponte.core.fs.ArvoreIso
import br.ps2ponte.core.fs.LocalFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpServerTest {
    @TempDir lateinit var dir: File
    private var srv: HttpServer? = null

    @AfterEach fun fechar() { srv?.close() }

    private val jogo = Random(3).nextBytes(300_000)

    private fun subir(riptopl: Boolean): HttpServer {
        File(dir, "DVD").mkdirs()
        File(dir, "CD").mkdirs()
        File(dir, "DVD/SLUS_201.74.Rumble Racing.iso").writeBytes(jogo)
        File(dir, "CD/Ação, Teste.iso").writeBytes(ByteArray(5000) { 1 })
        File(dir, "DVD/leia.txt").writeText("x")
        val s = HttpServer(ArvoreIso(LocalFile.root(dir), zso = riptopl), port = 0, riptopl = riptopl)
        s.start()
        srv = s
        return s
    }

    /** Cliente mínimo que lê exatamente o corpo pelo Content-Length, como o driver do console. */
    private class Cliente(porta: Int) : AutoCloseable {
        val s = Socket("127.0.0.1", porta)
        val ent = DataInputStream(s.getInputStream())
        fun pedir(caminhoCru: String, range: String? = null): Triple<Int, Map<String, String>, ByteArray> {
            val r = if (range != null) "Range: bytes=$range\r\n" else ""
            s.getOutputStream().write("GET $caminhoCru HTTP/1.1\r\nHost: x\r\n${r}Connection: keep-alive\r\n\r\n".toByteArray(Charsets.UTF_8))
            val linhas = mutableListOf<String>()
            val sb = StringBuilder()
            var total = 0
            while (true) {
                val c = ent.read()
                total++
                if (c == '\n'.code) {
                    val l = sb.toString().trimEnd('\r'); sb.setLength(0)
                    if (l.isEmpty()) break
                    linhas += l
                } else sb.append(c.toChar())
            }
            assertTrue(total < 512, "cabeçalho precisa caber em 512 bytes")
            val cod = linhas[0].split(' ')[1].toInt()
            val cab = linhas.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val corpo = ByteArray(cab["content-length"]!!.toInt())
            ent.readFully(corpo)
            return Triple(cod, cab, corpo)
        }
        override fun close() = s.close()
    }

    @Test
    fun riptoplListaECaminho() {
        val s = subir(riptopl = true)
        Cliente(s.localPort).use { c ->
            val (cod, _, csv) = c.pedir("/PS2/games.csv")
            assertEquals(200, cod)
            val linhas = String(csv, Charsets.UTF_8).trim().lines()
            assertEquals(2, linhas.size)
            assertTrue("\"SLUS_201.74\",\"Rumble Racing\",DVD,\"DVD/SLUS_201.74.Rumble Racing.iso\"" in linhas)
            // Vírgula e acento vão entre aspas; mídia pela pasta
            assertTrue(linhas.any { it.endsWith(",CD,\"CD/Ação, Teste.iso\"") }, linhas.toString())
            // Pedido pelo caminho, com pastas na frente e percent-encoded
            val (c206, cab, corpo) = c.pedir("/PS2/DVD/SLUS_201.74.Rumble%20Racing.iso", "1000-9191")
            assertEquals(206, c206)
            assertEquals("bytes 1000-9191/${jogo.size}", cab["content-range"])
            assertContentEquals(jogo.copyOfRange(1000, 9192), corpo)
            // Mesma conexão: mais leituras seguidas (keep-alive), inclusive o último byte
            val (_, _, fim) = c.pedir("/DVD/SLUS_201.74.Rumble%20Racing.iso", "${jogo.size - 10}-${jogo.size - 1}")
            assertContentEquals(jogo.copyOfRange(jogo.size - 10, jogo.size), fim)
            // Fora do arquivo: 416, e a conexão continua servindo
            assertEquals(416, c.pedir("/DVD/SLUS_201.74.Rumble%20Racing.iso", "${jogo.size}-${jogo.size + 100}").first)
            assertEquals(206, c.pedir("/DVD/SLUS_201.74.Rumble%20Racing.iso", "0-0").first)
            assertEquals(404, c.pedir("/DVD/leia.txt").first)
            assertEquals(404, c.pedir("/../segredo").first)
        }
    }

    @Test
    fun listaSimplesDoOplHttp() {
        val s = subir(riptopl = false)
        Cliente(s.localPort).use { c ->
            val (_, _, csv) = c.pedir("/games.csv")
            // Nome com vírgula fica de fora (o cliente simples não tem escape)
            assertEquals("SLUS_201.74,SLUS_201.74.Rumble Racing.iso,DVD", String(csv).trim())
            // O cliente simples só codifica o espaço e pede pelo nome na raiz
            val (cod, _, corpo) = c.pedir("/SLUS_201.74.Rumble%20Racing.iso", "0-8191")
            assertEquals(206, cod)
            assertContentEquals(jogo.copyOf(8192), corpo)
        }
    }
}
