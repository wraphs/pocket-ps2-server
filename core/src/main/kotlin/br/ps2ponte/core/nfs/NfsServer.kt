package br.ps2ponte.core.nfs

import br.ps2ponte.core.EventLog
import br.ps2ponte.core.NoLog
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import br.ps2ponte.core.t
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Servidor NFSv4.0 mínimo (TCP 2049, sem portmapper nem MOUNT) para o unfs4: driver nfs: do Neutrino e
 * nfsman/leitor do RiptOPL.
 *
 * O cliente lê as respostas em posições fixas, então: verificador AUTH_NONE vazio, tag vazia, resultados de
 * PUTFH/LOOKUP/SAVEFH com exatamente 8 bytes e atributos sempre {TYPE, SIZE, TIME_MODIFY} nessa ordem.
 * Os handles valem entre conexões (o jogo guarda o da ISO): o caminho vai dentro do handle quando cabe em
 * 63 bytes; senão, um hash registrado na memória.
 * PUTROOTFH é a pasta servida; "export" "/" ou um nome como "/ps2" ou "/PS2SMB" levam a ela.
 */
class NfsServer(
    private val tree: LocalTree,
    val port: Int = 2049,
    private val log: EventLog = NoLog,
    private val stats: TransferStats = TransferStats(),
) : PonteServer {
    override val name = "NFS"

    @Volatile private var listener: ServerSocket? = null
    private val closed = AtomicBoolean(false)
    private val clientes = ConcurrentHashMap<Socket, Thread>()
    private val hashes = ConcurrentHashMap<String, String>() // handle longo (hex) -> caminho
    private val abertos = object : LinkedHashMap<String, Pair<VHandle, Boolean>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<VHandle, Boolean>>): Boolean {
            if (size <= 16) return false
            try { e.value.first.close() } catch (_: IOException) {}
            return true
        }
    }
    private val verificador = ByteArray(8).also { java.util.Random().nextBytes(it) }
    @Volatile private var proxCliente = 1L

    val localPort: Int get() = listener?.localPort ?: port

    override fun start() {
        check(listener == null) { "NFS já iniciado" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 16)
        } catch (e: IOException) {
            ss.close()
            throw e
        }
        listener = ss
        thread(isDaemon = true, name = "nfs-accept") {
            while (!closed.get()) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                s.tcpNoDelay = true
                val th = thread(isDaemon = true, name = "nfs-${s.inetAddress.hostAddress}", start = false) { atender(s) }
                th.priority = Thread.MAX_PRIORITY
                clientes[s] = th
                th.start()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener?.close() } catch (_: IOException) {}
        for (s in clientes.keys) try { s.close() } catch (_: IOException) {}
        clientes.clear()
        synchronized(abertos) {
            for (v in abertos.values) try { v.first.close() } catch (_: IOException) {}
            abertos.clear()
        }
    }

    // ------------------------------------------------------------------ handles

    /** Handle de um caminho relativo ("" = raiz). */
    internal fun handle(rel: String): ByteArray {
        val b = rel.toByteArray(Charsets.UTF_8)
        if (b.size <= 63) return byteArrayOf(1) + b
        val h = MessageDigest.getInstance("MD5").digest(b)
        hashes[hex(h)] = rel
        return byteArrayOf(2) + h
    }

    /** Caminho do handle; null = desconhecido (STALE). */
    private fun caminho(fh: ByteArray): String? = when {
        fh.isEmpty() -> null
        fh[0].toInt() == 1 -> String(fh, 1, fh.size - 1, Charsets.UTF_8)
        fh[0].toInt() == 2 && fh.size == 17 -> hashes[hex(fh.copyOfRange(1, 17))]
        else -> null
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun arquivo(rel: String): VFile? = tree.resolve(rel)

    private fun abrir(rel: String, f: VFile, escrita: Boolean): VHandle = synchronized(abertos) {
        abertos[rel]?.let { (h, w) ->
            if (w || !escrita) return h
            try { h.close() } catch (_: IOException) {}
            abertos.remove(rel)
        }
        val h = f.open(escrita)
        abertos[rel] = h to escrita
        h
    }

    private fun esquecer(rel: String) = synchronized(abertos) {
        val pre = "$rel/"
        val it = abertos.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == rel || e.key.startsWith(pre)) {
                try { e.value.first.close() } catch (_: IOException) {}
                it.remove()
            }
        }
    }

    // ------------------------------------------------------------------ conexão

    private fun atender(s: Socket) {
        val ip = s.inetAddress.hostAddress
        log.log(t("NFS: $ip conectou", "NFS: $ip connected"))
        try {
            val ent = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            val sai = s.getOutputStream()
            var buf = ByteArray(70 * 1024)
            val ctx = Ctx()
            while (!closed.get()) {
                // Registro RPC (pode vir em fragmentos; a resposta vai num só)
                var n = 0
                while (true) {
                    val marca = try { ent.readInt() } catch (e: java.io.EOFException) { return }
                    val tam = marca and 0x7FFFFFFF
                    if (tam > 4 shl 20) throw IOException("registro RPC grande demais")
                    if (buf.size < n + tam) buf = buf.copyOf(n + tam + 4096)
                    ent.readFully(buf, n, tam)
                    n += tam
                    if (marca < 0) break
                }
                stats.request(ip)
                val resp = chamada(Xdr(buf, n), ctx, ip)
                val tam = resp.pos - 4
                resp.set32(0, tam or Int.MIN_VALUE)
                sai.write(resp.b, 0, resp.pos)
                sai.flush()
            }
        } catch (e: IOException) {
            log.log(t("NFS: $ip desconectou (${e.javaClass.simpleName})", "NFS: $ip disconnected (${e.javaClass.simpleName})"))
        } catch (e: RuntimeException) {
            log.log(t("NFS: erro com $ip: $e", "NFS: error with $ip: $e"))
        } finally {
            clientes.remove(s)
            try { s.close() } catch (_: IOException) {}
        }
    }

    /** Estado de uma COMPOUND: handle atual e salvo. */
    private class Ctx {
        var atual: String? = null
        var salvo: String? = null
        var avisouLeitura = false
    }

    private fun chamada(x: Xdr, ctx: Ctx, ip: String): XdrOut {
        val o = XdrOut(1024)
        o.u32(0) // marca do registro, preenchida no fim
        val xid = x.u32()
        o.u32(xid); o.u32(1) // REPLY
        if (x.u32() != 0) { o.u32(1); o.u32(0); o.u32(2); o.u32(2); return o } // não é CALL: MSG_DENIED/RPC_MISMATCH
        x.u32() // rpcvers
        val prog = x.u32()
        val vers = x.u32()
        val proc = x.u32()
        x.u32(); x.opaque() // credencial (ignorada: uid/gid não importam)
        x.u32(); x.opaque() // verificador
        o.u32(0) // MSG_ACCEPTED
        o.u32(0); o.u32(0) // verificador AUTH_NONE vazio (o cliente lê em posições fixas)
        when {
            prog != 100003 -> { o.u32(1); return o } // PROG_UNAVAIL
            vers != 4 -> { o.u32(2); o.u32(4); o.u32(4); return o } // PROG_MISMATCH
            proc == 0 -> { o.u32(0); return o } // NULL
            proc != 1 -> { o.u32(3); return o } // PROC_UNAVAIL
        }
        o.u32(0) // SUCCESS
        val tag = x.opaque()
        val minor = x.u32()
        val nops = x.u32()
        val statusAt = o.pos
        o.u32(0)
        o.opaque(tag)
        val numAt = o.pos
        o.u32(0)
        if (minor != 0) {
            o.set32(statusAt, NFS4ERR_MINOR_VERS_MISMATCH)
            return o
        }
        var feitos = 0
        var st = 0
        for (i in 0 until nops) {
            val op = x.u32()
            o.u32(op)
            val stAt = o.pos
            o.u32(0)
            st = try {
                operacao(op, x, o, ctx, ip)
            } catch (e: IOException) {
                o.pos = stAt + 4
                NFS4ERR_IO
            }
            o.set32(stAt, st)
            if (st != 0) o.pos = stAt + 4 // erro: só op + status
            feitos++
            if (st != 0) break
        }
        o.set32(statusAt, st)
        o.set32(numAt, feitos)
        return o
    }

    // ------------------------------------------------------------------ operações

    private fun operacao(op: Int, x: Xdr, o: XdrOut, ctx: Ctx, ip: String): Int {
        when (op) {
            PUTROOTFH, PUTPUBFH -> { ctx.atual = ""; return 0 }
            PUTFH -> {
                val rel = caminho(x.opaque()) ?: return NFS4ERR_STALE
                ctx.atual = rel
                return 0
            }
            GETFH -> {
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                o.opaque(handle(rel))
                return 0
            }
            SAVEFH -> { ctx.salvo = ctx.atual ?: return NFS4ERR_NOFILEHANDLE; return 0 }
            RESTOREFH -> { ctx.atual = ctx.salvo ?: return NFS4ERR_RESTOREFH; return 0 }
            LOOKUP -> {
                val nome = x.texto()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                val dir = arquivo(rel) ?: return NFS4ERR_STALE
                if (!dir.isDirectory) return NFS4ERR_NOTDIR
                if (nome.isEmpty() || nome == "." || nome == ".." || '/' in nome) return NFS4ERR_INVAL
                val filho = achar(dir, nome)
                if (filho == null) {
                    // "export" com nome (ex.: /ps2, /PS2SMB): aponta para a própria raiz
                    if (rel.isEmpty() && nome.lowercase() in APELIDOS) return 0
                    return NFS4ERR_NOENT
                }
                ctx.atual = junta(rel, filho.name)
                return 0
            }
            LOOKUPP -> {
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                if (rel.isEmpty()) return NFS4ERR_NOENT
                ctx.atual = rel.substringBeforeLast('/', "")
                return 0
            }
            GETATTR -> {
                x.bitmap()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                val f = arquivo(rel)?.takeIf { it.exists() } ?: return NFS4ERR_STALE
                atributos(o, f)
                return 0
            }
            ACCESS -> {
                val pedido = x.u32()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                arquivo(rel)?.takeIf { it.exists() } ?: return NFS4ERR_STALE
                val ok = if (tree.readOnly) pedido and (0x1 or 0x2 or 0x20) else pedido
                o.u32(pedido); o.u32(ok)
                return 0
            }
            READ -> {
                x.pula(16) // stateid (anônimo)
                val pos = x.u64()
                val qtd = minOf(x.u32().toLong() and 0xFFFFFFFFL, 1L shl 20).toInt()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                val f = arquivo(rel) ?: return NFS4ERR_STALE
                if (f.isDirectory) return NFS4ERR_ISDIR
                if (!f.exists()) return NFS4ERR_STALE
                val h = abrir(rel, f, false)
                val eofAt = o.pos
                o.u32(0)
                val lenAt = o.pos
                o.u32(0)
                o.need(qtd + 4)
                val n = try {
                    h.readFully(pos, o.b, o.pos, qtd)
                } catch (e: IOException) {
                    // Pendrive/SAF oscilou: mais uma chance
                    Thread.sleep(150)
                    try { h.readFully(pos, o.b, o.pos, qtd) } catch (e2: IOException) {
                        if (!ctx.avisouLeitura) { ctx.avisouLeitura = true; log.log(t("NFS: erro de leitura: ${e2.message}", "NFS: read error: ${e2.message}")) }
                        esquecer(rel)
                        throw e2
                    }
                }.coerceAtLeast(0)
                o.pos += n
                o.pad(n)
                o.set32(lenAt, n)
                o.set32(eofAt, if (n < qtd || pos + n >= h.length()) 1 else 0)
                stats.bytesRead.addAndGet(n.toLong())
                return 0
            }
            WRITE -> {
                x.pula(16)
                val pos = x.u64()
                x.u32() // stable (gravamos direto)
                val (ini, n) = x.opaqueRef()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                if (tree.readOnly) return NFS4ERR_ROFS
                val f = arquivo(rel)?.takeIf { it.exists() } ?: return NFS4ERR_STALE
                if (f.isDirectory) return NFS4ERR_ISDIR
                val h = try { abrir(rel, f, true) } catch (e: IOException) { return NFS4ERR_ACCESS }
                h.write(pos, x.b, ini, n)
                stats.bytesWritten.addAndGet(n.toLong())
                o.u32(n); o.u32(2); o.bytes(verificador)
                return 0
            }
            COMMIT -> {
                x.u64(); x.u32()
                o.bytes(verificador)
                return 0
            }
            READDIR -> return readdir(x, o, ctx)
            CREATE -> {
                val tipo = x.u32()
                if (tipo == 5) x.opaque() // NF4LNK: linkdata
                if (tipo == 3 || tipo == 4) { x.u32(); x.u32() } // dispositivos
                val nome = x.texto()
                pulaFattr(x)
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                if (tree.readOnly) return NFS4ERR_ROFS
                if (tipo != 2) return NFS4ERR_NOTSUPP
                val dir = arquivo(rel)?.takeIf { it.isDirectory } ?: return NFS4ERR_NOTDIR
                if (achar(dir, nome) != null) return NFS4ERR_EXIST
                if (!dir.child(nome).mkdir()) return NFS4ERR_ACCESS
                mudanca(o)
                o.u32(0) // attrset vazio
                ctx.atual = junta(rel, nome)
                return 0
            }
            REMOVE -> {
                val nome = x.texto()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                if (tree.readOnly) return NFS4ERR_ROFS
                val dir = arquivo(rel)?.takeIf { it.isDirectory } ?: return NFS4ERR_NOTDIR
                val f = achar(dir, nome) ?: return NFS4ERR_NOENT
                if (f.isDirectory && f.listFiles()?.isNotEmpty() == true) return NFS4ERR_NOTEMPTY
                esquecer(junta(rel, f.name))
                if (!f.delete()) return NFS4ERR_ACCESS
                mudanca(o)
                return 0
            }
            RENAME -> {
                val velho = x.texto()
                val novo = x.texto()
                val relOrig = ctx.salvo ?: return NFS4ERR_NOFILEHANDLE
                val relDest = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                if (tree.readOnly) return NFS4ERR_ROFS
                val dOrig = arquivo(relOrig)?.takeIf { it.isDirectory } ?: return NFS4ERR_NOTDIR
                val dDest = arquivo(relDest)?.takeIf { it.isDirectory } ?: return NFS4ERR_NOTDIR
                val f = achar(dOrig, velho) ?: return NFS4ERR_NOENT
                esquecer(junta(relOrig, f.name))
                // O cliente espera que um destino existente seja substituído
                achar(dDest, novo)?.let { alvo ->
                    if (alvo == f) return 0.also { mudanca(o); mudanca(o) }
                    if (alvo.isDirectory && alvo.listFiles()?.isNotEmpty() == true) return NFS4ERR_EXIST
                    esquecer(junta(relDest, alvo.name))
                    if (!alvo.delete()) return NFS4ERR_ACCESS
                }
                if (!f.renameTo(dDest.child(novo))) return NFS4ERR_ACCESS
                mudanca(o); mudanca(o)
                return 0
            }
            SETATTR -> {
                x.pula(16)
                val bm = x.bitmap()
                val (ini, _) = x.opaqueRef()
                val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
                val f = arquivo(rel)?.takeIf { it.exists() } ?: return NFS4ERR_STALE
                val querTamanho = bm.isNotEmpty() && bm[0] and 0x10 != 0
                if (querTamanho) {
                    if (tree.readOnly) return NFS4ERR_ROFS
                    if (f.isDirectory) return NFS4ERR_ISDIR
                    // SIZE (atributo 4) é o primeiro dos graváveis: vem no começo da lista
                    val tam = Xdr(x.b, x.b.size).also { it.p = ini }.u64()
                    abrir(rel, f, true).setLength(tam)
                    o.u32(1); o.u32(0x10)
                } else {
                    o.u32(0)
                }
                return 0
            }
            SETCLIENTID -> {
                x.pula(8); x.opaque(); x.u32(); x.opaque(); x.opaque(); x.u32()
                o.u64(proxCliente++); o.bytes(verificador)
                return 0
            }
            SETCLIENTID_CONFIRM -> { x.u64(); x.pula(8); return 0 }
            RENEW -> { x.u64(); return 0 }
            OPEN -> return abrirOp(x, o, ctx)
            OPEN_CONFIRM -> {
                val sid = x.raw(16)
                x.u32()
                sid[3] = (sid[3] + 1).toByte()
                o.bytes(sid)
                return 0
            }
            CLOSE -> {
                x.u32()
                val sid = x.raw(16)
                ctx.atual?.let { esquecer(it) }
                o.bytes(sid)
                return 0
            }
            else -> return NFS4ERR_OP_ILLEGAL
        }
    }

    private fun abrirOp(x: Xdr, o: XdrOut, ctx: Ctx): Int {
        x.u32() // seqid
        x.u32(); x.u32() // share_access, share_deny
        x.u64(); x.opaque() // dono
        val cria = x.u32() == 1
        var modo = -1
        if (cria) {
            modo = x.u32()
            if (modo == 2) x.pula(8) else pulaFattr(x)
        }
        val claim = x.u32()
        if (claim != 0) return NFS4ERR_NOTSUPP
        val nome = x.texto()
        val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
        val dir = arquivo(rel)?.takeIf { it.isDirectory } ?: return NFS4ERR_NOTDIR
        val existe = achar(dir, nome)
        val f: VFile
        if (existe != null) {
            if (cria && modo != 0) return NFS4ERR_EXIST
            if (existe.isDirectory) return NFS4ERR_ISDIR
            f = existe
        } else {
            if (!cria) return NFS4ERR_NOENT
            if (tree.readOnly) return NFS4ERR_ROFS
            f = dir.child(nome)
            try { f.open(true).close() } catch (e: IOException) { return NFS4ERR_ACCESS }
        }
        // stateid simples (o cliente usa o anônimo para ler e gravar)
        o.u32(1); o.u32(0x50533220); o.u64(System.nanoTime())
        mudanca(o)
        o.u32(4) // rflags: LOCKTYPE_POSIX, sem CONFIRM
        o.u32(0) // attrset vazio
        o.u32(0) // OPEN_DELEGATE_NONE
        ctx.atual = junta(rel, f.name)
        return 0
    }

    private fun readdir(x: Xdr, o: XdrOut, ctx: Ctx): Int {
        val cookie = x.u64()
        x.pula(8) // cookieverf
        x.u32() // dircount
        val maxcount = x.u32().toLong() and 0xFFFFFFFFL
        x.bitmap()
        val rel = ctx.atual ?: return NFS4ERR_NOFILEHANDLE
        val dir = arquivo(rel) ?: return NFS4ERR_STALE
        if (!dir.isDirectory) return NFS4ERR_NOTDIR
        // Ordem estável (o cookie é a posição + 3: 0, 1 e 2 são reservados)
        val itens = (dir.listFiles() ?: emptyList()).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        var i = if (cookie == 0L) 0 else (cookie - 2).toInt()
        if (i < 0 || i > itens.size) return NFS4ERR_BAD_COOKIE
        o.bytes(ByteArray(8)) // cookieverf fixo
        val orcamento = (minOf(maxcount, 64L * 1024) - 8).toInt() // a lista + terminador (8 bytes)
        val ini = o.pos
        var algum = false
        while (i < itens.size) {
            val f = itens[i]
            val volta = o.pos
            o.u32(1)
            o.u64(i + 3L)
            o.texto(f.name)
            atributos(o, f)
            if (o.pos - ini + 8 > orcamento) {
                o.pos = volta
                if (!algum) return NFS4ERR_TOOSMALL
                break
            }
            algum = true
            i++
        }
        o.u32(0)
        o.u32(if (i >= itens.size) 1 else 0)
        return 0
    }

    /** {TYPE, SIZE, TIME_MODIFY}, sempre nessa ordem (o cliente lê em posições fixas). */
    private fun atributos(o: XdrOut, f: VFile) {
        val dir = f.isDirectory
        o.u32(2); o.u32(0x12); o.u32(0x00200000)
        o.u32(24)
        o.u32(if (dir) 2 else 1)
        o.u64(if (dir) 4096L else f.length())
        val ms = f.lastModified()
        o.u64(ms / 1000)
        o.u32(((ms % 1000) * 1_000_000).toInt())
    }

    private fun mudanca(o: XdrOut) {
        o.u32(0); o.u64(0); o.u64(System.currentTimeMillis())
    }

    private fun pulaFattr(x: Xdr) {
        x.bitmap()
        x.opaque()
    }

    private fun achar(dir: VFile, nome: String): VFile? {
        val exato = dir.child(nome)
        if (exato.exists()) return exato
        return dir.listFiles()?.firstOrNull { it.name.equals(nome, true) }
    }

    private fun junta(rel: String, nome: String) = if (rel.isEmpty()) nome else "$rel/$nome"

    companion object {
        const val PORT = 2049
        private val APELIDOS = setOf("ps2", "ps2smb", "share", "games", "export", "nfs")

        const val ACCESS = 3; const val CLOSE = 4; const val COMMIT = 5; const val CREATE = 6
        const val GETATTR = 9; const val GETFH = 10; const val LOOKUP = 15; const val LOOKUPP = 16
        const val OPEN = 18; const val OPEN_CONFIRM = 20; const val PUTFH = 22; const val PUTPUBFH = 23
        const val PUTROOTFH = 24; const val READ = 25; const val READDIR = 26; const val REMOVE = 28
        const val RENAME = 29; const val RENEW = 30; const val RESTOREFH = 31; const val SAVEFH = 32
        const val SETATTR = 34; const val SETCLIENTID = 35; const val SETCLIENTID_CONFIRM = 36; const val WRITE = 38

        const val NFS4ERR_NOENT = 2; const val NFS4ERR_IO = 5; const val NFS4ERR_ACCESS = 13
        const val NFS4ERR_EXIST = 17; const val NFS4ERR_NOTDIR = 20; const val NFS4ERR_ISDIR = 21
        const val NFS4ERR_INVAL = 22; const val NFS4ERR_ROFS = 30; const val NFS4ERR_NOTEMPTY = 66
        const val NFS4ERR_STALE = 70; const val NFS4ERR_NOTSUPP = 10004; const val NFS4ERR_TOOSMALL = 10005
        const val NFS4ERR_BAD_COOKIE = 10003; const val NFS4ERR_NOFILEHANDLE = 10020
        const val NFS4ERR_MINOR_VERS_MISMATCH = 10021; const val NFS4ERR_RESTOREFH = 10030
        const val NFS4ERR_OP_ILLEGAL = 10044
    }
}

