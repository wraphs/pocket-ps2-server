package br.ps2ponte.core.smb

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Arrays
import java.util.Calendar

/** Comandos SMB1 que o servidor conhece. */
internal object Cmd {
    const val CREATE_DIRECTORY = 0x00
    const val DELETE_DIRECTORY = 0x01
    const val CLOSE = 0x04
    const val FLUSH = 0x05
    const val DELETE = 0x06
    const val RENAME = 0x07
    const val QUERY_INFORMATION = 0x08
    const val CHECK_DIRECTORY = 0x10
    const val LOCKING_ANDX = 0x24
    const val TRANSACTION = 0x25
    const val ECHO = 0x2B
    const val OPEN_ANDX = 0x2D
    const val READ_ANDX = 0x2E
    const val WRITE_ANDX = 0x2F
    const val TRANSACTION2 = 0x32
    const val FIND_CLOSE2 = 0x34
    const val TREE_DISCONNECT = 0x71
    const val NEGOTIATE = 0x72
    const val SESSION_SETUP_ANDX = 0x73
    const val LOGOFF_ANDX = 0x74
    const val TREE_CONNECT_ANDX = 0x75
    const val QUERY_INFORMATION_DISK = 0x80
    const val NT_CREATE_ANDX = 0xA2
    const val NT_CANCEL = 0xA4
    const val NONE = 0xFF

    /** Comandos AndX (podem vir encadeados). */
    val ANDX = setOf(LOCKING_ANDX, OPEN_ANDX, READ_ANDX, WRITE_ANDX, SESSION_SETUP_ANDX, LOGOFF_ANDX, TREE_CONNECT_ANDX, NT_CREATE_ANDX)
}

/** Capacidades do servidor (campo Capabilities do NEGOTIATE). */
internal object Cap {
    const val UNICODE = 0x0004
    const val LARGE_FILES = 0x0008
    const val NT_SMBS = 0x0010
    const val RPC_REMOTE_APIS = 0x0020
    const val STATUS32 = 0x0040
    const val NT_FIND = 0x0200
    const val LARGE_READX = 0x4000
    const val LARGE_WRITEX = 0x8000
}

internal object Flags2 {
    const val KNOWS_LONG_NAMES = 0x0001
    const val EAS = 0x0002
    const val IS_LONG_NAME = 0x0040
    const val STATUS32 = 0x4000
    const val UNICODE = 0x8000
}

/**
 * NTSTATUS usados nas respostas. Os valores 0x00XX0002 já estão no formato DOS
 * (classe ERRSRV no byte baixo, código na palavra alta), como no MS-CIFS.
 */
internal object St {
    const val SUCCESS = 0
    const val NOT_IMPLEMENTED = 0xC0000002L.toInt()
    const val INVALID_HANDLE = 0xC0000008L.toInt()
    const val INVALID_PARAMETER = 0xC000000DL.toInt()
    const val NO_SUCH_FILE = 0xC000000FL.toInt()
    const val INVALID_DEVICE_REQUEST = 0xC0000010L.toInt()
    const val ACCESS_DENIED = 0xC0000022L.toInt()
    const val BUFFER_TOO_SMALL = 0xC0000023L.toInt()
    const val OBJECT_NAME_INVALID = 0xC0000033L.toInt()
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034L.toInt()
    const val OBJECT_NAME_COLLISION = 0xC0000035L.toInt()
    const val OBJECT_PATH_NOT_FOUND = 0xC000003AL.toInt()
    const val DISK_FULL = 0xC000007FL.toInt()
    const val FILE_IS_A_DIRECTORY = 0xC00000BAL.toInt()
    const val NOT_SUPPORTED = 0xC00000BBL.toInt()
    const val UNEXPECTED_IO_ERROR = 0xC00000E9L.toInt()
    const val DIRECTORY_NOT_EMPTY = 0xC0000101L.toInt()
    const val NOT_A_DIRECTORY = 0xC0000103L.toInt()
    const val INVALID_LEVEL = 0xC0000148L.toInt()
    const val NO_MORE_FILES = 0x80000006L.toInt()
    const val SMB_BAD_TID = 0x00050002
    const val SMB_BAD_COMMAND = 0x00160002
}

/** Converte NTSTATUS para classe/código DOS (para clientes sem FLAGS2_32BIT_STATUS). */
internal fun toDos(st: Int): Int {
    if (st == 0 || (st ushr 24) == 0) return st
    val (cls, code) = when (st) {
        St.NOT_IMPLEMENTED, St.INVALID_DEVICE_REQUEST -> 1 to 1      // ERRbadfunc
        St.NO_SUCH_FILE, St.OBJECT_NAME_NOT_FOUND -> 1 to 2          // ERRbadfile
        St.OBJECT_PATH_NOT_FOUND -> 1 to 3                           // ERRbadpath
        St.ACCESS_DENIED, St.FILE_IS_A_DIRECTORY -> 1 to 5           // ERRnoaccess
        St.INVALID_HANDLE -> 1 to 6                                  // ERRbadfid
        St.NO_MORE_FILES -> 1 to 18                                  // ERRnofiles
        St.OBJECT_NAME_COLLISION -> 1 to 80                          // ERRfilexists
        St.INVALID_PARAMETER, St.BUFFER_TOO_SMALL -> 1 to 87         // ERRinvalidparam
        St.OBJECT_NAME_INVALID -> 1 to 123                           // ERRinvalidname
        St.INVALID_LEVEL -> 1 to 124                                 // ERRunknownlevel
        St.DIRECTORY_NOT_EMPTY -> 1 to 145                           // ERRdirnotempty
        St.NOT_A_DIRECTORY -> 1 to 267                               // ERRbaddirectory
        St.NOT_SUPPORTED -> 2 to 0xFFFF                              // ERRSRV/ERRnosupport
        St.DISK_FULL -> 3 to 39                                      // ERRHRD/ERRdiskfull
        St.UNEXPECTED_IO_ERROR -> 3 to 31                            // ERRHRD/ERRgeneral
        else -> 2 to 1                                               // ERRSRV/ERRerror
    }
    return cls or (code shl 16)
}

