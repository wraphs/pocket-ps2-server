package br.ps2ponte.core.smb

import br.ps2ponte.core.fs.LocalTree
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cliente que imita o usmb2 (menu do RiptOPL) e o smb2.c (leitor dentro do jogo), montando os pacotes
 * como eles montam: NTLMSSP cru, deslocamentos fixos, um pedido por vez.
 */
class Smb2Test {
    @TempDir lateinit var dir: File
    private var srv: SmbServer? = null
    private val jogo = Random(7).nextBytes(200_000)

    @AfterEach fun fechar() { srv?.close() }

    private fun subir(): Int {
        File(dir, "DVD").mkdirs()
        File(dir, "VMC").mkdirs()
        File(dir, "DVD/SLUS_209.46.Jogo.iso").writeBytes(jogo)
        File(dir, "VMC/slot0.bin").writeBytes(ByteArray(8192))
        val s = SmbServer(LocalTree(dir), port = 0)
        s.start()
        srv = s
        return s.localPort
    }

    private class R(val status: Int, val flags: Int, val mid: Long, val tree: Int, val sessao: Long, val corpo: ByteBuffer, val bruto: ByteArray)

    private class Cliente(porta: Int) : AutoCloseable {
        val s = Socket("127.0.0.1", porta)
        val ent = DataInputStream(s.getInputStream())
        var mid = 0L
        var tree = 0
        var sessao = 0L

