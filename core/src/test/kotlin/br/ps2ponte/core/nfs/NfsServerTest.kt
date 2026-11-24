package br.ps2ponte.core.nfs

import br.ps2ponte.core.fs.LocalTree
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cliente que monta as COMPOUNDs como o unfs4 e lê as respostas nas mesmas posições fixas. */
class NfsServerTest {
    @TempDir lateinit var dir: File
    private var srv: NfsServer? = null
    private val jogo = Random(11).nextBytes(150_000)

    @AfterEach fun fechar() { srv?.close() }

    private fun subir(): Int {
        File(dir, "DVD").mkdirs()
        File(dir, "VMC").mkdirs()
        File(dir, "DVD/Jogo.iso").writeBytes(jogo)
        File(dir, "VMC/slot0.bin").writeBytes(ByteArray(4096))
        // Muitos arquivos, para a listagem precisar de várias chamadas (1024 bytes por vez)
        for (i in 0 until 40) File(dir, "DVD/jogo_com_nome_comprido_numero_$i.iso").writeBytes(ByteArray(10))
        val s = NfsServer(LocalTree(dir), port = 0)
        s.start()
        srv = s
        return s.localPort
    }

    private class Op(val corpo: ByteBuffer.() -> Unit, val tam: Int)

    private class Cliente(porta: Int) : AutoCloseable {
        val s = Socket("127.0.0.1", porta)
        val ent = DataInputStream(s.getInputStream())
        var xid = 1000

        /** Uma COMPOUND; devolve o registro inteiro (com a marca) como o cliente vê. */
        fun compound(dados: Int, vararg ops: Op): ByteBuffer {
            val tam = 76 + ops.sumOf { it.tam }
            val bb = ByteBuffer.allocate(tam)
            bb.putInt(Int.MIN_VALUE or (tam - 4)).putInt(xid++).putInt(0).putInt(2).putInt(100003).putInt(4).putInt(1)
            bb.putInt(1).putInt(20).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0) // AUTH_SYS uid 0
            bb.putInt(0).putInt(0) // verf
            bb.putInt(0).putInt(0).putInt(ops.size) // tag vazia, minor 0
            for (o in ops) o.corpo(bb)
            s.getOutputStream().write(bb.array())
            val marca = ent.readInt()
            assertTrue(marca < 0, "resposta num fragmento só")
            val n = marca and 0x7FFFFFFF
            assertTrue(n <= 1024 + dados, "resposta maior que 1024 + dados ($n)")
            val r = ByteArray(n + 4)
            ByteBuffer.wrap(r).putInt(marca)
            ent.readFully(r, 4, n)
            val rb = ByteBuffer.wrap(r)
            assertEquals(1, rb.getInt(8)); assertEquals(0, rb.getInt(12))
            assertEquals(0, rb.getInt(20), "verificador vazio")
            assertEquals(0, rb.getInt(24))
            assertEquals(0, rb.getInt(32), "tag vazia")
            return rb
        }

        fun status(r: ByteBuffer) = r.getInt(28)
        /** RES(u, n) do unfs4: buf[40 + 8n]. */
        fun res(n: Int) = 40 + 8 * n

        fun putroot() = Op({ putInt(24) }, 4)
        fun putfh(fh: ByteArray) = Op({ putInt(22); opaque(fh) }, 8 + pad(fh.size))
        fun lookup(nome: String) = nome.toByteArray().let { b -> Op({ putInt(15); opaque(b) }, 8 + pad(b.size)) }
        fun getfh() = Op({ putInt(10) }, 4)
        fun getattr() = Op({ putInt(9); putInt(2); putInt(0x12); putInt(0x00200000) }, 16)
        fun read(pos: Long, n: Int) = Op({ putInt(25); put(ByteArray(16)); putLong(pos); putInt(n) }, 32)
        fun write(pos: Long, d: ByteArray) = Op({ putInt(38); put(ByteArray(16)); putLong(pos); putInt(2); opaque(d) }, 36 + pad(d.size))
        fun readdir(cookie: Long, verf: ByteArray) = Op({ putInt(26); putLong(cookie); put(verf); putInt(1024); putInt(1032); putInt(2); putInt(0x12); putInt(0x00200000) }, 40)

