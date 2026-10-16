package br.ps2ponte.core.smb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Arrays

/**
 * Cliente SMB do OPL reescrito em Kotlin, campo a campo, para os testes.
 *
 * - Funções `smb_*`: menu, ps2sdk `iop/network/smbman/src/smb.c`.
 * - Funções `cdvdman_*`: jogo, OPL `modules/iopcore/cdvdman/smb.c` (com USE_CUSTOM_RECV).
 *
 * [buf] imita `SMB_buf` (4 bytes de sessão + união de 4096 + 128 bytes). Os deslocamentos usados em
 * [w8]/[r16]... são os das structs empacotadas de `smb.h`, relativos ao início do cabeçalho SMB.
 */
class OplClient(port: Int) : Closeable {
    companion object {
        const val MAX_SMB_BUF = 4096
        const val MAX_SMB_BUF_HDR = 128
        const val SMB_MAGIC = 0x424d53ffL
        const val EIO = 5
        const val EACCES = 13
        const val EINVAL = 22

        // ps2sdk iomanX
        const val O_RDONLY = 0x0001
        const val O_WRONLY = 0x0002
        const val O_RDWR = 0x0003
        const val O_CREAT = 0x0200
        const val O_TRUNC = 0x0400

        const val CAP_UNICODE = 0x4L
        const val CAP_LARGE_FILES = 0x8L
        const val CAP_NT_SMBS = 0x10L
        const val CAP_STATUS32 = 0x40L
        const val CAP_LARGE_READX = 0x4000L
        const val CAP_LARGE_WRITEX = 0x8000L

        const val STATUS_ACCESS_DENIED = 0xC0000022L.toInt()
        const val STATUS_LOGON_FAILURE = 0xC000006DL.toInt()

        const val TRANS2_FIND_FIRST2 = 0x01
        const val TRANS2_FIND_NEXT2 = 0x02
        const val SMB_COM_CREATE_DIRECTORY = 0x00
        const val SMB_COM_DELETE_DIRECTORY = 0x01

        const val NO_PASSWORD = -1
        const val HASHED_PASSWORD = 1
    }

    private val sock = Socket()
    private val ins: DataInputStream
    private val os by lazy { sock.getOutputStream() }

    init {
        sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
        sock.tcpNoDelay = true
        sock.soTimeout = 15_000
        ins = DataInputStream(BufferedInputStream(sock.getInputStream()))
    }

    val buf = ByteArray(4 + MAX_SMB_BUF + MAX_SMB_BUF_HDR)

    // server_specs_t
    var Capabilities = 0L
    var SecurityMode = 0
    var PasswordType = 0
    var MaxBufferSize = 0L
    var MaxMpxCount = 0
    var SessionKey = 0L
    val EncryptionKey = ByteArray(8)
    var PrimaryDomainServerName = ""
    val rawDomain = ByteArray(64)  // o cdvdman copia 64 bytes crus

    /** Tamanho (sem os 4 bytes de sessão) da última resposta recebida. */
    var lastLength = 0

    /** Bytes da última resposta do READ_ANDX do jogo (com os 4 bytes de sessão). */
    var lastReadMessage = ByteArray(0)

    override fun close() = sock.close()

    // ------------------------------------------------------------ acesso a campos (relativo a SMB_buf.smb)

    fun w8(o: Int, v: Int) {
        buf[4 + o] = v.toByte()
    }

    fun w16(o: Int, v: Int) {
        w8(o, v); w8(o + 1, v ushr 8)
    }

    fun w32(o: Int, v: Long) {
        w16(o, v.toInt()); w16(o + 2, (v ushr 16).toInt())
    }

    fun wb(o: Int, b: ByteArray) = System.arraycopy(b, 0, buf, 4 + o, b.size)

    fun r8(o: Int): Int = buf[4 + o].toInt() and 0xFF
    fun r16(o: Int): Int = r8(o) or (r8(o + 1) shl 8)
    fun r16s(o: Int): Int = r16(o).toShort().toInt()
    fun r32(o: Int): Long = r16(o).toLong() or (r16(o + 2).toLong() shl 16)
    fun r64(o: Int): Long = r32(o) or (r32(o + 4) shl 32)

    /** `Eclass | (Ecode << 16)` com os `short` do C (extensão de sinal). */
    fun status(): Int = r16s(5) or (r16s(7) shl 16)
    fun magicOk() = r32(0) == SMB_MAGIC

    private fun ZERO_PKT_ALIGNED(size: Int) = Arrays.fill(buf, 4, 4 + ((size + 3) and 3.inv()), 0)

    private fun nb_SetSessionMessage(size: Int) {
        buf[0] = 0; buf[1] = (size ushr 16).toByte(); buf[2] = (size ushr 8).toByte(); buf[3] = size.toByte()
    }

