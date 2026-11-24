package br.ps2ponte.core.smb

import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import br.ps2ponte.core.t
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom

/**
 * SMB2 (dialetos 2.0.2 e 2.1) na mesma porta do SMB1: a conexão vira SMB2 quando a primeira mensagem
 * começa com 0xFE 'SMB'. Feito para o usmb2/libsmb2 do RiptOPL e do Neutrino (e serve para PCs também).
 *
 * - Login NTLMSSP (cru ou dentro de SPNEGO) aceitando qualquer usuário e senha, como convidado e sem
 *   assinatura (o PS2 não tem a senha para derivar chave).
 * - Pedidos encadeados (NextCommand / "related") são atendidos e respondidos encadeados.
 * - Um único compartilhamento: qualquer nome (menos IPC$) é a pasta servida.
 */
internal class Smb2Connection(
    private val srv: SmbServer,
    private val sock: Socket,
    private val ip: String,
    private val inp: DataInputStream,
    private val outs: OutputStream,
) {
    private class Aberto(
        var file: VFile, val handle: VHandle?, val isDir: Boolean, val writable: Boolean, var deleteOnClose: Boolean,
    ) {
        var busca: List<DirEntry>? = null
        var pos = 0
    }

    private val paths = srv.paths
    private val req = Msg(ByteArray(70 * 1024))
    private val out = Out(70 * 1024)
    private val rnd = SecureRandom()
    private val desafio = ByteArray(8).also { rnd.nextBytes(it) }
    private val serverGuid = ByteArray(16).also { rnd.nextBytes(it) }

    private var dialeto = 0x0202
    private var maxIo = 65536
    private var sessao = 0L
    private var logado = false
    private val arvores = HashMap<Int, Boolean>() // treeId -> é IPC$
    private var proxArvore = 1
    private val abertos = HashMap<Long, Aberto>()
    private var proxId = 1L
    private var avisouLeitura = false

    /** Atende a conexão a partir da primeira mensagem (já lida em [primeira]). */
    fun rodar(primeira: ByteArray, len: Int) {
        try {
            if (req.b.size < len) req.b = ByteArray(len + 512)
            System.arraycopy(primeira, 0, req.b, 0, len)
            req.len = len
            if (!mensagem()) return
            while (true) {
                val tipo = inp.read()
                if (tipo < 0) return
                val n = (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
                if (tipo != 0) { inp.skipBytes(n); continue }
                if (req.b.size < n) req.b = ByteArray(n + 512)
                inp.readFully(req.b, 0, n)
                req.len = n
                if (!mensagem()) return
            }
        } catch (e: IOException) {
            srv.log(t("SMB2: $ip desconectou (${e.javaClass.simpleName})", "SMB2: $ip disconnected (${e.javaClass.simpleName})"))
        } catch (e: RuntimeException) {
            srv.log(t("SMB2: erro com $ip: $e", "SMB2: error with $ip: $e"))
        } finally {
            for (a in abertos.values) fechar(a)
            abertos.clear()
            try { sock.close() } catch (_: IOException) {}
        }
    }

    // ------------------------------------------------------------------ mensagens

    /** Uma mensagem (talvez com vários pedidos encadeados). false = fechar. */
    private fun mensagem(): Boolean {
        val b = req.b
        if (req.len < 64 || b[0] != 0xFE.toByte() || b[1] != 'S'.code.toByte() || b[2] != 'M'.code.toByte() || b[3] != 'B'.code.toByte()) return false
        srv.statsRef.request(ip)
        out.pos = 4 // espaço do cabeçalho de 4 bytes do transporte
        var off = 0
        var ultimoId = -1L
        var anterior = -1
        while (true) {
            if (off + 64 > req.len) break
            val cmd = req.u16(off + 12)
            val flags = req.i32(off + 16)
            val proximo = req.i32(off + 20)
            if (cmd == CANCEL) { // não tem resposta
                if (proximo <= 0) break
                off += proximo
                continue
            }
            if (anterior >= 0) {
                out.align(4, 8)
                out.set32(anterior + 20, out.pos - anterior) // NextCommand da resposta anterior
            }
            val r0 = out.pos
            val relacionado = flags and 0x4 != 0
            val creditos = maxOf(1, req.u16(off + 18))
            // Cabeçalho da resposta (status e créditos preenchidos depois)
            out.u8(0xFE); out.u8('S'.code); out.u8('M'.code); out.u8('B'.code)
            out.u16(64); out.u16(req.u16(off + 6)); out.u32(0)
            out.u16(cmd); out.u16(minOf(creditos, 256))
            out.u32(1 or (flags and 0x4)) // SERVER_TO_REDIR (+ RELATED)
            out.u32(0)
            out.bytes(b, off + 24, 8) // MessageId
            out.u32(0)
            val tree = if (relacionado && req.i32(off + 36) == -1) ultimoTree else req.i32(off + 36)
            out.u32(tree)
            out.u64(if (cmd == NEGOTIATE) 0L else sessao)
            out.zeros(16)
            ultimoTree = tree
            val corpo = off + 64
            val fim = if (proximo > 0) off + proximo else req.len
            relId = if (relacionado) ultimoId else -1L
            val st = try {
                executar(cmd, corpo, fim, tree)
            } catch (e: Malformed) {
                St.INVALID_PARAMETER
            }
            if (st != St.SUCCESS && st != MORE_PROCESSING) {
                // Corpo de erro padrão (descarta o que o comando tenha começado a escrever)
                out.pos = r0 + 64
                out.u16(9); out.u8(0); out.u8(0); out.u32(0); out.u8(0)
            }
            out.set32(r0 + 8, st)
            if (cmd == SESSION_SETUP) {
                out.set32(r0 + 40, sessao.toInt())
                out.set32(r0 + 44, (sessao ushr 32).toInt())
            }
            ultimoId = criado ?: relId
            criado = null
            anterior = r0
            if (proximo <= 0) break
            off += proximo
        }
        if (out.pos == 4) return true
        val n = out.pos - 4
        out.set8(0, 0); out.set8(1, n ushr 16); out.set8(2, n ushr 8); out.set8(3, n)
        outs.write(out.b, 0, out.pos)
        outs.flush()
        return true
    }

    private var ultimoTree = 0
    private var relId = -1L
    private var criado: Long? = null


    private fun executar(cmd: Int, c: Int, fim: Int, tree: Int): Int {
        if (cmd != NEGOTIATE && cmd != SESSION_SETUP && cmd != ECHO && !logado) return USER_SESSION_DELETED
        return when (cmd) {
            NEGOTIATE -> negociar(c)
            SESSION_SETUP -> login(c)
            LOGOFF -> { logado = false; out.u16(4); out.u16(0); St.SUCCESS }
            TREE_CONNECT -> conectar(c)
            TREE_DISCONNECT -> { arvores.remove(tree); out.u16(4); out.u16(0); St.SUCCESS }
            ECHO, FLUSH, LOCK -> { out.u16(4); out.u16(0); St.SUCCESS }
            else -> {
                if (!arvores.containsKey(tree)) return NETWORK_NAME_DELETED
                if (arvores[tree] == true && cmd != CLOSE) return St.ACCESS_DENIED // IPC$: nada a fazer aqui
                when (cmd) {
                    CREATE -> criar(c)
                    CLOSE -> fecharPedido(c)
                    READ -> ler(c)
                    WRITE -> escrever(c)
                    QUERY_DIRECTORY -> listar(c)
                    QUERY_INFO -> info(c)
                    SET_INFO -> definir(c)
                    else -> St.NOT_SUPPORTED
                }
            }
        }
    }

    private fun idArquivo(o: Int): Long {
        val v = req.u64(o + 8)
        return if (v == -1L && relId >= 0) relId else v
    }

    // ------------------------------------------------------------------ negociação e login

    private fun negociar(c: Int): Int {
        val n = req.u16(c + 2)
        val caps = req.i32(c + 8)
        val oferecidos = (0 until n).map { req.u16(c + 36 + it * 2) }
        dialeto = when {
            0x0210 in oferecidos -> 0x0210
            0x0202 in oferecidos -> 0x0202
            else -> return St.NOT_SUPPORTED
        }
        // 2.1 com LARGE_MTU: leituras/gravações de até 1 MiB (menos idas e voltas no cabo)
        val grande = dialeto == 0x0210 && caps and 0x4 != 0
        maxIo = if (grande) 1 shl 20 else 65536
        srv.log(t("SMB2: $ip negociou o dialeto ${if (dialeto == 0x0210) "2.1" else "2.0.2"}", "SMB2: $ip negotiated dialect ${if (dialeto == 0x0210) "2.1" else "2.0.2"}"))
        val blob = negTokenInit()
        out.u16(65)
        out.u16(1) // assinatura habilitada, não exigida
        out.u16(dialeto)
        out.u16(0)
        out.bytes(serverGuid)
        out.u32(if (grande) 0x4 else 0)
        out.u32(maxIo); out.u32(maxIo); out.u32(maxIo)
        out.u64(fileTime(System.currentTimeMillis()))
        out.u64(0)
        out.u16(64 + 64); out.u16(blob.size)
        out.u32(0)
        out.bytes(blob)
        return St.SUCCESS
    }

    private fun login(c: Int): Int {
        val bOff = req.u16(c + 12)
        val bLen = req.u16(c + 14)
        val ini = (c - 64) + bOff
        if (ini < 0 || ini + bLen > req.len) throw Malformed()
        val blob = req.b.copyOfRange(ini, ini + bLen)
        val ntlm = indice(blob, NTLMSSP)
        val spnego = bLen > 0 && (blob[0] == 0x60.toByte() || blob[0] == 0xA1.toByte())
        if (sessao == 0L) sessao = (rnd.nextLong() and 0x7FFFFFFFFFFFL) or 0x1000L
        val tipo = if (ntlm >= 0 && ntlm + 12 <= blob.size) le32(blob, ntlm + 8) else 0
        out.u16(9)
        return when (tipo) {
            1 -> {
                val ch = desafioNtlm(le32(blob, ntlm + 12))
                val resp = if (spnego) negTokenResp(1, ch, true) else ch
                out.u16(0); out.u16(64 + 8); out.u16(resp.size); out.bytes(resp)
                MORE_PROCESSING
            }
            3 -> {
                val usuario = campo(blob, ntlm, 36)
                logado = true
                srv.log(t("SMB2: $ip entrou como ${usuario.ifEmpty { "convidado" }}", "SMB2: $ip logged in as ${usuario.ifEmpty { "guest" }}"))
                val resp = if (spnego) negTokenResp(0, null, false) else ByteArray(0)
                out.u16(1) // convidado: sem assinatura
                out.u16(if (resp.isEmpty()) 0 else 64 + 8); out.u16(resp.size); out.bytes(resp)
                St.SUCCESS
            }
            else -> {
                // Sem NTLMSSP (ex.: SPNEGO pedindo outro mecanismo): manda o desafio mesmo assim
                val ch = desafioNtlm(0)
                val resp = if (spnego) negTokenResp(1, ch, true) else ch
                out.u16(0); out.u16(64 + 8); out.u16(resp.size); out.bytes(resp)
                MORE_PROCESSING
            }
        }
    }

    /** NTLMSSP CHALLENGE com nome e TargetInfo (o NTLMv2 do cliente usa o TargetInfo no cálculo). */
    private fun desafioNtlm(flagsCliente: Int): ByteArray {
        val nome = "POCKETPS2".toByteArray(Charsets.UTF_16LE)
        val ti = Out(128)
        fun av(id: Int, v: ByteArray) { ti.u16(id); ti.u16(v.size); ti.bytes(v) }
        av(2, nome); av(1, nome); av(4, nome); av(3, nome)
        val ts = Out(8).also { it.u64(fileTime(System.currentTimeMillis())) }
        av(7, ts.b.copyOf(8))
        av(0, ByteArray(0))
        val targetInfo = ti.b.copyOf(ti.pos)
        var flags = 0x1 or 0x4 or 0x200 or 0x8000 or 0x20000 or 0x800000 or 0x2000000
        flags = flags or (flagsCliente and (0x80000 or 0x20000000 or 0x40000000 or 0x80000000.toInt() or 0x10 or 0x20))
        val o = Out(64 + nome.size + targetInfo.size)
        o.bytes(NTLMSSP); o.u32(2)
        o.u16(nome.size); o.u16(nome.size); o.u32(56)
        o.u32(flags)
        o.bytes(desafio)
        o.zeros(8)
        o.u16(targetInfo.size); o.u16(targetInfo.size); o.u32(56 + nome.size)
        o.u8(10); o.u8(0); o.u16(19041); o.zeros(3); o.u8(15) // versão (10.0.19041, NTLM rev 15)
        o.bytes(nome)
        o.bytes(targetInfo)
        return o.b.copyOf(o.pos)
    }

    /** Campo NTLMSSP (comprimento u16, máx u16, deslocamento u32) em UTF-16. */
    private fun campo(blob: ByteArray, base: Int, at: Int): String {
        if (base + at + 8 > blob.size) return ""
        val len = (blob[base + at].toInt() and 0xFF) or ((blob[base + at + 1].toInt() and 0xFF) shl 8)
        val o = base + le32(blob, base + at + 4)
        if (len <= 0 || o < 0 || o + len > blob.size) return ""
        return String(blob, o, len, Charsets.UTF_16LE)
    }

    // ------------------------------------------------------------------ árvore

    private fun conectar(c: Int): Int {
        val pOff = req.u16(c + 4)
        val pLen = req.u16(c + 6)
        val ini = (c - 64) + pOff
        val caminho = if (ini >= 0 && ini + pLen <= req.len) String(req.b, ini, pLen, Charsets.UTF_16LE) else ""
        val share = caminho.substringAfterLast('\\')
        val ipc = share.equals("IPC$", true)
        val id = proxArvore++
        arvores[id] = ipc
        out.set32(out.pos - 64 + 36, id) // TreeId no cabeçalho desta resposta
        ultimoTree = id
        out.u16(16)
        out.u8(if (ipc) 2 else 1)
        out.u8(0)
        out.u32(0)   // ShareFlags: sem cache offline
        out.u32(0)
        out.u32(if (paths.readOnly) 0x001200A9 else 0x001F01FF)
        if (!ipc) srv.log(t("SMB2: $ip abriu o compartilhamento \"$share\"", "SMB2: $ip opened share \"$share\""))
        return St.SUCCESS
    }

    // ------------------------------------------------------------------ arquivos

    private fun nome(c: Int, offAt: Int, lenAt: Int): String {
        val o = req.u16(c + offAt)
        val n = req.u16(c + lenAt)
        if (n == 0) return ""
        val ini = (c - 64) + o
        if (ini < 0 || ini + n > req.len) throw Malformed()
        return String(req.b, ini, n, Charsets.UTF_16LE)
    }

    private fun criar(c: Int): Int {
        val acesso = req.i32(c + 24)
        val disp = req.i32(c + 36)
        val opcoes = req.i32(c + 40)
        val caminho = nome(c, 44, 46).trimStart('\\')
        val f = paths.resolve(caminho) ?: return St.OBJECT_NAME_INVALID
        val existe = f.exists()
        val querPasta = opcoes and 0x1 != 0
        val naoPasta = opcoes and 0x40 != 0
        if (existe && f.isDirectory && naoPasta) return St.FILE_IS_A_DIRECTORY
        if (existe && !f.isDirectory && querPasta) return St.NOT_A_DIRECTORY
        val cria = !existe && disp in listOf(0, 2, 3, 5)
        if (!existe && !cria) return if (f.parentFile?.exists() == false) St.OBJECT_PATH_NOT_FOUND else St.OBJECT_NAME_NOT_FOUND
        if (existe && disp == 2) return St.OBJECT_NAME_COLLISION
        val trunca = existe && !f.isDirectory && disp in listOf(0, 4, 5)
        // Só os bits de gravar dados pedem o arquivo aberto para escrita (DELETE sozinho, não: é o "unlink" do menu)
        val quer = acesso and (0x2 or 0x4 or 0x40000000 or 0x10000000) != 0
        val apagar = opcoes and 0x1000 != 0
        if ((cria || trunca || quer || apagar) && paths.readOnly) return St.ACCESS_DENIED
        if (cria && f.parentFile?.exists() == false) return St.OBJECT_PATH_NOT_FOUND
        // rmdir do menu: pasta com coisas dentro falha já aqui (o menu confere de novo depois do CLOSE)
        if (apagar && existe && f.isDirectory && f.listFiles()?.isNotEmpty() == true) return St.DIRECTORY_NOT_EMPTY
        val acao: Int
        var handle: VHandle? = null
        var writable = false
        if (cria && querPasta) {
            if (!f.mkdir()) return St.ACCESS_DENIED
            acao = 2
        } else if (!cria && !trunca && acesso and DADOS == 0) {
            // Só atributos (stat, rename, unlink do menu): não abre o arquivo (no SAF, abrir custa uma chamada)
            acao = 1
        } else if (!(existe && f.isDirectory)) {
            writable = (quer || cria || trunca) && !paths.readOnly
            handle = try {
                f.open(writable)
            } catch (e: IOException) {
                if (cria || trunca) return St.ACCESS_DENIED
                writable = false
                try { f.open(false) } catch (e2: IOException) { return St.OBJECT_NAME_NOT_FOUND }
            }
            if (trunca) try { handle.setLength(0) } catch (e: IOException) { handle.close(); return St.ACCESS_DENIED }
            acao = when { cria -> 2; trunca -> if (disp == 0) 0 else 3; else -> 1 }
        } else acao = 1
        val dir = f.isDirectory
        val a = Aberto(f, handle, dir, writable, apagar)
        val id = proxId++
        abertos[id] = a
        criado = id
        out.u16(89)
        out.u8(0); out.u8(0)
        out.u32(acao)
        tempos(f)
        val tam = if (dir) 0L else handle?.length() ?: f.length()
        out.u64(allocOf(tam)); out.u64(tam)
        out.u32(attrsOf(dir, paths.readOnly))
        out.u32(0)
        out.u64(id); out.u64(id)
        out.u32(0); out.u32(0)
        return St.SUCCESS
    }

    private fun tempos(f: VFile) {
        val t = fileTime(f.lastModified())
        out.u64(t); out.u64(t); out.u64(t); out.u64(t)
    }

    private fun fecharPedido(c: Int): Int {
        val id = idArquivo(c + 8)
        val a = abertos.remove(id) ?: return FILE_CLOSED
        val pos = req.u16(c + 2) and 1 != 0
        val tam = if (a.isDir) 0L else try { a.handle?.length() ?: a.file.length() } catch (e: IOException) { 0L }
        fechar(a)
        out.u16(60)
        out.u16(if (pos) 1 else 0)
        out.u32(0)
        if (pos) {
            tempos(a.file)
            out.u64(allocOf(tam)); out.u64(tam)
            out.u32(attrsOf(a.isDir, paths.readOnly))
        } else {
            out.zeros(32 + 16 + 4)
        }
        return St.SUCCESS
    }

    private fun fechar(a: Aberto) {
        try { a.handle?.close() } catch (_: IOException) {}
        if (a.deleteOnClose && a.file != paths.root) a.file.delete()
    }

    private fun ler(c: Int): Int {
        val len = minOf(req.i32(c + 4).toLong() and 0xFFFFFFFFL, maxIo.toLong()).toInt()
        val pos = req.u64(c + 8)
        val a = abertos[idArquivo(c + 16)] ?: return FILE_CLOSED
        val h = a.handle ?: return St.INVALID_DEVICE_REQUEST
        val dados = out.pos + 16
        out.need(16 + len)
        val n = try {
            h.readFully(pos, out.b, dados, len)
        } catch (e: IOException) {
            // Pendrive/SAF oscilou: mais uma chance antes de devolver erro ao PS2
            try { Thread.sleep(150); h.readFully(pos, out.b, dados, len) } catch (e2: IOException) {
                if (!avisouLeitura) { avisouLeitura = true; srv.log(t("SMB2: erro de leitura: ${e2.message}", "SMB2: read error: ${e2.message}")) }
                return St.UNEXPECTED_IO_ERROR
            }
        }
        if (n <= 0 && len > 0) return END_OF_FILE
        out.u16(17); out.u8(0x50); out.u8(0)
        out.u32(n); out.u32(0); out.u32(0)
        out.pos += n
        srv.statsRef.bytesRead.addAndGet(n.toLong())
        return St.SUCCESS
    }

    private fun escrever(c: Int): Int {
        val dOff = req.u16(c + 2)
        val len = req.i32(c + 4)
        val pos = req.u64(c + 8)
        val a = abertos[idArquivo(c + 16)] ?: return FILE_CLOSED
        val h = a.handle ?: return St.INVALID_DEVICE_REQUEST
        if (!a.writable) return St.ACCESS_DENIED
        val ini = (c - 64) + dOff
        if (len < 0 || ini < 0 || ini + len > req.len) throw Malformed()
        try {
            h.write(pos, req.b, ini, len)
        } catch (e: IOException) {
            return St.DISK_FULL
        }
        srv.statsRef.bytesWritten.addAndGet(len.toLong())
        out.u16(17); out.u16(0); out.u32(len); out.u32(0); out.u16(0); out.u16(0)
        return St.SUCCESS
    }

    private fun listar(c: Int): Int {
        val classe = req.u8(c + 2)
        val flags = req.u8(c + 3)
        val a = abertos[idArquivo(c + 8)] ?: return FILE_CLOSED
        if (!a.isDir) return St.INVALID_PARAMETER
        val padrao = nome(c, 24, 26).ifEmpty { "*" }
        val max = minOf(req.i32(c + 28).toLong() and 0xFFFFFFFFL, maxIo.toLong()).toInt()
        if (a.busca == null || flags and (0x1 or 0x10) != 0) {
            a.busca = listDir(a.file, padrao, includeDirs = true)
            a.pos = 0
        }
        val lista = a.busca!!
        if (a.pos >= lista.size) return St.NO_MORE_FILES
        out.u16(9); out.u16(64 + 8)
        val tamAt = out.pos
        out.u32(0)
        val ini = out.pos
        var ultimo = -1
        while (a.pos < lista.size) {
            val e = lista[a.pos]
            val entrada = out.pos
            if (ultimo >= 0) {
                // Alinha a 8 e só então decide se cabe
                while ((out.pos - ini) % 8 != 0) out.u8(0)
            }
            val comeco = out.pos
            val nomeB = e.name.toByteArray(Charsets.UTF_16LE)
            entradaDir(classe, e, nomeB) ?: return St.INVALID_LEVEL
            if (out.pos - ini > max) {
                out.pos = if (ultimo >= 0) entrada else comeco
                if (ultimo < 0) return St.BUFFER_TOO_SMALL
                break
            }
            if (ultimo >= 0) out.set32(ultimo, comeco - ultimo)
            ultimo = comeco
            a.pos++
            if (flags and 0x2 != 0) break
        }
        out.set32(tamAt, out.pos - ini)
        return St.SUCCESS
    }

    /** Escreve uma entrada de listagem na classe pedida; null = classe não suportada. */
    private fun entradaDir(classe: Int, e: DirEntry, nomeB: ByteArray): Unit? {
        val f = e.file
        val dir = f.isDirectory
        val tam = if (dir) 0L else f.length()
        if (classe == 12) { // FileNamesInformation
            out.u32(0); out.u32(0); out.u32(nomeB.size); out.bytes(nomeB)
            return Unit
        }
        if (classe !in listOf(1, 2, 3, 37, 38)) return null
        out.u32(0); out.u32(0)
        tempos(f)
        out.u64(tam); out.u64(allocOf(tam))
        out.u32(attrsOf(dir, paths.readOnly))
        out.u32(nomeB.size)
        when (classe) {
            2 -> out.u32(0)
            3 -> { out.u32(0); out.u8(0); out.u8(0); out.zeros(24) }
            37 -> { out.u32(0); out.u8(0); out.u8(0); out.zeros(24); out.u16(0); out.u64(fileId(f)) }
            38 -> { out.u32(0); out.u32(0); out.u64(fileId(f)) }
        }
        out.bytes(nomeB)
        return Unit
    }

    private fun info(c: Int): Int {
        val tipo = req.u8(c + 2)
        val classe = req.u8(c + 3)
        val max = req.i32(c + 4).toLong() and 0xFFFFFFFFL
        val a = abertos[idArquivo(c + 24)] ?: return FILE_CLOSED
        out.u16(9); out.u16(64 + 8)
        val tamAt = out.pos
        out.u32(0)
        val ini = out.pos
        val f = a.file
        val dir = a.isDir
        val tam = if (dir) 0L else try { a.handle?.length() ?: f.length() } catch (e: IOException) { f.length() }
        val ft = fileTime(f.lastModified())
        val attrs = attrsOf(dir, paths.readOnly)
        fun basico() { out.u64(ft); out.u64(ft); out.u64(ft); out.u64(ft); out.u32(attrs); out.u32(0) }
        fun padrao() { out.u64(allocOf(tam)); out.u64(tam); out.u32(1); out.u8(if (a.deleteOnClose) 1 else 0); out.u8(if (dir) 1 else 0); out.u16(0) }
        when (tipo) {
            1 -> when (classe) {
                4 -> basico()
                5 -> padrao()
                6 -> out.u64(fileId(f))
                7 -> out.u32(0)
                8 -> out.u32(if (a.writable) 0x001F01FF else 0x001200A9)
                14 -> out.u64(0)
                16 -> out.u32(0)
                17 -> out.u32(0)
                18 -> { // FileAllInformation
                    basico(); padrao(); out.u64(fileId(f)); out.u32(0)
                    out.u32(if (a.writable) 0x001F01FF else 0x001200A9); out.u64(0); out.u32(0); out.u32(0)
                    val n = ("\\" + f.path.substringAfterLast('/')).toByteArray(Charsets.UTF_16LE)
                    out.u32(n.size); out.bytes(n)
                }
                22 -> { out.u32(0); out.u32(0) } // FileStreamInformation vazio
                34 -> { out.u64(ft); out.u64(ft); out.u64(ft); out.u64(ft); out.u64(allocOf(tam)); out.u64(tam); out.u32(attrs); out.u32(0) }
                35 -> { out.u32(attrs); out.u32(0) }
                else -> return St.INVALID_LEVEL
            }
            2 -> {
                val total = paths.root.totalSpace().takeIf { it > 0 } ?: (64L shl 30)
                val livre = paths.root.usableSpace().takeIf { it > 0 } ?: 0L
                when (classe) {
                    1 -> { out.u64(0); out.u32(0x50533220); out.u32(6); out.u8(0); out.u8(0); out.bytes("PS2".toByteArray(Charsets.UTF_16LE)) }
                    3 -> { out.u64(total / 4096); out.u64(livre / 4096); out.u32(8); out.u32(512) }
                    4 -> { out.u32(7); out.u32(0) }
                    5 -> { out.u32(0x3); out.u32(255); val n = "NTFS".toByteArray(Charsets.UTF_16LE); out.u32(n.size); out.bytes(n) }
                    7 -> { out.u64(total / 4096); out.u64(livre / 4096); out.u64(livre / 4096); out.u32(8); out.u32(512) }
                    else -> return St.INVALID_LEVEL
                }
            }
            else -> return St.NOT_SUPPORTED
        }
        val n = out.pos - ini
        if (n > max) { out.pos = ini; return St.BUFFER_TOO_SMALL }
        out.set32(tamAt, n)
        return St.SUCCESS
    }

    private fun definir(c: Int): Int {
        val tipo = req.u8(c + 2)
        val classe = req.u8(c + 3)
        val bLen = req.i32(c + 4)
        val bOff = (c - 64) + req.u16(c + 8)
        val a = abertos[idArquivo(c + 16)] ?: return FILE_CLOSED
        if (tipo != 1) return St.NOT_SUPPORTED
        if (bOff < 0 || bOff + bLen > req.len) throw Malformed()
        val st = when (classe) {
            20 -> { // EndOfFile (VMC, saves)
                val h = a.handle ?: return St.INVALID_PARAMETER
                if (!a.writable) return St.ACCESS_DENIED
                try { h.setLength(req.u64(bOff)); St.SUCCESS } catch (e: IOException) { St.DISK_FULL }
            }
            19, 4 -> St.SUCCESS // alocação e datas: ignorados
            13 -> {
                if (paths.readOnly) return St.ACCESS_DENIED
                val del = req.u8(bOff) != 0
                if (del && a.isDir && a.file.listFiles()?.isNotEmpty() == true) return St.DIRECTORY_NOT_EMPTY
                a.deleteOnClose = del
                St.SUCCESS
            }
            10 -> renomear(a, bOff)
            else -> St.INVALID_LEVEL
        }
        if (st == St.SUCCESS) out.u16(2)
        return st
    }

    private fun renomear(a: Aberto, b: Int): Int {
        if (paths.readOnly) return St.ACCESS_DENIED
        val substitui = req.u8(b) != 0
        val n = req.i32(b + 16)
        if (n < 0 || b + 20 + n > req.len) throw Malformed()
        val destino = paths.resolve(String(req.b, b + 20, n, Charsets.UTF_16LE).trimStart('\\')) ?: return St.OBJECT_NAME_INVALID
        if (destino.exists()) {
            if (destino == a.file) return St.SUCCESS
            if (!substitui) return St.OBJECT_NAME_COLLISION
            if (!destino.delete()) return St.ACCESS_DENIED
        }
        if (!a.file.renameTo(destino)) return St.ACCESS_DENIED
        a.file = destino
        return St.SUCCESS
    }

    // ------------------------------------------------------------------ SPNEGO (DER mínimo)

    private fun der(tag: Int, conteudo: ByteArray): ByteArray {
        val n = conteudo.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + conteudo
    }

    /** NegTokenInit anunciando só NTLMSSP (vai na resposta do NEGOTIATE). */
    private fun negTokenInit(): ByteArray {
        val mechs = der(0x30, der(0x06, OID_NTLMSSP))
        val init = der(0x30, der(0xA0, mechs))
        return der(0x60, der(0x06, OID_SPNEGO) + der(0xA0, init))
    }

    /** NegTokenResp: estado (0 concluído, 1 incompleto) e, se houver, o token NTLMSSP. */
    private fun negTokenResp(estado: Int, token: ByteArray?, comMech: Boolean): ByteArray {
        var c = der(0xA0, der(0x0A, byteArrayOf(estado.toByte())))
        if (comMech) c += der(0xA1, der(0x06, OID_NTLMSSP))
        if (token != null) c += der(0xA2, der(0x04, token))
        return der(0xA1, der(0x30, c))
    }

    private fun indice(b: ByteArray, alvo: ByteArray): Int {
        outer@ for (i in 0..b.size - alvo.size) {
            for (j in alvo.indices) if (b[i + j] != alvo[j]) continue@outer
            return i
        }
        return -1
    }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)

    companion object {
        const val NEGOTIATE = 0x00
        const val SESSION_SETUP = 0x01
        const val LOGOFF = 0x02
        const val TREE_CONNECT = 0x03
        const val TREE_DISCONNECT = 0x04
        const val CREATE = 0x05
        const val CLOSE = 0x06
        const val FLUSH = 0x07
        const val READ = 0x08
        const val WRITE = 0x09
        const val LOCK = 0x0A
        const val CANCEL = 0x0C
        const val ECHO = 0x0D
        const val QUERY_DIRECTORY = 0x0E
        const val QUERY_INFO = 0x10
        const val SET_INFO = 0x11

        /** Bits de acesso que leem ou gravam conteúdo (READ/WRITE/APPEND/EXECUTE, GENERIC_*, MAXIMUM_ALLOWED). */
        const val DADOS = 0x1 or 0x2 or 0x4 or 0x20 or 0x02000000 or 0x10000000 or 0x20000000 or 0x40000000 or 0x80000000.toInt()
        const val MORE_PROCESSING = 0xC0000016L.toInt()
        const val END_OF_FILE = 0xC0000011L.toInt()
        const val FILE_CLOSED = 0xC0000128L.toInt()
        const val USER_SESSION_DELETED = 0xC0000203L.toInt()
        const val NETWORK_NAME_DELETED = 0xC00000C9L.toInt()

        private val NTLMSSP = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
        private val OID_SPNEGO = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x02)
        private val OID_NTLMSSP = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0A)
    }
}