/** Leitura XDR (big-endian, alinhada a 4) sobre um buffer. */
internal class Xdr(val b: ByteArray, private val fim: Int) {
    var p = 0

    private fun checa(n: Int) { if (n < 0 || p + n > fim) throw IOException("XDR curto") }

    fun u32(): Int {
        checa(4)
        val v = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
        p += 4
        return v
    }

    fun u64(): Long = (u32().toLong() shl 32) or (u32().toLong() and 0xFFFFFFFFL)

    fun pula(n: Int) { checa(n); p += n }

    fun raw(n: Int): ByteArray { checa(n); return b.copyOfRange(p, p + n).also { p += n } }

    /** (início, tamanho) de um opaque, sem copiar. */
    fun opaqueRef(): Pair<Int, Int> {
        val n = u32()
        checa(n)
        val ini = p
        p += (n + 3) and 3.inv()
        if (p > fim) p = fim
        return ini to n
    }

    fun opaque(): ByteArray { val (i, n) = opaqueRef(); return b.copyOfRange(i, i + n) }

    fun texto(): String { val (i, n) = opaqueRef(); return String(b, i, n, Charsets.UTF_8) }

    fun bitmap(): IntArray { val n = u32(); if (n > 8) throw IOException("bitmap grande"); return IntArray(n) { u32() } }
}

/** Escrita XDR que cresce sob demanda. */
internal class XdrOut(tam: Int) {
    var b = ByteArray(tam)
    var pos = 0

    fun need(n: Int) { if (pos + n > b.size) b = b.copyOf(maxOf(b.size * 2, pos + n + 1024)) }

    fun u32(v: Int) {
        need(4)
        b[pos] = (v ushr 24).toByte(); b[pos + 1] = (v ushr 16).toByte(); b[pos + 2] = (v ushr 8).toByte(); b[pos + 3] = v.toByte()
        pos += 4
    }

    fun u64(v: Long) { u32((v ushr 32).toInt()); u32(v.toInt()) }

    fun set32(at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    fun bytes(d: ByteArray) { need(d.size); System.arraycopy(d, 0, b, pos, d.size); pos += d.size }

    fun pad(n: Int) { val r = (4 - (n and 3)) and 3; need(r); repeat(r) { b[pos++] = 0 } }

    fun opaque(d: ByteArray) { u32(d.size); bytes(d); pad(d.size) }

    fun texto(s: String) = opaque(s.toByteArray(Charsets.UTF_8))
}