        fun pedir(cmd: Int, corpo: ByteArray, charge: Int = 1, creditos: Int = 1): R {
            val h = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            h.put(byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
            h.putShort(64).putShort(charge.toShort()).putInt(0).putShort(cmd.toShort()).putShort(creditos.toShort())
            h.putInt(0).putInt(0).putLong(mid).putInt(0).putInt(tree).putLong(sessao)
            val n = 64 + corpo.size
            val out = s.getOutputStream()
            // usmb2 escreve os 4 bytes como um uint32 big-endian (tipo 0)
            out.write(byteArrayOf(0, (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            out.write(h.array())
            out.write(corpo)
            out.flush()
            val esperado = mid++
            val len = ent.readInt()
            assertTrue(len ushr 24 == 0, "tipo do quadro precisa ser 0 (nada de keep-alive)")
            assertTrue(len >= 64)
            val b = ByteArray(len)
            ent.readFully(b)
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x424D53FE, bb.getInt(0))
            val flags = bb.getInt(16)
            assertTrue(flags and 1 != 0, "SERVER_TO_REDIR é obrigatório no leitor do jogo")
            assertEquals(0, flags and 0x8, "nunca assinar")
            assertEquals(esperado, bb.getLong(24), "MessageId precisa ecoar o pedido")
            assertEquals(0, bb.getInt(20), "sem respostas encadeadas")
            val corpoR = ByteBuffer.wrap(b, 64, len - 64).slice().order(ByteOrder.LITTLE_ENDIAN)
            return R(bb.getInt(8), flags, bb.getLong(24), bb.getInt(36), bb.getLong(40), corpoR, b)
        }

        private fun corpo(n: Int, f: ByteBuffer.() -> Unit): ByteArray =
            ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()

        /** NEGOTIATE {2.0.2, 2.1} + login NTLMSSP do menu (AUTHENTICATE fora do padrão) + TREE_CONNECT. */
        fun entrar(usuario: String = "", share: String = "PS2SMB") {
            val neg = pedir(0, corpo(40) {
                putShort(36).putShort(2).putShort(1).putShort(0).putInt(0)
                put(ByteArray(16)).putLong(0).putShort(0x0202).putShort(0x0210)
            }, charge = 0)
            assertEquals(0, neg.status)
            assertEquals(65, neg.corpo.getShort(0).toInt())
            assertEquals(0x0210, neg.corpo.getShort(4).toInt())
            assertEquals(0, neg.corpo.getShort(2).toInt() and 0x2, "assinatura não pode ser exigida")
            val maxRead = neg.corpo.getInt(32)
            assertTrue(maxRead in 8192..65536)

            // Perna 1: NEGOTIATE_MESSAGE de 32 bytes, cru, buffer em 0x58, quadro arredondado para 8
            val t1 = corpo(32) { put("NTLMSSP\u0000".toByteArray()); putInt(1); putInt(0x20080227) }
            val r1 = pedir(1, sessaoCorpo(t1))
            assertEquals(0xC0000016L.toInt(), r1.status)
            assertTrue(r1.sessao != 0L)
            sessao = r1.sessao
            val off = r1.corpo.getShort(4).toInt()
            val len = r1.corpo.getShort(6).toInt()
            assertEquals(72, off)
            assertTrue(len >= 48 && r1.corpo.limit() <= 1024)
            val ch = r1.bruto.copyOfRange(off, off + len)
            val c = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("NTLMSSP\u0000", String(ch, 0, 8))
            assertEquals(2, c.getInt(8))
            val tnLen = c.getShort(12).toInt(); val tnOff = c.getInt(16)
            val tiLen = c.getShort(40).toInt(); val tiOff = c.getInt(44)
            assertTrue(tnLen % 2 == 0 && tnOff % 2 == 0 && tiOff % 2 == 0 && tnLen <= 64 && tiLen <= 512)
            // Pares AV com comprimento par, até o EOL
            var p = tiOff
            while (true) {
                val id = c.getShort(p).toInt(); val l = c.getShort(p + 2).toInt()
                assertEquals(0, l % 2)
                if (id == 0) break
                p += 4 + l
            }
            val dominio = ch.copyOfRange(tnOff, tnOff + tnLen)

            // Perna 2: AUTHENTICATE do menu: flags no lugar errado, NegotiateFlags = 0, payload em 72
            val user = usuario.ifEmpty { "Guest" }.toByteArray(Charsets.UTF_16LE)
            val nt = ByteArray(16 + 28) { 1 }
            val t3 = corpo(72 + dominio.size + user.size + nt.size) {
                put("NTLMSSP\u0000".toByteArray()); putInt(3)
                putShort(0).putShort(0).putInt(0)                                     // LM: zero
                putShort(nt.size.toShort()).putShort(nt.size.toShort()).putInt(72 + dominio.size + user.size)
                putShort(dominio.size.toShort()).putShort(dominio.size.toShort()).putInt(72)
                putShort(user.size.toShort()).putShort(user.size.toShort()).putInt(72 + dominio.size)
                putShort(0).putShort(0).putInt(0)                                     // estação
                putShort(0).putShort(0).putInt(0x20088037)                            // chave: offset com as flags
                putInt(0).putInt(0)                                                   // NegotiateFlags = 0
                put(dominio); put(user); put(nt)
            }
            val r2 = pedir(1, sessaoCorpo(t3))
            assertEquals(0, r2.status)
            assertEquals(sessao, r2.sessao, "mesma SessionId nas duas pernas")

            val unc = "\\\\192.168.0.10\\$share".toByteArray(Charsets.UTF_16LE)
            val tc = pedir(3, corpo(8 + unc.size) { putShort(9).putShort(0).putShort(0x48).putShort(unc.size.toShort()); put(unc) })
            assertEquals(0, tc.status)
            assertTrue(tc.tree != 0)
            tree = tc.tree
        }

        private fun sessaoCorpo(token: ByteArray): ByteArray {
            val n = (24 + token.size + 7) and 0xFFF8
            return corpo(n) {
                putShort(25).put(0).put(0).putInt(0).putInt(0).putShort(0x58).putShort(token.size.toShort()).putLong(0)
                put(token)
            }
        }

        /** CREATE no formato do usmb2: nome em 0x78 e 8 bytes de enchimento no fim. */
        fun criar(nome: String, acesso: Int, disp: Int, opcoes: Int, attrs: Int = 0): R {
            val n = nome.toByteArray(Charsets.UTF_16LE)
            return pedir(5, corpo(56 + n.size + 8) {
                putShort(57).put(0).put(0).putInt(2).putLong(0).putLong(0)
                putInt(acesso).putInt(attrs).putInt(3).putInt(disp).putInt(opcoes)
                putShort(0x78).putShort(n.size.toShort()).putInt(0).putInt(0)
                put(n)
            })
        }

        fun id(r: R): ByteArray = ByteArray(16).also { r.corpo.position(64); r.corpo.get(it); r.corpo.position(0) }

        fun fecharArq(id: ByteArray): R = pedir(6, corpo(24) { putShort(24).putShort(0).putInt(0); put(id) })

        fun ler(id: ByteArray, pos: Long, n: Int, padding: Int): R = pedir(8, corpo(if (padding == 0) 56 else 49) {
            putShort(49).put(padding.toByte()).put(0).putInt(n).putLong(pos); put(id); putInt(0).putInt(0).putInt(0).putShort(0).putShort(0)
        })

        fun escrever(id: ByteArray, pos: Long, dados: ByteArray): R = pedir(9, corpo(48 + dados.size) {
            putShort(49).putShort(0x70).putInt(dados.size).putLong(pos); put(id); putInt(0).putInt(0).putShort(0).putShort(0).putInt(0)
            put(dados)
        })

        fun listar(id: ByteArray): R {
            val p = "*".toByteArray(Charsets.UTF_16LE)
            return pedir(14, corpo(32 + p.size) {
                putShort(33).put(1).put(0).putInt(0); put(id); putShort(0x60).putShort(p.size.toShort()).putInt(4096); put(p)
            })
        }

        override fun close() = s.close()
    }

    @Test
    fun menuListaLeEApaga() {
        val porta = subir()
        Cliente(porta).use { c ->
            c.entrar()
            // opendir da raiz (nome vazio) e QUERY_DIRECTORY repetido até NO_MORE_FILES
            val raiz = c.criar("", 0x81, 1, 0x01, 0x10)
            assertEquals(0, raiz.status)
            val idRaiz = c.id(raiz)
            val nomes = mutableListOf<String>()
            while (true) {
                val r = c.listar(idRaiz)
                if (r.status == 0x80000006L.toInt()) break
                assertEquals(0, r.status)
                val n = r.corpo.getInt(4)
                assertTrue(n in 1..4096)
                // Dados logo depois do corpo de 8 bytes (o cliente ignora o OutputBufferOffset)
                var e = 8
                while (true) {
                    val prox = r.corpo.getInt(e)
                    val nl = r.corpo.getInt(e + 60)
                    nomes += String(r.bruto, 64 + e + 64, nl, Charsets.UTF_16LE)
                    if (prox == 0) break
                    assertTrue(prox >= 64 && prox % 8 == 0)
                    e += prox
                }
            }
            assertEquals(listOf(".", "..", "DVD", "VMC"), nomes)
            assertEquals(0, c.fecharArq(idRaiz).status)

            // open read-only: EndOfFile em body+48; lseek SEEK_END por QUERY_INFO classe 5
            val f = c.criar("DVD/SLUS_209.46.Jogo.iso", 0x89, 1, 0x40)
            assertEquals(0, f.status)
            assertEquals(jogo.size.toLong(), f.corpo.getLong(48))
            val id = c.id(f)
            val qi = c.pedir(16, ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(41).put(1).put(5).putInt(24).putShort(0).putShort(0).putInt(0).putInt(0).putInt(0); put(id)
            }.array())
            assertEquals(0, qi.status)
            assertEquals(jogo.size.toLong(), qi.corpo.getLong(16))
            // READ do menu (64 KiB, padding 0): dados no byte 80
            val r = c.ler(id, 1000, 65536, 0)
            assertEquals(0, r.status)
            assertEquals(0x50, r.corpo.get(2).toInt())
            val dl = r.corpo.getInt(4)
            assertEquals(65536, dl)
            assertContentEquals(jogo.copyOfRange(1000, 1000 + dl), r.bruto.copyOfRange(80, 80 + dl))
            // Leitura que cruza o fim: curta; depois do fim: END_OF_FILE
            assertEquals(100, c.ler(id, jogo.size - 100L, 8192, 0).corpo.getInt(4))
            assertEquals(0xC0000011L.toInt(), c.ler(id, jogo.size.toLong(), 8192, 0).status)
            c.fecharArq(id)

            // mkdir de pasta existente: OBJECT_NAME_COLLISION
            assertEquals(0xC0000035L.toInt(), c.criar("DVD", 0x80, 2, 0x01, 0x10).status)
            // mkdir novo
            val nova = c.criar("CFG", 0x80, 2, 0x01, 0x10)
            assertEquals(0, nova.status)
            c.fecharArq(c.id(nova))
            assertTrue(File(dir, "CFG").isDirectory)

            // Criar arquivo (O_CREAT|O_TRUNC = OVERWRITE_IF) e escrever
            val cfg = c.criar("CFG\\conf.cfg", 0x8B, 5, 0x40)
            assertEquals(0, cfg.status)
            val w = c.escrever(c.id(cfg), 0, "abc=1\n".toByteArray())
            assertEquals(6, w.corpo.getInt(4))
            c.fecharArq(c.id(cfg))
            assertEquals("abc=1\n", File(dir, "CFG/conf.cfg").readText())

            // rename por SET_INFO (classe 10, buffer em 0x60): destino existente = COLISÃO
            val src = c.criar("CFG\\conf.cfg", 0x00010080, 1, 0)
            val sid = c.id(src)
            fun renomear(destino: String): Int {
                val n = destino.toByteArray(Charsets.UTF_16LE)
                return c.pedir(17, ByteBuffer.allocate(32 + 20 + n.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(33).put(1).put(10).putInt(20 + n.size).putShort(0x60).putShort(0).putInt(0); put(sid)
                    put(0); put(ByteArray(7)); putLong(0); putInt(n.size); put(n)
                }.array()).status
            }
            assertEquals(0xC0000035L.toInt(), renomear("DVD\\SLUS_209.46.Jogo.iso"))
            assertEquals(0, renomear("CFG\\novo.cfg"))
            c.fecharArq(sid)
            assertTrue(File(dir, "CFG/novo.cfg").exists())

            // unlink: DELETE|READ_ATTR com DELETE_ON_CLOSE; apaga no CLOSE
            val del = c.criar("CFG\\novo.cfg", 0x00010080, 1, 0x1040)
            assertEquals(0, del.status)
            assertEquals(0, c.fecharArq(c.id(del)).status)
            assertFalse(File(dir, "CFG/novo.cfg").exists())
            // rmdir de pasta com coisas: falha já no CREATE
            assertEquals(0xC0000101L.toInt(), c.criar("DVD", 0x00010080, 1, 0x1001).status)

            // ECHO
            assertEquals(0, c.pedir(13, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(4).putShort(0).array()).status)
        }
    }

    @Test
    fun jogoLeEGravaVmc() {
        val porta = subir()
        Cliente(porta).use { c ->
            c.entrar(usuario = "")
            // Leitor do jogo: acesso 0x00120089, leituras de 8 KiB com padding 0x50
            val f = c.criar("DVD\\SLUS_209.46.Jogo.iso", 0x00120089, 1, 0x40)
            assertEquals(0, f.status)
            val id = c.id(f)
            for (lsn in listOf(0L, 16L, 50L)) {
                val r = c.ler(id, lsn * 2048, 8192, 0x50)
                assertEquals(0, r.status)
                assertContentEquals(jogo.copyOfRange((lsn * 2048).toInt(), (lsn * 2048).toInt() + 8192), r.bruto.copyOfRange(80, 80 + 8192))
            }
            // VMC: abre um arquivo existente (FILE_OPEN) para gravar e escreve sem FLUSH nem CLOSE
            val v = c.criar("VMC\\slot0.bin", 0x0012019F, 1, 0x40)
            assertEquals(0, v.status)
            val dados = ByteArray(512) { 0x5A }
            assertEquals(512, c.escrever(c.id(v), 1024, dados).corpo.getInt(4))
            val gravado = File(dir, "VMC/slot0.bin").readBytes()
            assertContentEquals(dados, gravado.copyOfRange(1024, 1536))
            assertEquals(8192, gravado.size)
        }
    }
}
