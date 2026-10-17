package br.ps2ponte.core.smb

import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.TimeZone

/**
 * Uma conexão TCP (um cliente). Atende um pedido por vez, na ordem em que chegam.
 * A árvore é de [VFile] (java.io.File ou SAF): no SAF cada metadado é IPC, então arquivos abertos
 * guardam o que já se sabe ([OpenFile.isDir]) e o tamanho vem do [VHandle].
 */
internal class SmbConnection(private val srv: SmbServer, private val sock: Socket, private val ip: String) {

    private class Tree(val tid: Int, val ipc: Boolean)

    /** Arquivo ou pasta aberto; [handle] é null para pastas. */
    private class OpenFile(
        val fid: Int, val tid: Int, val uid: Int, val file: VFile, val path: String,
        val handle: VHandle?, val isDir: Boolean, val writable: Boolean, var deleteOnClose: Boolean,
    )

    private class Blk(val wc: Int, val p: Int, val b: Int, val end: Int)

    private class ReadData(val handle: VHandle, val offset: Long, val len: Int)

    private val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 128 * 1024))
    private val outs: OutputStream = sock.getOutputStream()
    private val req = Msg(ByteArray(64 * 1024 + 512))
    private val out = Out(16 * 1024)
    private val paths = srv.paths
    private val ro get() = paths.readOnly
    private val challenge = ByteArray(8).also { SecureRandom().nextBytes(it) }

    private val uids = HashSet<Int>()
    private val trees = HashMap<Int, Tree>()
    private val files = HashMap<Int, OpenFile>()
    private val searches = LinkedHashMap<Int, Search>()
    private var nextUid = 100
    private var nextTid = 1
    private var nextFid = 1
    private var nextSid = 1
    private var clientCaps = 0
    private var clientMaxBuffer = 4356

    // Estado do pedido atual.
    private var flags2 = 0
    private var uni = false
    private var curTid = 0
    private var curUid = 0
    private var chainFid = -1
    private var readData: ReadData? = null
    private var noReply = false
    private var stopChain = false
    private val tp = Out(512)       // parâmetros de TRANS/TRANS2
    private val td = Out(8 * 1024)  // dados de TRANS/TRANS2

    fun run() {
        try {
            while (true) {
                val type = inp.read()
                if (type < 0) return
                val b1 = inp.readUnsignedByte()
                val b2 = inp.readUnsignedByte()
                val b3 = inp.readUnsignedByte()
                when (type) {
                    0x00 -> {  // mensagem: comprimento de 24 bits (TCP direto)
                        readMessage((b1 shl 16) or (b2 shl 8) or b3)
                        if (!handle()) return
                    }
                    0x81 -> {  // pedido de sessão NetBIOS (porta 139): responde positivo
                        skip(((b1 and 1) shl 16) or (b2 shl 8) or b3)
                        outs.write(byteArrayOf(0x82.toByte(), 0, 0, 0))
                    }
                    0x85 -> skip(((b1 and 1) shl 16) or (b2 shl 8) or b3)  // keepalive
                    else -> return
                }
            }
        } catch (e: IOException) {
            // conexão caiu
        } catch (e: RuntimeException) {
            srv.log("SMB: erro com $ip: $e")
        } finally {
            cleanup()
        }
    }

    private fun readMessage(n: Int) {
        if (req.b.size < n) req.b = ByteArray(n + 512)
        inp.readFully(req.b, 0, n)
        req.len = n
    }

    private fun skip(n: Int) {
        var left = n
        while (left > 0) {
            val k = inp.skipBytes(left)
            if (k <= 0) {
                inp.readUnsignedByte(); left--
            } else {
                left -= k
            }
        }
    }

    private fun cleanup() {
        for (of in files.values) closeFile(of)
        files.clear()
        searches.clear()
        trees.clear()
        try {
            sock.close()
        } catch (e: IOException) {
        }
    }

    // ---------------------------------------------------------------- mensagens

    /** Trata uma mensagem SMB (com AndX). false = fechar a conexão. */
    private fun handle(): Boolean {
        val n = req.len
        val b = req.b
        if (n < 35 || b[0] != 0xFF.toByte() || b[1] != 'S'.code.toByte() || b[2] != 'M'.code.toByte() || b[3] != 'B'.code.toByte()) {
            if (n >= 4 && b[0] == 0xFE.toByte()) srv.log("SMB: $ip tentou SMB2 (só SMB1 é suportado)")
            return false
        }
        val cmd = req.u8(4)
        flags2 = req.u16(10)
        uni = (flags2 and Flags2.UNICODE) != 0
        curTid = req.u16(24)
        curUid = req.u16(28)
        chainFid = -1
        readData = null
        noReply = false
        stopChain = false

        if (cmd == Cmd.ECHO) {
            echo()
            return true
        }

        beginReply(cmd)
        var status: Int
        var off = 32
        var c = cmd
        var prevAndx = -1
        while (true) {
            val start = out.pos
            if (prevAndx >= 0) {
                out.set8(prevAndx, c)
                out.set16(prevAndx + 2, start - 4)
            }
            status = try {
                dispatch(c, block(off))
            } catch (e: Malformed) {
                St.INVALID_PARAMETER
            } catch (e: FileNotFoundException) {
                St.ACCESS_DENIED
            } catch (e: IOException) {
                ioStatus(e)
            }
            if (noReply) return true
            if (status != St.SUCCESS) {
                out.pos = start
                out.u8(0); out.u16(0)
                readData = null
                break
            }
            if (c !in Cmd.ANDX || stopChain) break
            val next = req.u8(off + 1)
            val nextOff = req.u16(off + 3)
            if (next == Cmd.NONE || nextOff <= off || nextOff >= n) break
            prevAndx = start + 1
            off = nextOff
            c = next
        }
        out.set32(9, if ((flags2 and Flags2.STATUS32) != 0) status else toDos(status))
        out.set16(28, curTid)
        out.set16(32, curUid)
        send()
        return true
    }

    /** Cabeçalho da resposta: 4 bytes NBSS + 32 bytes SMB (status, TID e UID ajustados no fim). */
    private fun beginReply(cmd: Int) {
        out.pos = 0
        out.u32(0)
        out.u8(0xFF); out.u8('S'.code); out.u8('M'.code); out.u8('B'.code)
        out.u8(cmd)
        out.u32(0)                    // status
        out.u8(0x98)                  // resposta, nomes sem diferenciar maiúsculas
        var f2 = flags2 and (Flags2.UNICODE or Flags2.STATUS32 or Flags2.IS_LONG_NAME or Flags2.EAS or Flags2.KNOWS_LONG_NAMES)
        if (cmd == Cmd.NEGOTIATE) f2 = (f2 and Flags2.UNICODE.inv()) or (if (srv.unicode) Flags2.UNICODE else 0)
        out.u16(f2)
        out.u16(req.u16(12))          // PIDHigh
        out.zeros(8)                  // assinatura
        out.u16(0)
        out.u16(curTid)
        out.u16(req.u16(26))          // PID
        out.u16(curUid)
        out.u16(req.u16(30))          // MID
    }

    private fun block(off: Int): Blk {
        val wc = req.u8(off)
        val p = off + 1
        val bc = req.u16(p + 2 * wc)
        val b = p + 2 * wc + 2
        return Blk(wc, p, b, minOf(b + bc, req.len))
    }

    /** Envia a resposta. Num READ, cabeçalho e o começo dos dados saem numa única escrita. */
    private fun send() {
        val rd = readData
        val total = out.pos - 4 + (rd?.len ?: 0)
        out.set8(0, 0); out.set8(1, total ushr 16); out.set8(2, total ushr 8); out.set8(3, total)
        if (rd == null) {
            outs.write(out.b, 0, out.pos)
            return
        }
        var left = rd.len
        var pos = rd.offset
        var base = out.pos
        do {
            val k = minOf(left, READ_CHUNK)
            out.pos = base
            out.need(k)
            readAt(rd.handle, pos, out.b, base, k)
            outs.write(out.b, 0, base + k)
            left -= k
            pos += k
            base = 0
        } while (left > 0)
        srv.statsRef.bytesRead.addAndGet(rd.len.toLong())
    }

    private fun readAt(h: VHandle, pos: Long, buf: ByteArray, off: Int, n: Int) {
        val done = h.readFully(pos, buf, off, n)
        // arquivo encolheu: completa com zeros para manter o fluxo
        if (done < n) java.util.Arrays.fill(buf, off + maxOf(done, 0), off + n, 0)
    }

    private fun ioStatus(e: IOException): Int {
        val m = e.message ?: ""
        return if (m.contains("ENOSPC") || m.contains("No space", ignoreCase = true)) St.DISK_FULL else St.UNEXPECTED_IO_ERROR
    }

    private fun dispatch(c: Int, k: Blk): Int {
        when (c) {
            Cmd.NEGOTIATE -> return negotiate(k)
            Cmd.SESSION_SETUP_ANDX -> return sessionSetup(k)
            Cmd.LOGOFF_ANDX -> return logoff()
            Cmd.TREE_CONNECT_ANDX -> return treeConnect(k)
            Cmd.TREE_DISCONNECT -> return treeDisconnect()
            Cmd.NT_CANCEL -> {
                noReply = true
                return St.SUCCESS
            }
        }
        val t = trees[curTid] ?: return St.SMB_BAD_TID
        return when (c) {
            Cmd.TRANSACTION -> trans(k, t)
            Cmd.TRANSACTION2 -> trans2(k, t)
            Cmd.NT_CREATE_ANDX -> ntCreate(k, t)
            Cmd.OPEN_ANDX -> openAndX(k, t)
            Cmd.READ_ANDX -> readAndX(k)
            Cmd.WRITE_ANDX -> writeAndX(k)
            Cmd.CLOSE -> close(k)
            Cmd.FLUSH -> flush(k)
            Cmd.LOCKING_ANDX -> locking(k)
            Cmd.FIND_CLOSE2 -> findClose(k)
            else -> {
                if (t.ipc) return St.ACCESS_DENIED
                when (c) {
                    Cmd.DELETE -> delete(k)
                    Cmd.CREATE_DIRECTORY -> mkdir(k)
                    Cmd.DELETE_DIRECTORY -> rmdir(k)
                    Cmd.RENAME -> rename(k)
                    Cmd.CHECK_DIRECTORY -> checkDir(k)
                    Cmd.QUERY_INFORMATION -> queryInformation(k)
                    Cmd.QUERY_INFORMATION_DISK -> queryDisk()
                    else -> St.SMB_BAD_COMMAND
                }
            }
        }
    }

    private fun andx() {
        out.u8(Cmd.NONE); out.u8(0); out.u16(0)
    }

    private fun emptyReply(): Int {
        out.u8(0); out.u16(0)
        return St.SUCCESS
    }

    // ---------------------------------------------------------------- sessão

    private fun negotiate(k: Blk): Int {
        var p = k.b
        var idx = 0
        var chosen = -1
        while (p < k.end && req.u8(p) == 0x02) {
            val (name, next) = req.str(p + 1, k.end, false)
            if (chosen < 0 && (name == "NT LM 0.12" || name == "NT LANMAN 1.0")) chosen = idx
            idx++
            p = next
        }
        if (chosen < 0) {
            out.u8(1); out.u16(0xFFFF); out.u16(0)
            return St.SUCCESS
        }
        var caps = Cap.LARGE_FILES or Cap.NT_SMBS or Cap.RPC_REMOTE_APIS or Cap.STATUS32 or Cap.NT_FIND or
            Cap.LARGE_READX or Cap.LARGE_WRITEX
        if (srv.unicode) caps = caps or Cap.UNICODE
        val now = System.currentTimeMillis()
        out.u8(17)
        out.u16(chosen)
        out.u8(0x03)              // nível usuário + desafio/resposta (sem assinatura)
        out.u16(50)               // MaxMpxCount
        out.u16(1)                // MaxNumberVcs
        out.u32(MAX_BUFFER)       // MaxBufferSize (o mesmo de Windows/Samba)
        out.u32(65536)            // MaxRawSize (sem modo raw)
        out.u32(0)                // SessionKey
        out.u32(caps)
        out.u64(fileTime(now))
        out.u16(-(TimeZone.getDefault().getOffset(now) / 60000))
        out.u8(challenge.size)    // EncryptionKeyLength
        val bc = out.pos
        out.u16(0)
        out.bytes(challenge)
        out.text(DOMAIN, srv.unicode)    // o smbman lê este nome e devolve no SESSION_SETUP
        out.text(SERVER_NAME, srv.unicode)
        out.set16(bc, out.pos - bc - 2)
        return St.SUCCESS
    }

    private fun sessionSetup(k: Blk): Int {
        val p = k.p
        when (k.wc) {
            13 -> {  // NT LM 0.12 sem segurança estendida (o que o PS2 manda)
                clientMaxBuffer = req.u16(p + 4)
                clientCaps = req.i32(p + 22)
            }
            10 -> {  // LANMAN antigo
                clientMaxBuffer = req.u16(p + 4)
                clientCaps = 0
            }
            else -> return St.NOT_SUPPORTED  // segurança estendida não foi oferecida
        }
        // Qualquer usuário/senha é aceito: não confere hashes nem nomes.
        val uid = newId(nextUid) { it in uids }
        nextUid = uid + 1
        uids += uid
        curUid = uid
        out.u8(3)
        andx()
        out.u16(0)  // Action
        val bc = out.pos
        out.u16(0)
        if (uni && (out.pos - 4) % 2 != 0) out.u8(0)
        out.text("PS2 Ponte", uni)   // NativeOS
        out.text("PS2 Ponte", uni)   // NativeLanMan
        out.text(DOMAIN, uni)        // PrimaryDomain
        out.set16(bc, out.pos - bc - 2)
        return St.SUCCESS
    }

    private fun logoff(): Int {
        uids.remove(curUid)
        files.values.filter { it.uid == curUid }.forEach { closeFile(it); files.remove(it.fid) }
        out.u8(2)
        andx()
        out.u16(0)
        return St.SUCCESS
    }

    private fun treeConnect(k: Blk): Int {
        val flags = req.u16(k.p + 4)
        val pwLen = req.u16(k.p + 6)
        var q = k.b + pwLen
        if (uni && q % 2 != 0) q++
        val (path, _) = req.str(q, k.end, uni)
        val share = path.trimEnd('\\').substringAfterLast('\\')
        val ipc = share.equals("IPC$", ignoreCase = true)
        if ((flags and 0x0001) != 0) dropTree(curTid)  // TREE_CONNECT_ANDX_DISCONNECT_TID
        val tid = newId(nextTid) { it in trees }
        nextTid = tid + 1
        trees[tid] = Tree(tid, ipc)
        curTid = tid
        val ext = (flags and 0x0008) != 0
        out.u8(if (ext) 7 else 3)
        andx()
        out.u16(0x0001)  // SMB_SUPPORT_SEARCH_BITS
        if (ext) {
            val rights = if (ipc || !ro) 0x001F01FF else 0x001200A9
            out.u32(rights); out.u32(rights)
        }
        val bc = out.pos
        out.u16(0)
        out.bytes(enc(if (ipc) "IPC" else "A:", false)); out.u8(0)  // Service: sempre OEM
        if (uni && (out.pos - 4) % 2 != 0) out.u8(0)
        out.text(if (ipc) "" else "NTFS", uni)
        out.set16(bc, out.pos - bc - 2)
        return St.SUCCESS
    }

    private fun treeDisconnect(): Int {
        if (!trees.containsKey(curTid)) return St.SMB_BAD_TID
        dropTree(curTid)
        return emptyReply()
    }

    private fun dropTree(tid: Int) {
        trees.remove(tid) ?: return
        files.values.filter { it.tid == tid }.forEach { closeFile(it); files.remove(it.fid) }
        searches.values.removeAll { it.tid == tid }
    }

    private fun echo() {
        val k = try {
            block(32)
        } catch (e: Malformed) {
            return
        }
        val count = if (k.wc >= 1) minOf(req.u16(k.p), 64) else 1
        val n = k.end - k.b
        for (seq in 1..count) {
            beginReply(Cmd.ECHO)
            out.u8(1)
            out.u16(seq)
            out.u16(n)
            out.bytes(req.b, k.b, n)
            send()
        }
    }

    /** Próximo identificador livre (1..0xFFFE) a partir de [start]. */
    private inline fun newId(start: Int, used: (Int) -> Boolean): Int {
        var id = start
        repeat(0xFFFF) {
            if (id < 1 || id > 0xFFFE) id = 1
            if (!used(id)) return id
            id++
        }
        throw IOException("sem identificadores livres")
    }

    // ---------------------------------------------------------------- TRANS (IPC$) e TRANS2

    private class TransReq(
        val maxParam: Int, val maxData: Int, val po: Int, val pc: Int, val dataOff: Int, val dc: Int, val setup: Int,
    )

    /** Lê os campos comuns de SMB_COM_TRANSACTION/TRANSACTION2. null = precisaria de pedidos secundários. */
    private fun transReq(k: Blk): TransReq? {
        val p = k.p
        val totalParam = req.u16(p)
        val totalData = req.u16(p + 2)
        val pc = req.u16(p + 18)
        val po = req.u16(p + 20)
        val dc = req.u16(p + 22)
        val dataOff = req.u16(p + 24)
        val setupCount = req.u8(p + 26)
        if (pc < totalParam || dc < totalData) return null
        if (pc > 0 && po + pc > req.len) throw Malformed()
        if (dc > 0 && dataOff + dc > req.len) throw Malformed()
        val setup = if (setupCount > 0) req.u16(p + 28) else -1
        return TransReq(req.u16(p + 4), req.u16(p + 6), po, pc, dataOff, dc, setup)
    }

    /** Resposta de TRANS/TRANS2 com [tp] e [td]; parâmetros e dados alinhados em 4 bytes. */
    private fun transReply() {
        val pn = tp.pos
        val dn = td.pos
        val w = out.pos
        out.u8(10)
        out.u16(pn); out.u16(dn); out.u16(0)
        out.u16(pn); out.u16(0); out.u16(0)
        out.u16(dn); out.u16(0); out.u16(0)
        out.u8(0); out.u8(0)
        val bc = out.pos
        out.u16(0)
        out.align(4, 4)
        val po = out.pos - 4
        out.bytes(tp.b, 0, pn)
        out.align(4, 4)
        val dO = out.pos - 4
        out.bytes(td.b, 0, dn)
        out.set16(w + 9, po)
        out.set16(w + 15, dO)
        out.set16(bc, out.pos - bc - 2)
    }

    private fun trans(k: Blk, t: Tree): Int {
        val r = transReq(k) ?: return St.NOT_SUPPORTED
        if (!t.ipc) return St.NOT_SUPPORTED
        var q = k.b
        if (uni && q % 2 != 0) q++
        val (name, _) = req.str(q, k.end, uni)
        if (!name.equals("\\PIPE\\LANMAN", ignoreCase = true) || r.pc < 2) return St.NOT_SUPPORTED
        tp.pos = 0
        td.pos = 0
        rap(r)
        if (td.pos > r.maxData) td.pos = 0
        transReply()
        return St.SUCCESS
    }

    /** RAP (\PIPE\LANMAN). Só NetShareEnum nível 1, como o smb_NetShareEnum do smbman pede. */
    private fun rap(r: TransReq) {
        val end = r.po + r.pc
        val fn = req.u16(r.po)
        val (_, q1) = req.str(r.po + 2, end, false)  // descritor dos parâmetros ("WrLeh")
        val (_, q2) = req.str(q1, end, false)        // descritor dos dados ("B13BWz")
        if (fn != 0) {
            tp.u16(50); tp.u16(0); tp.u16(0); tp.u16(0)  // ERROR_NOT_SUPPORTED
            return
        }
        val level = req.u16(q2)
        if (level != 1) {
            tp.u16(124); tp.u16(0); tp.u16(0); tp.u16(0)  // ERROR_INVALID_LEVEL
            return
        }
        // share_info_1: nome[13], pad, tipo, ponteiro do comentário (deslocamento nos dados, conversor 0)
        val shares = listOf(Triple(srv.shareName, 0, "PS2 Ponte"), Triple("IPC$", 3, "IPC"))
        var remarkOff = shares.size * 20
        for ((name, type, remark) in shares) {
            val nb = name.toByteArray(Charsets.UTF_8).copyOf(12)
            td.bytes(nb); td.u8(0); td.u8(0)
            td.u16(type)
            td.u32(remarkOff)
            remarkOff += remark.length + 1
        }
        for ((_, _, remark) in shares) {
            td.bytes(remark.toByteArray(Charsets.US_ASCII)); td.u8(0)
        }
        tp.u16(0)              // status NERR_Success
        tp.u16(0)              // conversor
        tp.u16(shares.size)    // entradas devolvidas
        tp.u16(shares.size)    // entradas disponíveis (o smbman usa este)
    }

    private fun trans2(k: Blk, t: Tree): Int {
        val r = transReq(k) ?: return St.NOT_SUPPORTED
        if (t.ipc) return St.NOT_SUPPORTED
        tp.pos = 0
        td.pos = 0
        val st = when (r.setup) {
            0x01 -> findFirst(r)
            0x02 -> findNext(r)
            0x03 -> queryFs(r)
            0x05 -> queryPath(r)
            0x06 -> setPath(r)
            0x07 -> queryFile(r)
            0x08 -> setFile(r)
            else -> St.NOT_SUPPORTED
        }
        if (st != St.SUCCESS) return st
        if (tp.pos > r.maxParam || td.pos > r.maxData) return St.BUFFER_TOO_SMALL
        transReply()
        return St.SUCCESS
    }

    // ---------------------------------------------------------------- busca em pastas

    private fun findFirst(r: TransReq): Int {
        val po = r.po
        val attrs = req.u16(po)
        val count = req.u16(po + 2)
        val flags = req.u16(po + 4)
        val level = req.u16(po + 6)
        val (pattern, _) = req.str(po + 12, po + r.pc, uni)
        if (findHeaderSize(level) < 0) return St.INVALID_LEVEL
        val norm = pattern.replace('/', '\\')
        val i = norm.lastIndexOf('\\')
        val dirPath = if (i >= 0) norm.substring(0, i) else ""
        val mask = (if (i >= 0) norm.substring(i + 1) else norm).ifEmpty { "*" }
        val dir = paths.resolve(dirPath) ?: return St.ACCESS_DENIED
        if (!dir.isDirectory) return St.OBJECT_PATH_NOT_FOUND
        val entries = listDir(dir, mask, (attrs and 0x10) != 0)
        if (entries.isEmpty()) return St.NO_SUCH_FILE
        val sid = newId(nextSid) { it in searches }
        nextSid = sid + 1
        val s = Search(sid, curTid, entries)
        val (n, last) = fillFind(s, level, count, findLimit(r))
        if (n == 0) return St.BUFFER_TOO_SMALL
        val eos = s.pos >= s.entries.size
        if ((flags and 0x01) == 0 && !(eos && (flags and 0x02) != 0)) keepSearch(s)
        tp.u16(sid)
        tp.u16(n)
        tp.u16(if (eos) 1 else 0)
        tp.u16(0)      // EaErrorOffset
        tp.u16(last)   // LastNameOffset
        return St.SUCCESS
    }

    private fun findNext(r: TransReq): Int {
        val po = r.po
        val sid = req.u16(po)
        val count = req.u16(po + 2)
        val level = req.u16(po + 4)
        val flags = req.u16(po + 10)
        val (resume, _) = req.str(po + 12, po + r.pc, uni)
        if (findHeaderSize(level) < 0) return St.INVALID_LEVEL
        val s = searches[sid] ?: return St.INVALID_HANDLE
        if ((flags and 0x08) == 0 && resume.isNotEmpty()) {  // sem CONTINUE: retoma depois do nome dado
            var idx = s.entries.indexOfFirst { it.name == resume }
            if (idx < 0) idx = s.entries.indexOfFirst { it.name.equals(resume, ignoreCase = true) }
            if (idx >= 0) s.pos = idx + 1
        }
        val (n, last) = fillFind(s, level, count, findLimit(r))
        val eos = s.pos >= s.entries.size
        if (n == 0 && !eos) return St.BUFFER_TOO_SMALL
        if ((flags and 0x01) != 0 || (eos && (flags and 0x02) != 0)) searches.remove(sid)
        tp.u16(n)
        tp.u16(if (eos) 1 else 0)
        tp.u16(0)
        tp.u16(last)
        return St.SUCCESS
    }

    /** Dados cabem em MaxDataCount e no buffer declarado pelo cliente no SESSION_SETUP. */
    private fun findLimit(r: TransReq): Int = minOf(r.maxData, maxOf(clientMaxBuffer, 1024) - 80, 60_000)

    /** Preenche [td] com entradas; devolve (quantidade, deslocamento da última). */
    private fun fillFind(s: Search, level: Int, maxCount: Int, maxBytes: Int): Pair<Int, Int> {
        val limit = if (maxCount == 0) Int.MAX_VALUE else maxCount
        val head = findHeaderSize(level)
        var n = 0
        var last = 0
        while (s.pos < s.entries.size && n < limit) {
            val e = s.entries[s.pos]
            val name = enc(e.name, uni)
            val size = head + name.size + (if (uni) 2 else 1)
            val start = if (n == 0) 0 else (td.pos + 7) and 7.inv()
            if (start + size > maxBytes) break
            if (n > 0) {
                td.align(0, 8)
                td.set32(last, start - last)  // NextEntryOffset da anterior
            }
            writeFindEntry(td, level, e, name, uni, ro)
            last = start
            n++
            s.pos++
        }
        return n to last
    }

    /** O OPL nunca manda FIND_CLOSE2: guarda no máximo 64 buscas abertas por conexão. */
    private fun keepSearch(s: Search) {
        searches[s.sid] = s
        while (searches.size > 64) searches.remove(searches.keys.first())
    }

    private fun findClose(k: Blk): Int {
        searches.remove(req.u16(k.p)) ?: return St.INVALID_HANDLE
        return emptyReply()
    }

    // ---------------------------------------------------------------- informações

    private fun queryPath(r: TransReq): Int {
        val level = req.u16(r.po)
        val (name, _) = req.str(r.po + 6, r.po + r.pc, uni)
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (!f.exists()) return notFound(f)
        tp.u16(0)  // EaErrorOffset
        return fileInfo(level, f, name, null)
    }

    private fun queryFile(r: TransReq): Int {
        val of = fileFor(req.u16(r.po)) ?: return St.INVALID_HANDLE
        tp.u16(0)
        return fileInfo(req.u16(r.po + 2), of.file, of.path, of)
    }

    /** Níveis de QUERY_PATH/QUERY_FILE_INFORMATION. O smbman usa 0x101 e depois 0x102. */
    private fun fileInfo(level: Int, f: VFile, path: String, of: OpenFile?): Int {
        val dir = of?.isDir ?: f.isDirectory
        val size = if (dir) 0L else (of?.handle?.length() ?: f.length())
        val mtime = f.lastModified()
        val t = fileTime(mtime)
        val attrs = attrsOf(dir, ro)
        val delPending = if (of?.deleteOnClose == true) 1 else 0
        when (level) {
            1, 2 -> {  // SMB_INFO_STANDARD / SMB_INFO_QUERY_EA_SIZE
                val (d, tm) = dosDateTime(mtime)
                repeat(3) { td.u16(d); td.u16(tm) }
                td.u32(minOf(size, 0xFFFFFFFFL))
                td.u32(minOf(allocOf(size), 0xFFFFFFFFL))
                td.u16(attrs)
                if (level == 2) td.u32(0)
            }
            0x101 -> {  // SMB_QUERY_FILE_BASIC_INFO
                td.u64(t); td.u64(t); td.u64(t); td.u64(t)
                td.u32(attrs); td.u32(0)
            }
            0x102 -> {  // SMB_QUERY_FILE_STANDARD_INFO
                td.u64(allocOf(size)); td.u64(size)
                td.u32(1); td.u8(delPending); td.u8(if (dir) 1 else 0); td.u16(0)
            }
            0x103 -> td.u32(0)  // SMB_QUERY_FILE_EA_INFO
            0x104 -> {  // SMB_QUERY_FILE_NAME_INFO
                val nb = enc(displayPath(path), uni)
                td.u32(nb.size); td.bytes(nb)
            }
            0x107 -> {  // SMB_QUERY_FILE_ALL_INFO
                td.u64(t); td.u64(t); td.u64(t); td.u64(t)
                td.u32(attrs); td.u32(0)
                td.u64(allocOf(size)); td.u64(size)
                td.u32(1); td.u8(delPending); td.u8(if (dir) 1 else 0); td.u16(0)
                td.u32(0)
                val nb = enc(displayPath(path), uni)
                td.u32(nb.size); td.bytes(nb)
            }
            0x109 -> if (!dir) {  // SMB_QUERY_FILE_STREAM_INFO
                val nb = enc("::\$DATA", true)
                td.u32(0); td.u32(nb.size); td.u64(size); td.u64(allocOf(size)); td.bytes(nb)
            }
            else -> return St.INVALID_LEVEL
        }
        return St.SUCCESS
    }

    private fun setFile(r: TransReq): Int {
        val of = fileFor(req.u16(r.po)) ?: return St.INVALID_HANDLE
        val level = req.u16(r.po + 2)
        val d = r.dataOff
        when (level) {
            0x101, 1004 -> {  // datas: VFile não muda datas; aceita sem efeito
                if (r.dc < 32) return St.INVALID_PARAMETER
                val lw = req.u64(d + 16)
                if (lw != 0L && lw != -1L && ro) return St.ACCESS_DENIED
            }
            0x102, 1013 -> {  // apagar ao fechar
                if (r.dc < 1) return St.INVALID_PARAMETER
                val del = req.u8(d) != 0
                if (del && (ro || of.file == paths.root)) return St.ACCESS_DENIED
                if (del && of.isDir && (of.file.listFiles()?.isNotEmpty() == true)) return St.DIRECTORY_NOT_EMPTY
                of.deleteOnClose = del
            }
            0x103, 1019 -> {}  // tamanho alocado: ignorado
            0x104, 1020 -> {  // fim do arquivo
                if (r.dc < 8) return St.INVALID_PARAMETER
                val h = of.handle ?: return St.INVALID_DEVICE_REQUEST
                if (!of.writable || ro) return St.ACCESS_DENIED
                h.setLength(req.u64(d))
            }
            else -> return St.INVALID_LEVEL
        }
        tp.u16(0)
        return St.SUCCESS
    }

    private fun setPath(r: TransReq): Int {
        val level = req.u16(r.po)
        val (name, _) = req.str(r.po + 6, r.po + r.pc, uni)
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (!f.exists()) return notFound(f)
        if (level != 0x101 && level != 1004) return St.NOT_SUPPORTED
        if (r.dc < 32) return St.INVALID_PARAMETER
        val lw = req.u64(r.dataOff + 16)
        if (lw != 0L && lw != -1L && ro) return St.ACCESS_DENIED  // VFile não muda datas: sem efeito
        tp.u16(0)
        return St.SUCCESS
    }

    private fun queryFs(r: TransReq): Int {
        val level = req.u16(r.po)
        val total = paths.root.totalSpace()
        val free = paths.root.usableSpace()
        val label = enc("PS2", uni)
        when (level) {
            1 -> {  // SMB_INFO_ALLOCATION
                td.u32(0); td.u32(8); td.u32(minOf(total / 4096, 0xFFFFFFFFL)); td.u32(minOf(free / 4096, 0xFFFFFFFFL)); td.u16(512)
            }
            2 -> {  // SMB_INFO_VOLUME
                td.u32(VOLUME_SERIAL); td.u8(label.size); td.bytes(label)
            }
            0x102 -> {  // SMB_QUERY_FS_VOLUME_INFO
                td.u64(0); td.u32(VOLUME_SERIAL); td.u32(label.size); td.u16(0); td.bytes(label)
            }
            0x103 -> {  // SMB_QUERY_FS_SIZE_INFO
                td.u64(total / 4096); td.u64(free / 4096); td.u32(8); td.u32(512)
            }
            0x104 -> {  // SMB_QUERY_FS_DEVICE_INFO: disco
                td.u32(7); td.u32(0)
            }
            0x105 -> {  // SMB_QUERY_FS_ATTRIBUTE_INFO
                val fs = enc("NTFS", uni)
                td.u32(0x06); td.u32(255); td.u32(fs.size); td.bytes(fs)
            }
            else -> return St.INVALID_LEVEL
        }
        return St.SUCCESS
    }

    private fun queryInformation(k: Blk): Int {
        val (name, _) = fmtStr(k.b, k.end) ?: return St.INVALID_PARAMETER
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (!f.exists()) return notFound(f)
        val dir = f.isDirectory
        val size = if (dir) 0L else f.length()
        out.u8(10)
        out.u16(attrsOf(dir, ro))
        out.u32(f.lastModified() / 1000)
        out.u32(minOf(size, 0xFFFFFFFFL))
        out.zeros(10)
        out.u16(0)
        return St.SUCCESS
    }

    private fun queryDisk(): Int {
        val total = paths.root.totalSpace()
        val free = paths.root.usableSpace()
        var perUnit = 1
        while (total / (512L * perUnit) > 0xFFFF && perUnit < 0x8000) perUnit *= 2
        val unit = 512L * perUnit
        out.u8(5)
        out.u16(minOf(total / unit, 0xFFFFL).toInt())
        out.u16(perUnit)
        out.u16(512)
        out.u16(minOf(free / unit, 0xFFFFL).toInt())
        out.u16(0)
        out.u16(0)
        return St.SUCCESS
    }

    private fun checkDir(k: Blk): Int {
        val (name, _) = fmtStr(k.b, k.end) ?: return St.INVALID_PARAMETER
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (!f.exists()) return St.OBJECT_PATH_NOT_FOUND
        if (!f.isDirectory) return St.NOT_A_DIRECTORY
        return emptyReply()
    }

    // ---------------------------------------------------------------- abrir/criar

    private fun ntCreate(k: Blk, t: Tree): Int {
        val p = k.p
        val nameLen = req.u16(p + 5)
        val access = req.i32(p + 15)
        val disp = req.i32(p + 35)
        val opts = req.i32(p + 39)
        var q = k.b
        if (uni && q % 2 != 0) q++
        val (name, _) = req.str(q, if (nameLen > 0) minOf(k.end, q + nameLen) else k.end, uni)
        if (t.ipc) return St.OBJECT_NAME_NOT_FOUND  // sem pipes (srvsvc etc.)
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        val write = (access and WRITE_ACCESS) != 0 || ((access and MAXIMUM_ALLOWED) != 0 && !ro)
        val st = doOpen(t, f, name, disp, write, dirOnly = (opts and 0x01) != 0, fileOnly = (opts and 0x40) != 0,
            delOnClose = (opts and 0x1000) != 0)
        if (st != St.SUCCESS) return st
        val of = files[chainFid]!!
        val dir = of.isDir
        val size = of.handle?.length() ?: 0L
        val tm = fileTime(f.lastModified())
        out.u8(34)
        andx()
        out.u8(0)                 // sem oplock
        out.u16(of.fid)
        out.u32(lastAction)
        out.u64(tm); out.u64(tm); out.u64(tm); out.u64(tm)
        out.u32(attrsOf(dir, ro))
        out.u64(allocOf(size))
        out.u64(size)             // EndOfFile (64 bits: o smbman usa como tamanho)
        out.u16(0)                // disco
        out.u16(0)
        out.u8(if (dir) 1 else 0)
        out.u16(0)
        return St.SUCCESS
    }

    private fun openAndX(k: Blk, t: Tree): Int {
        val p = k.p
        val accessMode = req.u16(p + 6)
        val openMode = req.u16(p + 16)
        var q = k.b
        if (uni && q % 2 != 0) q++
        val (name, _) = req.str(q, k.end, uni)
        if (t.ipc) return St.OBJECT_NAME_NOT_FOUND
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        val acc = accessMode and 0x7
        if (acc > 3) return St.INVALID_PARAMETER
        val write = acc == 1 || acc == 2
        val disp = if (f.exists()) {
            when (openMode and 0x3) {
                0 -> return St.OBJECT_NAME_COLLISION
                1 -> 1  // abrir
                else -> 4  // truncar
            }
        } else {
            if ((openMode and 0x10) == 0) return notFound(f)
            2  // criar
        }
        val st = doOpen(t, f, name, disp, write, dirOnly = false, fileOnly = true, delOnClose = false)
        if (st != St.SUCCESS) return st
        val of = files[chainFid]!!
        val size = of.handle?.length() ?: 0L
        out.u8(15)
        andx()
        out.u16(of.fid)
        out.u16(attrsOf(of.isDir, ro))
        out.u32(f.lastModified() / 1000)     // UTIME
        out.u32(minOf(size, 0xFFFFFFFFL))    // >4 GiB: o smbman pergunta o tamanho por QUERY_PATH
        out.u16(if (write) acc else 0)
        out.u16(0)                           // disco
        out.u16(0)
        out.u16(lastAction)                  // 1 aberto, 2 criado, 3 truncado
        out.u32(0)
        out.u16(0)
        out.u16(0)
        return St.SUCCESS
    }

    private var lastAction = 1

    /**
     * Abre/cria conforme a disposição NT (0 supersede, 1 open, 2 create, 3 open_if, 4 overwrite, 5 overwrite_if).
     * Em sucesso o arquivo aberto fica em files[chainFid] e a ação em [lastAction].
     */
    private fun doOpen(
        t: Tree, f: VFile, path: String, disp: Int, write: Boolean, dirOnly: Boolean, fileOnly: Boolean, delOnClose: Boolean,
    ): Int {
        val exists = f.exists()
        val isDir = exists && f.isDirectory
        var creates = false
        var truncates = false
        when (disp) {
            0 -> if (exists) { truncates = true; lastAction = 0 } else { creates = true; lastAction = 2 }
            1 -> if (exists) { lastAction = 1 } else return notFound(f)
            2 -> if (exists) return St.OBJECT_NAME_COLLISION else { creates = true; lastAction = 2 }
            3 -> if (exists) { lastAction = 1 } else { creates = true; lastAction = 2 }
            4 -> if (exists) { truncates = true; lastAction = 3 } else return notFound(f)
            5 -> if (exists) { truncates = true; lastAction = 3 } else { creates = true; lastAction = 2 }
            else -> return St.INVALID_PARAMETER
        }
        if (isDir && (fileOnly || truncates)) return St.FILE_IS_A_DIRECTORY
        if (exists && !isDir && dirOnly) return St.NOT_A_DIRECTORY
        if (f == paths.root && (delOnClose || truncates)) return St.ACCESS_DENIED
        if (ro && (creates || truncates || write || delOnClose)) return St.ACCESS_DENIED
        val dir = if (creates) dirOnly else isDir
        if (creates) {
            if (f.parentFile?.isDirectory != true) return St.OBJECT_PATH_NOT_FOUND
            if (dir && !f.mkdir()) return St.ACCESS_DENIED
        }
        val handle = if (dir) null else f.open(write || creates || truncates)  // open(true) cria se não existe
        try {
            if (truncates && handle != null) handle.setLength(0)
        } catch (e: IOException) {
            handle?.close()
            throw e
        }
        val fid = newId(nextFid) { it in files }
        nextFid = fid + 1
        files[fid] = OpenFile(fid, t.tid, curUid, f, path, handle, dir, write, delOnClose)
        chainFid = fid
        if (!dir) srv.log("SMB: $ip abriu ${displayPath(path)}")
        return St.SUCCESS
    }

    private fun notFound(f: VFile): Int =
        if (f.parentFile?.isDirectory == true) St.OBJECT_NAME_NOT_FOUND else St.OBJECT_PATH_NOT_FOUND

    private fun displayPath(path: String): String = "\\" + path.replace('/', '\\').trim('\\')

    /** No encadeamento AndX o FID do comando seguinte pode vir 0xFFFF: usa o recém-aberto. */
    private fun fileFor(fid: Int): OpenFile? = files[fid] ?: if (chainFid >= 0) files[chainFid] else null

    private fun closeFile(of: OpenFile) {
        try {
            of.handle?.close()
        } catch (e: IOException) {
        }
        if (of.deleteOnClose && of.file != paths.root) of.file.delete()
    }

    // ---------------------------------------------------------------- leitura/escrita

    private fun readAndX(k: Blk): Int {
        val p = k.p
        val fid = req.u16(p + 4)
        var offset = req.u32(p + 6)
        val low = req.u16(p + 10)
        val high = req.u16(p + 14)  // MaxCountHigh: metade baixa do Timeout
        if (k.wc >= 12) offset = offset or (req.u32(p + 20) shl 32)
        var count = low.toLong()
        if ((clientCaps and Cap.LARGE_READX) != 0 && high != 0xFFFF) count = count or (high.toLong() shl 16)
        val of = fileFor(fid) ?: return St.INVALID_HANDLE
        val h = of.handle ?: return St.INVALID_DEVICE_REQUEST
        if (offset < 0) return St.INVALID_PARAMETER
        val size = h.length()  // do arquivo aberto: nenhum metadado do VFile por leitura
        val n = if (offset >= size) 0 else minOf(count, size - offset, MAX_READ.toLong()).toInt()
        out.u8(12)
        andx()
        out.u16(0xFFFF)              // Available: -1 para arquivos
        out.u16(0)                   // DataCompactionMode
        out.u16(0)
        out.u16(n and 0xFFFF)        // DataLength
        val doPos = out.pos
        out.u16(0)                   // DataOffset
        out.u32(n ushr 16)           // DataLengthHigh
        out.zeros(6)
        out.u16(minOf(n + 1, 0xFFFF))
        out.u8(0)                    // 1 byte de enchimento: dados em 60, como Windows/Samba
        out.set16(doPos, out.pos - 4)
        readData = ReadData(h, offset, n)
        stopChain = true
        return St.SUCCESS
    }

    private fun writeAndX(k: Blk): Int {
        val p = k.p
        val fid = req.u16(p + 4)
        var offset = req.u32(p + 6)
        val lenHigh = req.u16(p + 18)
        val lenLow = req.u16(p + 20)
        val dataOff = req.u16(p + 22)
        if (k.wc >= 14) offset = offset or (req.u32(p + 24) shl 32)
        var n = lenLow
        val big = lenLow or (lenHigh shl 16)
        if (lenHigh != 0 && dataOff + big <= req.len) n = big
        if (dataOff < 32 || dataOff + n > req.len) return St.INVALID_PARAMETER
        val of = fileFor(fid) ?: return St.INVALID_HANDLE
        val h = of.handle ?: return St.INVALID_DEVICE_REQUEST
        if (!of.writable || ro) return St.ACCESS_DENIED
        if (offset < 0) return St.INVALID_PARAMETER
        if (n > 0) {
            h.write(offset, req.b, dataOff, n)
            srv.statsRef.bytesWritten.addAndGet(n.toLong())
        }
        out.u8(6)
        andx()
        out.u16(n and 0xFFFF)
        out.u16(0xFFFF)  // Available
        out.u16(n ushr 16)
        out.u16(0)
        out.u16(0)
        return St.SUCCESS
    }

    private fun close(k: Blk): Int {
        val of = fileFor(req.u16(k.p)) ?: return St.INVALID_HANDLE
        // LastWriteTime (k.p + 2) é ignorado: VFile não muda datas.
        files.remove(of.fid)
        closeFile(of)
        return emptyReply()
    }

    /** VHandle não tem sync: as escritas já vão direto para o arquivo; só confere o FID. */
    private fun flush(k: Blk): Int {
        val fid = req.u16(k.p)
        if (fid != 0xFFFF && !files.containsKey(fid)) return St.INVALID_HANDLE
        return emptyReply()
    }

    private fun locking(k: Blk): Int {
        val p = k.p
        fileFor(req.u16(p + 4)) ?: return St.INVALID_HANDLE
        val type = req.u8(p + 6)
        val unlocks = req.u16(p + 12)
        val locks = req.u16(p + 14)
        if ((type and 0x02) != 0 && unlocks == 0 && locks == 0) {  // liberação de oplock: sem resposta
            noReply = true
            return St.SUCCESS
        }
        out.u8(2)
        andx()
        out.u16(0)
        return St.SUCCESS
    }

    // ---------------------------------------------------------------- apagar, pastas, renomear

    /** String com BufferFormat 0x04 (DELETE, RENAME, CREATE_DIRECTORY...). */
    private fun fmtStr(q0: Int, end: Int): Pair<String, Int>? {
        if (q0 >= end || req.u8(q0) != 0x04) return null
        var q = q0 + 1
        if (uni && q % 2 != 0) q++
        return req.str(q, end, uni)
    }

    private fun delete(k: Blk): Int {
        val (name, _) = fmtStr(k.b, k.end) ?: return St.INVALID_PARAMETER
        if (name.any { it == '*' || it == '<' || it == '>' || it == '"' }) return St.OBJECT_NAME_INVALID
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (!f.exists()) return notFound(f)
        if (f.isDirectory) return St.FILE_IS_A_DIRECTORY
        if (ro || !f.delete()) return St.ACCESS_DENIED
        return emptyReply()
    }

    private fun mkdir(k: Blk): Int {
        val (name, _) = fmtStr(k.b, k.end) ?: return St.INVALID_PARAMETER
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (f.exists()) return St.OBJECT_NAME_COLLISION
        if (ro) return St.ACCESS_DENIED
        if (f.parentFile?.isDirectory != true) return St.OBJECT_PATH_NOT_FOUND
        if (!f.mkdir()) return St.ACCESS_DENIED
        return emptyReply()
    }

    private fun rmdir(k: Blk): Int {
        val (name, _) = fmtStr(k.b, k.end) ?: return St.INVALID_PARAMETER
        val f = paths.resolve(name) ?: return St.ACCESS_DENIED
        if (!f.exists()) return notFound(f)
        if (!f.isDirectory) return St.NOT_A_DIRECTORY
        if (ro || f == paths.root) return St.ACCESS_DENIED
        if (f.listFiles()?.isNotEmpty() == true) return St.DIRECTORY_NOT_EMPTY
        if (!f.delete()) return St.ACCESS_DENIED
        return emptyReply()
    }

    private fun rename(k: Blk): Int {
        val (oldName, q) = fmtStr(k.b, k.end) ?: return St.INVALID_PARAMETER
        val (newName, _) = fmtStr(q, k.end) ?: return St.INVALID_PARAMETER
        val src = paths.resolve(oldName) ?: return St.ACCESS_DENIED
        if (!src.exists()) return notFound(src)
        if (ro || src == paths.root) return St.ACCESS_DENIED
        // destino: pasta mãe resolvida + último nome exatamente como veio
        val np = newName.replace('/', '\\').trimEnd('\\')
        val i = np.lastIndexOf('\\')
        val parent = paths.resolve(if (i >= 0) np.substring(0, i) else "") ?: return St.ACCESS_DENIED
        val leaf = if (i >= 0) np.substring(i + 1) else np
        if (leaf.isEmpty() || leaf == "." || leaf == ".." || hasWildcards(leaf)) return St.OBJECT_NAME_INVALID
        if (!parent.isDirectory) return St.OBJECT_PATH_NOT_FOUND
        val list = parent.listFiles() ?: emptyList()
        // Sem caminho canônico no SAF: "o mesmo item" = mesma pasta e mesmo nome real (da listagem).
        val sameDir = src.parentFile?.path?.equals(parent.path, ignoreCase = true) == true
        val srcReal = if (!sameDir) null else
            list.firstOrNull { it.name == src.name } ?: list.firstOrNull { it.name.equals(src.name, ignoreCase = true) }
        val exact = list.firstOrNull { it.name == leaf }
        if (exact != null) return if (exact == srcReal) emptyReply() else St.OBJECT_NAME_COLLISION
        val clash = list.firstOrNull { it.name.equals(leaf, ignoreCase = true) }
        val ok = if (clash != null) {
            if (srcReal == null || clash != srcReal) return St.OBJECT_NAME_COLLISION
            // só muda maiúsculas/minúsculas: passa por um nome temporário
            val tmp = parent.child(".ps2ponte-${System.nanoTime()}")
            when {
                !srcReal.renameTo(tmp) -> false
                tmp.renameTo(parent.child(leaf)) -> true
                else -> {
                    tmp.renameTo(srcReal)  // desfaz
                    false
                }
            }
        } else {
            (srcReal ?: src).renameTo(parent.child(leaf))
        }
        if (!ok) return St.ACCESS_DENIED
        return emptyReply()
    }

    companion object {
        const val MAX_BUFFER = 16644
        const val MAX_READ = 16 * 1024 * 1024 - 256
        const val READ_CHUNK = 256 * 1024
        const val DOMAIN = "WORKGROUP"
        const val SERVER_NAME = "PS2PONTE"
        const val VOLUME_SERIAL = 0x50533250
        const val MAXIMUM_ALLOWED = 0x02000000
        // FILE_WRITE_DATA | FILE_APPEND_DATA | GENERIC_ALL | GENERIC_WRITE
        const val WRITE_ACCESS = 0x00000002 or 0x00000004 or 0x10000000 or 0x40000000
    }
}