        fun mount(export: String): ByteArray {
            val partes = export.split('/').filter { it.isNotEmpty() }
            val r = compound(0, putroot(), *partes.map { lookup(it) }.toTypedArray(), getfh())
            assertEquals(0, status(r))
            return fh(r, res(partes.size + 1))
        }

        fun fh(r: ByteBuffer, at: Int): ByteArray {
            val n = r.getInt(at + 8)
            assertTrue(n in 1..64)
            return ByteArray(n).also { r.position(at + 12); r.get(it); r.position(0) }
        }

        override fun close() = s.close()
    }

    @Test
    fun montaLeListaEGrava() {
        val porta = subir()
        val isoFh: ByteArray
        Cliente(porta).use { c ->
            val raiz = c.mount("/ps2")
            assertContentEquals(c.mount("/"), raiz, "export com nome aponta para a raiz")

            // stat: atributos sempre type, size, mtime nas posições fixas
            val st = c.compound(0, c.putfh(raiz), c.lookup("DVD"), c.lookup("Jogo.iso"), c.getattr())
            assertEquals(0, c.status(st))
            val a = c.res(3) + 8
            assertEquals(2, st.getInt(a))
            val al = st.getInt(a + 12)
            assertTrue(al >= 20)
            assertEquals(1, st.getInt(a + 16))
            assertEquals(jogo.size.toLong(), st.getLong(a + 20))
            assertTrue(st.getLong(a + 28) > 1_000_000_000L)

            // Arquivo que falta: NOENT (o menu decide criar por isso)
            assertEquals(2, c.status(c.compound(0, c.putfh(raiz), c.lookup("nada.cfg"), c.getattr())))

            val lk = c.compound(0, c.putfh(raiz), c.lookup("DVD"), c.lookup("Jogo.iso"), c.getfh())
            isoFh = c.fh(lk, c.res(3))

            // READ: 40 bytes de cabeçalho + PUTFH(8) + READ op/status + eof + len, dados a partir do byte 64
            for ((pos, n) in listOf(0L to 8192, 2048L * 10 to 8192, jogo.size - 100L to 8192)) {
                val r = c.compound(n, c.putfh(isoFh), c.read(pos, n))
                assertEquals(0, c.status(r))
                assertEquals(25, r.getInt(48))
                val len = r.getInt(60)
                assertEquals(minOf(n.toLong(), jogo.size - pos).toInt(), len)
                val dados = ByteArray(len).also { r.position(64); r.get(it) }
                assertContentEquals(jogo.copyOfRange(pos.toInt(), pos.toInt() + len), dados)
            }
            assertEquals(0, c.compound(8192, c.putfh(isoFh), c.read(jogo.size.toLong(), 8192)).getInt(60), "EOF = 0 bytes")

            // READDIR paginado em 1024 bytes, sem "." e "..", cookieverf em 56
            val dvd = c.fh(c.compound(0, c.putfh(raiz), c.lookup("DVD"), c.getfh()), c.res(2))
            val nomes = mutableListOf<String>()
            var cookie = 0L
            var verf = ByteArray(8)
            var voltas = 0
            while (true) {
                val r = c.compound(1024, c.putfh(dvd), c.readdir(cookie, verf))
                assertEquals(0, c.status(r))
                verf = ByteArray(8).also { r.position(56); r.get(it); r.position(0) }
                var p = 64
                val fimLista = r.limit()
                assertTrue(fimLista - 64 <= 1024, "lista + terminador cabem em 1024")
                var eof = false
                while (true) {
                    if (r.getInt(p) == 0) { eof = r.getInt(p + 4) != 0; break }
                    cookie = r.getLong(p + 4)
                    val nl = r.getInt(p + 12)
                    nomes += String(r.array(), p + 16, nl)
                    p += 16 + pad(nl)
                    val bmN = r.getInt(p)
                    p += 4 + 4 * bmN
                    val alen = r.getInt(p)
                    p += 4 + alen
                }
                voltas++
                if (eof) break
            }
            assertTrue(voltas > 1, "devia precisar de mais de uma chamada")
            assertEquals(41, nomes.size)
            assertFalse("." in nomes || ".." in nomes)

            // VMC: WRITE FILE_SYNC com stateid anônimo
            val vmc = c.fh(c.compound(0, c.putfh(raiz), c.lookup("VMC"), c.lookup("slot0.bin"), c.getfh()), c.res(3))
            val d = ByteArray(1000) { 7 }
            val w = c.compound(0, c.putfh(vmc), c.write(512, d))
            assertEquals(0, c.status(w))
            assertEquals(1000, w.getInt(c.res(2)))
            assertContentEquals(d, File(dir, "VMC/slot0.bin").readBytes().copyOfRange(512, 1512))

            // Criar arquivo como o menu: SETCLIENTID; CONFIRM+PUTFH+LOOKUP+OPEN; PATH+GETFH+CLOSE
            val sc = c.compound(0, Op({ putInt(35); put(ByteArray(8)); opaque("unfs4-0000abcd".toByteArray()); putInt(0x40000000); opaque("tcp".toByteArray()); opaque("0.0.0.0.0.0".toByteArray()); putInt(0) }, 4 + 8 + 20 + 4 + 8 + 16 + 4))
            assertEquals(0, c.status(sc))
            val cid = sc.getLong(48)
            val conf = ByteArray(8).also { sc.position(56); sc.get(it); sc.position(0) }
            val nome = "novo.cfg".toByteArray()
            val op = c.compound(0,
                Op({ putInt(36); putLong(cid); put(conf) }, 20),
                c.putfh(raiz), c.lookup("VMC"),
                Op({
                    putInt(18); putInt(0); putInt(3); putInt(0); putLong(cid); opaque(byteArrayOf(1, 2, 3, 4))
                    putInt(1); putInt(0); putInt(2); putInt(0); putInt(2); putInt(4); putInt(0x1A4)
                    putInt(0); opaque(nome)
                }, 4 + 12 + 8 + 8 + 8 + 16 + 4 + 4 + 4 + 8 + pad(nome.size)),
            )
            assertEquals(0, c.status(op))
            val openRes = c.res(4) + 8
            assertEquals(0, op.get(openRes + 39).toInt() and 2, "sem OPEN4_RESULT_CONFIRM")
            assertTrue(File(dir, "VMC/novo.cfg").exists())
            val cl = c.compound(0, c.putfh(raiz), c.lookup("VMC"), c.lookup("novo.cfg"), c.getfh(), Op({ putInt(4); putInt(1); put(ByteArray(16)) }, 24))
            assertEquals(0, c.status(cl))

            // mkdir: CREATE NF4DIR; de novo = EXIST
            fun mkdir(n: String) = c.compound(0, c.putfh(raiz), Op({ putInt(6); putInt(2); opaque(n.toByteArray()); putInt(2); putInt(0); putInt(2); putInt(4); putInt(0x1ED) }, 8 + 4 + pad(n.length) + 24))
            assertEquals(0, c.status(mkdir("CFG")))
            assertEquals(17, c.status(mkdir("CFG")))

            // rename: DIR(old), SAVEFH, PUTFH(raiz)+LOOKUP(destino), RENAME — substitui o destino
            File(dir, "CFG/b.cfg").writeText("velho")
            val rn = c.compound(0, c.putfh(raiz), c.lookup("VMC"), Op({ putInt(32) }, 4), c.putfh(raiz), c.lookup("CFG"),
                Op({ putInt(29); opaque("novo.cfg".toByteArray()); opaque("b.cfg".toByteArray()) }, 4 + 12 + 12))
            assertEquals(0, c.status(rn))
            assertEquals("", File(dir, "CFG/b.cfg").readText())
            assertFalse(File(dir, "VMC/novo.cfg").exists())

            // remove; pasta com coisas = NOTEMPTY
            assertEquals(66, c.status(c.compound(0, c.putfh(raiz), Op({ putInt(28); opaque("CFG".toByteArray()) }, 12))))
            assertEquals(0, c.status(c.compound(0, c.putfh(raiz), c.lookup("CFG"), Op({ putInt(28); opaque("b.cfg".toByteArray()) }, 16))))
            assertFalse(File(dir, "CFG/b.cfg").exists())
        }
        // O jogo guarda o handle da ISO: tem que valer numa conexão nova
        Cliente(porta).use { c ->
            val r = c.compound(4096, c.putfh(isoFh), c.read(100, 4096))
            assertEquals(0, c.status(r))
            assertEquals(4096, r.getInt(60))
        }
    }

    companion object {
        fun pad(n: Int) = (n + 3) and 3.inv()
        fun ByteBuffer.opaque(d: ByteArray) { putInt(d.size); put(d); repeat(pad(d.size) - d.size) { put(0) } }
    }
}