/** Pedido malformado (campo fora da mensagem). */
internal class Malformed : RuntimeException()

/** Mensagem SMB recebida (começa no cabeçalho 0xFF 'SMB'); leituras little-endian com checagem de limite. */
internal class Msg(var b: ByteArray, var len: Int = 0) {
    fun u8(o: Int): Int {
        if (o < 0 || o >= len) throw Malformed()
        return b[o].toInt() and 0xFF
    }

    fun u16(o: Int): Int {
        if (o < 0 || o + 2 > len) throw Malformed()
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    fun i32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
    fun u32(o: Int): Long = i32(o).toLong() and 0xFFFFFFFFL
    fun u64(o: Int): Long = u32(o) or (u32(o + 4) shl 32)

    /** Lê uma string terminada em NUL até [end]; devolve o texto e a posição depois do terminador. */
    fun str(o: Int, end: Int, unicode: Boolean): Pair<String, Int> {
        val e = minOf(end, len)
        if (o >= e) return "" to o
        var p = o
        return if (unicode) {
            while (p + 1 < e && (b[p].toInt() or b[p + 1].toInt()) != 0) p += 2
            String(b, o, p - o, Charsets.UTF_16LE) to minOf(p + 2, e)
        } else {
            while (p < e && b[p].toInt() != 0) p++
            oemDecode(b, o, p - o) to minOf(p + 1, e)
        }
    }
}

/** Buffer de saída little-endian que cresce sob demanda (reaproveitado: escreve todos os bytes). */
internal class Out(size: Int) {
    var b = ByteArray(size)
    var pos = 0

    fun need(n: Int) {
        if (pos + n > b.size) b = b.copyOf(maxOf(b.size * 2, pos + n))
    }

    fun u8(v: Int) {
        need(1); b[pos++] = v.toByte()
    }

    fun u16(v: Int) {
        need(2); b[pos] = v.toByte(); b[pos + 1] = (v ushr 8).toByte(); pos += 2
    }

    fun u32(v: Int) {
        need(4)
        b[pos] = v.toByte(); b[pos + 1] = (v ushr 8).toByte(); b[pos + 2] = (v ushr 16).toByte(); b[pos + 3] = (v ushr 24).toByte()
        pos += 4
    }

    fun u32(v: Long) = u32(v.toInt())

    fun u64(v: Long) {
        u32(v.toInt()); u32((v ushr 32).toInt())
    }

    fun bytes(src: ByteArray, off: Int = 0, n: Int = src.size - off) {
        need(n); System.arraycopy(src, off, b, pos, n); pos += n
    }

    fun zeros(n: Int) {
        need(n); Arrays.fill(b, pos, pos + n, 0); pos += n
    }

    fun set8(at: Int, v: Int) {
        b[at] = v.toByte()
    }

    fun set16(at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
    }

    fun set32(at: Int, v: Int) {
        set16(at, v); set16(at + 2, v ushr 16)
    }

    /** Completa com zeros até (pos - base) ser múltiplo de [n]. */
    fun align(base: Int, n: Int) {
        while ((pos - base) % n != 0) u8(0)
    }

    /** String no formato do pedido (UTF-16LE ou OEM), com terminador opcional. */
    fun text(s: String, unicode: Boolean, nul: Boolean = true) {
        bytes(enc(s, unicode))
        if (nul) {
            if (unicode) u16(0) else u8(0)
        }
    }
}

internal fun enc(s: String, unicode: Boolean): ByteArray =
    if (unicode) s.toByteArray(Charsets.UTF_16LE) else s.toByteArray(Charsets.UTF_8)

/** Strings OEM: UTF-8 (o PS2 só repassa os bytes); se não for UTF-8 válido, Latin-1. */
internal fun oemDecode(b: ByteArray, off: Int, n: Int): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(b, off, n)).toString()
} catch (e: CharacterCodingException) {
    String(b, off, n, Charsets.ISO_8859_1)
}

private const val EPOCH_DIFF_MS = 11_644_473_600_000L

/** Milissegundos Unix -> FILETIME (100 ns desde 1601). */
internal fun fileTime(ms: Long): Long = if (ms <= 0L) 0L else (ms + EPOCH_DIFF_MS) * 10_000L

internal fun fromFileTime(ft: Long): Long = ft / 10_000L - EPOCH_DIFF_MS

/** Data e hora no formato DOS (hora local). */
internal fun dosDateTime(ms: Long): Pair<Int, Int> {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    val y = c.get(Calendar.YEAR)
    if (y < 1980) return 0x21 to 0
    val date = ((y - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
    val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
    return date to time
}