    private fun nb_GetSessionMessageLength(): Int =
        ((buf[1].toInt() and 0xFF) shl 16) or ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)

    private fun GetSMBServerReply(shdrlen: Int, payload: ByteArray?, payloadOff: Int, rhdrlen: Int): Int {
        val total = nb_GetSessionMessageLength() + 4
        if (shdrlen == 0) {
            os.write(buf, 0, total)
        } else {
            os.write(buf, 0, shdrlen + 4)
            os.write(payload!!, payloadOff, total - shdrlen - 4)
        }
        do {
            ins.readFully(buf, 0, 4)
        } while (buf[0].toInt() != 0)
        val len = nb_GetSessionMessageLength()
        val size = if (rhdrlen == 0) len else rhdrlen
        check(size <= buf.size - 4) { "resposta de $size bytes estouraria o buffer de ${buf.size - 4} bytes do PS2" }
        ins.readFully(buf, 4, size)
        lastLength = len
        return len
    }

    // ------------------------------------------------------------ strings (setStringField/getStringField)

    val unicode get() = (Capabilities and CAP_UNICODE) != 0L

    /** `char *` do C: os bytes do nome (UTF-8 aqui). */
    fun setStringField(o: Int, s: String): Int {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return if (unicode) {
            for (i in bytes.indices) {
                w8(o + 2 * i, bytes[i].toInt()); w8(o + 2 * i + 1, 0)
            }
            w8(o + 2 * bytes.size, 0); w8(o + 2 * bytes.size + 1, 0)
            2 * bytes.size + 2
        } else {
            wb(o, bytes); w8(o + bytes.size, 0)
            bytes.size + 1
        }
    }

    fun getStringField(o: Int, inbytes: Int): String {
        val out = java.io.ByteArrayOutputStream()
        if (unicode) {  // utf16ToAscii
            var p = o
            var processed = 0
            while (inbytes == 0 || processed < inbytes) {
                val w = r8(p) or (r8(p + 1) shl 8)
                if (w == 0) break
                if (w in 0xD800 until 0xDC00) {
                    out.write('?'.code); p += 4; processed += 4
                } else {
                    out.write(if (w > 128) '?'.code else w); p += 2; processed += 2
                }
            }
            return String(out.toByteArray(), Charsets.ISO_8859_1)
        }
        var p = o
        while ((inbytes == 0 || p - o < inbytes) && r8(p) != 0) out.write(r8(p++))
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------ smbman (menu)

    fun smb_NegotiateProtocol(): Long {
        val dialect = "NT LM 0.12"
        repeat(3) {
            ZERO_PKT_ALIGNED(36)
            w32(0, SMB_MAGIC); w8(4, 0x72); w8(9, 0x08); w16(10, 0x0001 or 0x4000)
            w16(33, dialect.length + 1 + 1)
            w8(35, 0x02)
            setRaw(36, dialect)
            nb_SetSessionMessage(36 + dialect.length + 1)
            val r = GetSMBServerReply(0, null, 0, 0)
            if (r <= 0 || !magicOk() || r16s(5) != 0 || r8(32) != 17) return@repeat
            val caps = r32(52) and (CAP_LARGE_WRITEX or CAP_LARGE_READX or CAP_UNICODE or CAP_LARGE_FILES or CAP_STATUS32)
            Capabilities = r32(52)
            SecurityMode = if ((r8(35) and 0x01) != 0) 1 else 0
            PasswordType = if ((r8(35) and 0x02) != 0) 1 else 0
            MaxBufferSize = r32(40)
            MaxMpxCount = r16(36)
            SessionKey = r32(48)
            val kl = r8(66)
            System.arraycopy(buf, 4 + 69, EncryptionKey, 0, kl)
            PrimaryDomainServerName = getStringField(69 + kl, 0)
            return caps
        }
        return -1
    }

    private fun setRaw(o: Int, s: String) {
        val b = s.toByteArray(Charsets.ISO_8859_1); wb(o, b); w8(o + b.size, 0)
    }

    /** Hash/resposta fictícios: o servidor aceita qualquer senha, o que importa é o formato. */
    private fun fakeResponse(seed: Int) = ByteArray(24) { (it * 7 + seed).toByte() }

    private fun AddPassword(password: String?, passwordType: Int, authType: Int, ansiLenOff: Int, uniLenOff: Int, o: Int): Int {
        var len = 0
        if (password != null && passwordType != NO_PASSWORD) {
            if (PasswordType == 1) {
                len = 24
                if (authType == 0) w16(ansiLenOff, len) else w16(uniLenOff, len)
                wb(o, fakeResponse(authType))
            } else {
                val b = password.toByteArray(Charsets.ISO_8859_1)
                len = b.size.coerceIn(1, 14)
                w16(ansiLenOff, len)
                wb(o, b.copyOf(len))
            }
        } else if (SecurityMode == 0) {
            len = 1; w16(ansiLenOff, 1); w8(o, 0)
        }
        return len
    }

    fun smb_SessionSetupAndX(user: String, password: String?, passwordType: Int, capabilities: Long): Int {
        var authType = 1  // NTLM_AUTH
        while (true) {
            ZERO_PKT_ALIGNED(61)
            val useUnicode = unicode
            w32(0, SMB_MAGIC); w8(4, 0x73); w8(9, 0x08)
            w16(10, 0x0001 or 0x4000 or (if (useUnicode) 0x8000 else 0))
            w8(32, 13); w8(33, 0xFF)
            w16(37, 0xFFFF)                                  // CLIENT_MAX_BUFFER_SIZE
            w16(39, if (MaxMpxCount >= 2) 2 else MaxMpxCount)
            w16(41, 1)
            w32(43, SessionKey)
            w32(55, capabilities)
            var offset = 0
            var passwordlen = 0
            if (SecurityMode == 1) {
                passwordlen = AddPassword(password, passwordType, authType, 47, 49, 61)
                offset += passwordlen
            }
            if (useUnicode && (passwordlen and 1) == 0) { w8(61 + offset, 0); offset++ }
            offset += setStringField(61 + offset, user)
            offset += setStringField(61 + offset, PrimaryDomainServerName)
            if (useUnicode && (offset and 1) != 0) { w8(61 + offset, 0); offset++ }
            offset += setStringField(61 + offset, "PlayStation 2")
            if (useUnicode && (offset and 1) != 0) { w8(61 + offset, 0); offset++ }
            offset += setStringField(61 + offset, "SMBMAN")
            w16(59, offset)
            nb_SetSessionMessage(61 + offset)
            if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
            if (!magicOk()) return -EIO
            when (status()) {
                0 -> {}
                STATUS_LOGON_FAILURE -> {
                    if (SecurityMode == 1 && authType == 1) { authType = 0; continue }
                    return -EACCES
                }
                else -> return -EIO
            }
            return r16(28)
        }
    }

    fun smb_TreeConnectAndX(uid: Int, shareName: String, password: String?, passwordType: Int): Int {
        ZERO_PKT_ALIGNED(43)
        w32(0, SMB_MAGIC); w8(4, 0x75); w8(9, 0x08)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid)
        w8(32, 4); w8(33, 0xFF)
        var offset = 0
        val passwordlen: Int
        if (SecurityMode == 0) {
            passwordlen = AddPassword(password, passwordType, 1, 39, 39, 43)
        } else {
            passwordlen = 1; w16(39, 1)
        }
        offset += passwordlen
        if (unicode && (passwordlen and 1) == 0) { w8(43 + offset, 0); offset++ }
        offset += setStringField(43 + offset, shareName)
        wb(43 + offset, "?????\u0000".toByteArray(Charsets.ISO_8859_1)); offset += 6
        w16(41, offset)
        nb_SetSessionMessage(43 + offset)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        when (status()) {
            0 -> {}
            STATUS_LOGON_FAILURE -> return -EACCES
            else -> return -EIO
        }
        return r16(24)
    }

    data class ShareEntry(val name: String, val comment: String)

    /** smb_NetShareEnum: maxEntries 0 só conta. */
    fun smb_NetShareEnum(uid: Int, tid: Int, out: MutableList<ShareEntry>, index: Int, maxEntries: Int): Int {
        ZERO_PKT_ALIGNED(63)
        w32(0, SMB_MAGIC); w8(4, 0x25); w16(28, uid); w16(24, tid)
        w8(32, 14)
        w16(33, 19); w16(51, 19)
        w16(37, 1024)
        w16(39, MAX_SMB_BUF - 1024)
        w16(53, 76)
        w16(57, 95)
        w16(61, 32)
        wb(63, "\\PIPE\\LANMAN\u0000\u0000\u0000WrLeh\u0000B13BWz\u0000\u0001\u0000 \u001f".toByteArray(Charsets.ISO_8859_1))
        nb_SetSessionMessage(63 + 32)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        when (status()) {
            0 -> {}
            STATUS_ACCESS_DENIED -> return -EACCES
            else -> return -EIO
        }
        val po = r16(41)
        if (r16((po / 2) * 2) != 0) return -EIO                 // u16buff[ParamOffset / 2]
        val available = r16s(((po + 6) / 2) * 2)                  // s16buff[(ParamOffset + 6) / 2]
        val data = r16(47)
        var p = data
        var count = 0
        for (i in 0 until available) {
            val len = cstrlen(p)
            val padding = if ((len + 1 + 2) % 16 != 0) 16 - ((len + 1) % 16) else 0
            if (r16(p + len + 1 + padding - 2) == 0) {            // tipo "Directory Tree"
                if (maxEntries > 0) {
                    if (count < maxEntries && i >= index) {
                        count++
                        out += ShareEntry(cstr(p), cstr(data + r16(p + len + 1 + padding)))
                    }
                } else {
                    count++
                }
            }
            p += len + 1 + padding + 4
        }
        return count
    }

    private fun cstrlen(o: Int): Int {
        var n = 0
        while (r8(o + n) != 0) n++
        return n
    }

    private fun cstr(o: Int) = String(buf, 4 + o, cstrlen(o), Charsets.ISO_8859_1)

    /** smb_GetShareList do smb_fio.c: IPC$, conta, lista um por um, desconecta. */
    fun smb_GetShareList(uid: Int, serverIp: String): List<ShareEntry>? {
        val tid = smb_TreeConnectAndX(uid, "\\\\$serverIp\\IPC$", null, 0)
        if (tid < 0) return null
        val all = ArrayList<ShareEntry>()
        val n = smb_NetShareEnum(uid, tid, all, 0, 0)
        if (n < 0) return null
        val result = ArrayList<ShareEntry>()
        for (i in 0 until n) {
            val one = ArrayList<ShareEntry>()
            if (smb_NetShareEnum(uid, tid, one, i, 1) < 0) return null
            one.filter { it.name != "IPC$" }.forEach { result += it }
        }
        if (smb_TreeDisconnect(uid, tid) < 0) return null
        return result
    }

    class PathInformation {
        var Created = 0L
        var LastAccess = 0L
        var LastWrite = 0L
        var Change = 0L
        var FileAttributes = 0L
        var AllocationSize = 0L
        var EndOfFile = 0L
        var LinkCount = 0L
        var DeletePending = 0
        var IsDirectory = 0
    }

    fun smb_QueryPathInformation(uid: Int, tid: Int, info: PathInformation, path: String): Int {
        var queryType = 0x101
        while (true) {
            ZERO_PKT_ALIGNED(65)
            w32(0, SMB_MAGIC); w8(4, 0x32); w8(9, 0x10)
            w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
            w16(28, uid); w16(24, tid)
            w8(32, 15)
            w8(59, 1)
            w16(61, 0x05)
            w16(53, 68)
            w16(37, 256)
            w16(39, MAX_SMB_BUF - 256)
            w16(68, queryType)
            w32(70, 0)
            val pathLen = setStringField(74, path)
            val total = 2 + 4 + pathLen
            w16(33, total); w16(51, total)
            w16(63, 3 + total)
            w16(57, 68 + total)
            nb_SetSessionMessage(68 + total)
            if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
            if (!magicOk()) return -EIO
            when (status()) {
                0 -> {}
                STATUS_ACCESS_DENIED -> return -EACCES
                else -> return -EIO
            }
            val d = r16(47)
            if (queryType == 0x101) {
                info.Created = r64(d); info.LastAccess = r64(d + 8); info.LastWrite = r64(d + 16); info.Change = r64(d + 24)
                info.FileAttributes = r32(d + 32)
                queryType = 0x102
                continue
            }
            info.AllocationSize = r64(d); info.EndOfFile = r64(d + 8); info.LinkCount = r32(d + 16)
            info.DeletePending = r8(d + 20); info.IsDirectory = r8(d + 21)
            return 0
        }
    }

    class SearchInfo {
        var SID = 0
        var EOS = 0
        val fileInfo = PathInformation()
        var FileName = ""
    }

    fun smb_FindFirstNext2(uid: Int, tid: Int, path: String?, cmd: Int, info: SearchInfo): Int {
        ZERO_PKT_ALIGNED(65)
        w32(0, SMB_MAGIC); w8(4, 0x32); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid); w16(24, tid)
        w8(32, 15)
        w8(59, 1)
        w16(61, cmd and 0xFF)
        val po = (65 + 3) and 3.inv()
        w16(53, po)
        w16(37, 256)
        w16(39, MAX_SMB_BUF - 256)
        for (i in 65 until po) w8(i, 0)
        val total: Int
        if (cmd == TRANS2_FIND_FIRST2) {
            w16(po, 0x01 or 0x02 or 0x04 or 0x10 or 0x20)
            w16(po + 2, 1)
            w16(po + 4, 0x02 or 0x04)            // CLOSE_SEARCH_IF_EOS | RESUME_SEARCH
            w16(po + 6, 0x104)                   // SMB_FIND_FILE_BOTH_DIRECTORY_INFO
            w32(po + 8, 0)
            total = 12 + setStringField(po + 12, path!!)
        } else {
            w16(po, info.SID)
            w16(po + 2, 1)
            w16(po + 4, 0x104)
            w32(po + 6, 0)
            w16(po + 10, 0x02 or 0x04 or 0x08)   // + CONTINUE_SEARCH
            total = 12 + setStringField(po + 12, "")
        }
        w16(33, total); w16(51, total)
        w16(63, 3 + total)
        nb_SetSessionMessage(po + total)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        when (status()) {
            0 -> {}
            STATUS_ACCESS_DENIED -> return -EACCES
            else -> return -EIO
        }
        val pp: Int
        if (cmd == TRANS2_FIND_FIRST2) {
            pp = r16(41)
            info.SID = r16(pp)
        } else {
            pp = r16(41) - 2
        }
        val d = r16(47)
        info.EOS = r16(pp + 4)
        if (r16(pp + 2) == 0) return -EINVAL
        val fi = info.fileInfo
        fi.Created = r64(d + 8); fi.LastAccess = r64(d + 16); fi.LastWrite = r64(d + 24); fi.Change = r64(d + 32)
        fi.FileAttributes = r32(d + 56)
        fi.IsDirectory = if ((fi.FileAttributes and 0x10) != 0L) 1 else 0
        fi.AllocationSize = r64(d + 48)
        fi.EndOfFile = r64(d + 40)
        info.FileName = getStringField(d + 94, r32(d + 60).toInt())
        return 0
    }

    /** smb_dopen + smb_dread até dar erro, como o OPL lista uma pasta. */
    fun listDir(uid: Int, tid: Int, path: String): List<SearchInfo>? {
        val pi = PathInformation()
        if (smb_QueryPathInformation(uid, tid, pi, path) < 0) return null
        if ((pi.FileAttributes and 0x10) == 0L) return null
        val name = if (path.endsWith("\\")) "$path*" else "$path\\*"
        val out = ArrayList<SearchInfo>()
        var sid = -1
        while (true) {
            val info = SearchInfo()
            val r = if (sid == -1) {
                smb_FindFirstNext2(uid, tid, name, TRANS2_FIND_FIRST2, info).also { if (it >= 0) sid = info.SID }
            } else {
                info.SID = sid
                smb_FindFirstNext2(uid, tid, null, TRANS2_FIND_NEXT2, info)
            }
            if (r < 0) break
            out += info
            check(out.size < 10_000) { "busca sem fim" }
        }
        return out
    }

    fun smb_NTCreateAndX(uid: Int, tid: Int, filename: String, filesize: LongArray, mode: Int): Int {
        ZERO_PKT_ALIGNED(83)
        w32(0, SMB_MAGIC); w8(4, 0xA2); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid); w16(24, tid)
        w8(32, 24); w8(33, 0xFF)
        val rw = (mode and O_RDWR) == O_RDWR || (mode and O_WRONLY) != 0
        w32(48, if (rw) 0x2019fL else 0x20089L)
        w32(60, if (rw) 0x80L else 0x01L)
        w32(64, 0x01)
        var disp = 0
        if ((mode and O_CREAT) != 0) disp = disp or 0x02
        disp = if ((mode and O_TRUNC) != 0) disp or 0x04 else disp or 0x01
        if (disp == 0x06) disp = 0x05
        w32(68, disp.toLong())
        w32(76, 2)
        w8(80, 0x03)
        var offset = 0
        if (unicode) { w8(83, 0); offset++ }
        val nameLength = setStringField(83 + offset, filename)
        w16(38, nameLength)
        offset += nameLength
        w16(81, offset)
        nb_SetSessionMessage(83 + offset + 1)  // o C manda 1 byte a mais
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        when (status()) {
            0 -> {}
            STATUS_ACCESS_DENIED -> return -EACCES
            else -> return -EIO
        }
        filesize[0] = r64(88)
        return r16(38)
    }

    fun smb_OpenAndX(uid: Int, tid: Int, filename: String, filesize: LongArray, mode: Int): Int {
        if ((Capabilities and CAP_NT_SMBS) != 0L) return smb_NTCreateAndX(uid, tid, filename, filesize, mode)
        ZERO_PKT_ALIGNED(65)
        w32(0, SMB_MAGIC); w8(4, 0x2D); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid); w16(24, tid)
        w8(32, 15); w8(33, 0xFF)
        val rw = (mode and O_RDWR) == O_RDWR || (mode and O_WRONLY) != 0
        w16(39, if (rw) 0x02 else 0x00)
        w16(43, if (rw) 0x80 else 0x01)
        var co = 0
        if ((mode and O_CREAT) != 0) co = co or 0x10
        co = if ((mode and O_TRUNC) != 0) co or 0x02 else co or 0x01
        w16(49, co)
        var offset = 0
        if (unicode) { w8(65, 0); offset++ }
        offset += setStringField(65 + offset, filename)
        w16(63, offset)
        nb_SetSessionMessage(65 + offset + 1)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        when (status()) {
            0 -> {}
            STATUS_ACCESS_DENIED -> return -EACCES
            else -> return -EIO
        }
        var r = r16(37)
        val info = PathInformation()
        if (smb_QueryPathInformation(uid, tid, info, filename) >= 0) filesize[0] = info.EndOfFile else r = -1
        return r
    }

    fun smb_ReadAndX(uid: Int, tid: Int, fid: Int, fileoffset: Long, readbuf: ByteArray, ptr: Int, nbytes: Int): Int {
        ZERO_PKT_ALIGNED(59)
        w32(0, SMB_MAGIC); w16(10, 0x4000); w8(4, 0x2E)
        w16(28, uid); w16(24, tid)
        w8(32, 12); w8(33, 0xFF)
        w16(37, fid)
        w32(39, fileoffset and 0xFFFFFFFFL)
        w32(53, (fileoffset ushr 32) and 0xFFFFFFFFL)
        w16(43, nbytes)
        w16(47, nbytes ushr 16)
        nb_SetSessionMessage(59)
        if (GetSMBServerReply(0, null, 0, 59) <= 0) return -EIO
        if (!magicOk()) return -EIO
        if (status() != 0) return -EIO
        val padding = r16(45) - 59
        if (padding > 0) ins.readFully(buf, 4 + 59, padding)
        val dataLength = ((r32(47) shl 16) or r16(43).toLong()).toInt()
        if (dataLength > 0) ins.readFully(readbuf, ptr, dataLength)
        // O smbman lê exatamente cabeçalho + enchimento + dados: sobras atrapalhariam a próxima resposta.
        check(lastLength == r16(45) + dataLength) { "READ_ANDX com ${lastLength - r16(45) - dataLength} bytes sobrando" }
        return dataLength
    }

    fun smb_ReadFile(uid: Int, tid: Int, fid: Int, fileoffset: Long, readbuf: ByteArray, nbytes: Int): Int {
        var remaining = nbytes
        var ptr = 0
        var pos = fileoffset
        while (remaining > 0) {
            val toRead = minOf(remaining, 0xFFFF)
            val r = smb_ReadAndX(uid, tid, fid, pos, readbuf, ptr, toRead)
            if (r <= 0) return r
            pos += r; ptr += r; remaining -= r
        }
        return nbytes
    }

    fun smb_WriteAndX(uid: Int, tid: Int, fid: Int, fileoffset: Long, writebuf: ByteArray, ptr: Int, nbytes: Int): Int {
        val padding = 1
        ZERO_PKT_ALIGNED(63 + padding)
        w32(0, SMB_MAGIC); w16(10, 0x4000); w8(4, 0x2F)
        w16(28, uid); w16(24, tid)
        w8(32, 14); w8(33, 0xFF)
        w16(37, fid)
        w32(39, fileoffset and 0xFFFFFFFFL)
        w32(57, (fileoffset ushr 32) and 0xFFFFFFFFL)
        w16(47, 0x0001)
        w16(55, 63 + padding)
        w16(49, nbytes)
        w16(53, nbytes)
        w16(51, nbytes ushr 16)
        w16(61, nbytes + padding)
        nb_SetSessionMessage(63 + padding + nbytes)
        if (GetSMBServerReply(63 + padding, writebuf, ptr, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        if (status() != 0) return -EIO
        return nbytes
    }

    fun smb_WriteFile(uid: Int, tid: Int, fid: Int, fileoffset: Long, writebuf: ByteArray, nbytes: Int): Int {
        var remaining = nbytes
        var ptr = 0
        var pos = fileoffset
        while (remaining > 0) {
            val toWrite = minOf(remaining, 0xFFFF)
            val r = smb_WriteAndX(uid, tid, fid, pos, writebuf, ptr, toWrite)
            if (r <= 0) return r
            pos += r; ptr += r; remaining -= r
        }
        return nbytes
    }

    fun smb_Close(uid: Int, tid: Int, fid: Int): Int {
        ZERO_PKT_ALIGNED(41)
        w32(0, SMB_MAGIC); w8(4, 0x04); w8(9, 0x10); w16(10, 0x0001 or 0x4000)
        w16(28, uid); w16(24, tid)
        w8(32, 3)
        w16(33, fid)
        nb_SetSessionMessage(41)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        return if (status() != 0) -EIO else 0
    }

    fun smb_Delete(uid: Int, tid: Int, path: String): Int {
        ZERO_PKT_ALIGNED(38)
        w32(0, SMB_MAGIC); w8(4, 0x06); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid); w16(24, tid)
        w8(32, 1)
        w16(33, 0)
        w8(37, 0x04)
        val pathLen = setStringField(38, path)
        w16(35, pathLen + 1)
        nb_SetSessionMessage(38 + pathLen)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        return if (status() != 0) -EIO else 0
    }

    fun smb_ManageDirectory(uid: Int, tid: Int, path: String, cmd: Int): Int {
        ZERO_PKT_ALIGNED(36)
        w32(0, SMB_MAGIC); w8(4, cmd); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid); w16(24, tid)
        w8(35, 0x04)
        val pathLen = setStringField(36, path)
        w16(33, pathLen + 1)
        nb_SetSessionMessage(36 + pathLen)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        return when (status()) {
            0 -> 0
            STATUS_ACCESS_DENIED -> -EACCES
            else -> -EIO
        }
    }

    fun smb_Rename(uid: Int, tid: Int, oldPath: String, newPath: String): Int {
        ZERO_PKT_ALIGNED(37)
        w32(0, SMB_MAGIC); w8(4, 0x07); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, uid); w16(24, tid)
        w8(32, 1)
        w16(33, 0)
        var offset = 0
        w8(37 + offset, 0x04); offset++
        offset += setStringField(37 + offset, oldPath)
        w8(37 + offset, 0x04); offset++
        if (unicode) { w8(37 + offset, 0); offset++ }
        offset += setStringField(37 + offset, newPath)
        w16(35, offset)
        nb_SetSessionMessage(37 + offset)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        return when (status()) {
            0 -> 0
            STATUS_ACCESS_DENIED -> -EACCES
            else -> -EIO
        }
    }

    fun smb_TreeDisconnect(uid: Int, tid: Int): Int {
        ZERO_PKT_ALIGNED(35)
        w32(0, SMB_MAGIC); w8(4, 0x71)
        w16(28, uid); w16(24, tid)
        nb_SetSessionMessage(35)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        return if (status() != 0) -EIO else 0
    }

    fun smb_LogOffAndX(uid: Int): Int {
        ZERO_PKT_ALIGNED(39)
        w32(0, SMB_MAGIC); w8(4, 0x74)
        w16(28, uid)
        w8(32, 2); w8(33, 0xFF)
        nb_SetSessionMessage(39)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        return if (status() != 0) -EIO else 0
    }

    fun smb_Echo(echo: ByteArray): Int {
        ZERO_PKT_ALIGNED(37)
        w32(0, SMB_MAGIC); w8(4, 0x2B)
        w8(32, 1)
        w16(33, 1)
        wb(37, echo)
        w16(35, echo.size)
        nb_SetSessionMessage(37 + echo.size)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return -EIO
        if (!magicOk()) return -EIO
        if (status() != 0) return -EIO
        for (i in echo.indices) if (buf[4 + 37 + i] != echo[i]) return -EIO
        return 0
    }

    data class DiskInfo(val totalUnits: Int, val blocksPerUnit: Int, val blockSize: Int, val freeUnits: Int)

    fun smb_QueryInformationDisk(uid: Int, tid: Int): DiskInfo? {
        ZERO_PKT_ALIGNED(35)
        w32(0, SMB_MAGIC); w8(4, 0x80)
        w16(28, uid); w16(24, tid)
        nb_SetSessionMessage(35)
        if (GetSMBServerReply(0, null, 0, 0) <= 0) return null
        if (!magicOk() || status() != 0) return null
        return DiskInfo(r16(33), r16(35), r16(37), r16(39))
    }

    // ------------------------------------------------------------ cdvdman (jogo)

    var gameUid = 0
    var gameTid = 0
    var gamePasswordLen = 0
    val gamePassword = ByteArray(48)

    /** smb_NegotiateProtocol do cdvdman + SmbInitHashPassword (smbinit). */
    fun cdvdman_NegotiateProtocol(password: String): Long {
        val dialect = "NT LM 0.12"
        repeat(3) {
            ZERO_PKT_ALIGNED(36)
            w32(0, SMB_MAGIC); w8(4, 0x72); w8(9, 0x08); w16(10, 0x0001)  // sem 32BIT_STATUS
            w16(33, dialect.length + 2)
            w8(35, 0x02)
            setRaw(36, dialect)
            nb_SetSessionMessage(36 + dialect.length + 1)
            GetSMBServerReply(0, null, 0, 0)
            if (!magicOk() || r16s(5) != 0 || r8(32) != 17) return@repeat
            val caps = r32(52) and (CAP_LARGE_WRITEX or CAP_LARGE_READX or CAP_UNICODE or CAP_LARGE_FILES or CAP_STATUS32)
            Capabilities = r32(52)
            SecurityMode = if ((r8(35) and 0x01) != 0) 1 else 0
            PasswordType = if ((r8(35) and 0x02) != 0) 1 else 0
            MaxBufferSize = r32(40)
            MaxMpxCount = r16(36)
            SessionKey = r32(48)
            val kl = r8(66)
            System.arraycopy(buf, 4 + 69, EncryptionKey, 0, kl)
            System.arraycopy(buf, 4 + 69 + kl, rawDomain, 0, 64)
            // SmbInitHashPassword
            if (password.isNotEmpty()) {
                if (PasswordType == 1) {
                    gamePasswordLen = 24
                    System.arraycopy(fakeResponse(0), 0, gamePassword, 0, 24)
                    System.arraycopy(fakeResponse(1), 0, gamePassword, 24, 24)
                } else {
                    val b = password.toByteArray(Charsets.ISO_8859_1)
                    gamePasswordLen = b.size.coerceIn(1, 14)
                    System.arraycopy(b, 0, gamePassword, 0, gamePasswordLen)
                }
            }
            return caps
        }
        return -1
    }

    /** O cdvdman passa PrimaryDomainServerName (bytes crus) como string C. */
    private fun rawDomainCString(): String {
        var n = 0
        while (n < 64 && rawDomain[n].toInt() != 0) n++
        return String(rawDomain, 0, n, Charsets.ISO_8859_1)
    }

    fun cdvdman_SessionSetupAndX(user: String, capabilities: Long): Int {
        var authType = 1
        while (true) {
            ZERO_PKT_ALIGNED(61)
            val useUnicode = unicode
            w32(0, SMB_MAGIC); w8(4, 0x73); w8(9, 0x08)
            w16(10, 0x0001 or 0x4000 or (if (useUnicode) 0x8000 else 0))
            w8(32, 13); w8(33, 0xFF)
            w16(37, 8192)                                   // CLIENT_MAX_BUFFER_SIZE do jogo
            w16(39, if (MaxMpxCount >= 2) 2 else MaxMpxCount)
            w16(41, 1)
            w32(43, SessionKey)
            w32(55, capabilities)
            var offset = 0
            var passwordLen = 0
            if (SecurityMode == 1) {
                passwordLen = gamePasswordLen
                System.arraycopy(gamePassword, (authType shl 4) + (authType shl 3), buf, 4 + 61, passwordLen)
                if (authType == 0) w16(47, passwordLen) else w16(49, passwordLen)
                offset += passwordLen
            }
            if (useUnicode && (passwordLen and 1) == 0) { w8(61 + offset, 0); offset++ }
            offset += setStringField(61 + offset, user)
            offset += setStringField(61 + offset, rawDomainCString())
            if (useUnicode && (offset and 1) != 0) { w8(61 + offset, 0); offset++ }
            offset += setStringField(61 + offset, "PlayStation 2")
            if (useUnicode && (offset and 1) != 0) { w8(61 + offset, 0); offset++ }
            offset += setStringField(61 + offset, "SMBMAN")
            w16(59, offset)
            nb_SetSessionMessage(61 + offset)
            GetSMBServerReply(0, null, 0, 0)
            if (!magicOk()) return -1
            if (SecurityMode == 1 && status() == STATUS_LOGON_FAILURE && authType == 1) { authType = 0; continue }
            if ((r16s(5) or r16s(7)) != 0) return -1000
            gameUid = r16(28)
            return 1
        }
    }

    fun cdvdman_TreeConnectAndX(shareName: String): Int {
        ZERO_PKT_ALIGNED(43)
        w32(0, SMB_MAGIC); w8(4, 0x75); w8(9, 0x08)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, gameUid)
        w8(32, 4); w8(33, 0xFF)
        var offset = 0
        var passwordLen = 1
        if (SecurityMode == 0) {
            passwordLen = gamePasswordLen
            System.arraycopy(gamePassword, 24, buf, 4 + 43, passwordLen)
        }
        w16(39, passwordLen)
        offset += passwordLen
        if (unicode && (passwordLen and 1) == 0) { w8(43 + offset, 0); offset++ }
        offset += setStringField(43 + offset, shareName)
        wb(43 + offset, "?????\u0000".toByteArray(Charsets.ISO_8859_1)); offset += 6
        w16(41, offset)
        nb_SetSessionMessage(43 + offset)
        GetSMBServerReply(0, null, 0, 0)
        if (!magicOk()) return -1
        if ((r16s(5) or r16s(7)) != 0) return -1000
        gameTid = r16(24)
        return 1
    }

    /** smb_OpenAndX do jogo: só confere Eclass (bytes 5-6) e copia o FID. */
    fun cdvdman_OpenAndX(filename: String, fid: IntArray, write: Boolean): Int {
        ZERO_PKT_ALIGNED(65)
        w32(0, SMB_MAGIC); w8(4, 0x2D); w8(9, 0x10)
        w16(10, 0x0001 or 0x4000 or (if (unicode) 0x8000 else 0))
        w16(28, gameUid); w16(24, gameTid)
        w8(32, 15); w8(33, 0xFF)
        w16(39, if (write) 2 else 0)
        w16(43, if (write) 0x80 else 0x01)
        w16(49, 1)
        var offset = 0
        if (unicode) { w8(65, 0); offset++ }
        offset += setStringField(65 + offset, filename)
        w16(63, offset)
        nb_SetSessionMessage(65 + offset + 1)
        GetSMBServerReply(0, null, 0, 0)
        if (!magicOk()) return -1
        if (r16s(5) != 0) return -1000
        fid[0] = r16(37)
        return 1
    }

    /**
     * smb_ReadAndX do jogo com o recvfrom() modificado do SMSTCPIP: copia 63 bytes de cabeçalho,
     * pula para header[49] + 4 (só o byte baixo de DataOffset!) e o resto vai direto para readbuf.
     * Aqui a mensagem chega inteira; as condições conferidas são as mesmas do C.
     */
    fun cdvdman_ReadAndX(fid: Int, offsetlow: Long, offsethigh: Long, readbuf: ByteArray, ptr: Int, nbytes: Int): Int {
        ZERO_PKT_ALIGNED(59)
        w32(0, SMB_MAGIC); w16(10, 0x4000); w8(4, 0x2E)
        w16(28, gameUid); w16(24, gameTid)
        w8(32, 12); w8(33, 0xFF)
        w16(37, fid)
        w32(39, offsetlow)
        w32(53, offsethigh)
        w16(43, nbytes)
        w16(47, nbytes ushr 16)
        nb_SetSessionMessage(59)
        os.write(buf, 0, 59 + 4)
        var msg: ByteArray
        do {
            val head = ByteArray(4)
            ins.readFully(head)
            val len = ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            msg = head.copyOf(4 + len)
            ins.readFully(msg, 4, len)
        } while (msg[0].toInt() != 0)
        lastReadMessage = msg
        check(msg.size >= 63) { "resposta do READ com ${msg.size} bytes: o recvfrom do jogo precisa de 63" }
        System.arraycopy(msg, 0, buf, 0, 63)
        val index = (msg[49].toInt() and 0xFF) + 4
        val expected = msg.size
        var rcv = index
        val plen = minOf(nbytes, msg.size - index)
        if (plen > 0) System.arraycopy(msg, index, readbuf, ptr, plen)
        rcv += maxOf(plen, 0)
        check(rcv == expected) { "recvfrom: $rcv de $expected bytes (sobra ou falta)" }
        return ((r32(47) shl 16) or r16(43).toLong()).toInt()
    }

    fun cdvdman_ReadFile(fid: Int, offsetlow0: Long, offsethigh0: Long, readbuf: ByteArray, nbytes: Int): Int {
        var remaining = nbytes
        var ptr = 0
        var offsetlow = offsetlow0
        var offsethigh = offsethigh0
        while (remaining > 0) {
            val toRead = minOf(remaining, 8192)  // CLIENT_MAX_RECV_SIZE
            val r = cdvdman_ReadAndX(fid, offsetlow, offsethigh, readbuf, ptr, toRead)
            if (r <= 0) return r
            if (((offsetlow + r) and 0xFFFFFFFFL) < offsetlow) offsethigh++
            offsetlow = (offsetlow + r) and 0xFFFFFFFFL
            ptr += r
            remaining -= r
        }
        return nbytes
    }

    /** smb_ReadCD: lsn * 2048 em 32 bits e lsn >> 21 como OffsetHigh. */
    fun cdvdman_ReadCD(fid: Int, lsn: Long, nsectors: Int, readbuf: ByteArray): Int =
        cdvdman_ReadFile(fid, (lsn * 2048) and 0xFFFFFFFFL, lsn ushr 21, readbuf, nsectors * 2048)

    fun cdvdman_WriteAndX(fid: Int, offsetlow: Long, offsethigh: Long, writebuf: ByteArray, ptr: Int, nbytes: Int): Int {
        val padding = 1
        ZERO_PKT_ALIGNED(63 + padding)
        w32(0, SMB_MAGIC); w16(10, 0x4000); w8(4, 0x2F)
        w16(28, gameUid); w16(24, gameTid)
        w8(32, 14); w8(33, 0xFF)
        w16(37, fid)
        w32(39, offsetlow)
        w32(57, offsethigh)
        w16(47, 0x0001)
        w16(55, 63 + padding)
        w16(49, nbytes); w16(53, nbytes); w16(51, nbytes ushr 16)
        w16(61, nbytes + padding)
        nb_SetSessionMessage(63 + padding + nbytes)
        if (GetSMBServerReply(63 + padding, writebuf, ptr, 0) <= 0) return -EIO
        if (status() != 0) return -EIO
        return nbytes
    }

    fun cdvdman_Close(fid: Int): Int = smb_Close(gameUid, gameTid, fid)
}
