package br.ps2ponte.core.exfat

import java.util.TimeZone

/** Estruturas e somas de verificação do exFAT (especificação da Microsoft, rev. 1.00). */
internal object ExFat {
    const val SECTOR = 512
    const val SECTOR_SHIFT = 9
    const val CLUSTER_SHIFT = 8 // 256 setores = 128 KiB
    const val SECTORS_PER_CLUSTER = 1 shl CLUSTER_SHIFT
    const val CLUSTER_BYTES = SECTOR shl CLUSTER_SHIFT
    const val ENTRY = 32
    const val NAME_CHARS_PER_ENTRY = 15
    const val MAX_NAME = 255

    const val ET_BITMAP = 0x81
    const val ET_UPCASE = 0x82
    const val ET_LABEL = 0x83
    const val ET_FILE = 0x85
    const val ET_STREAM = 0xC0
    const val ET_NAME = 0xC1

    const val ATTR_DIR = 0x10
    const val ATTR_ARCHIVE = 0x20

    /** Soma da região de boot: setores 0..10, sem VolumeFlags (106, 107) e PercentInUse (112). */
    fun bootChecksum(b: ByteArray, off: Int = 0): Int {
        var sum = 0
        for (i in 0 until 11 * SECTOR) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = ((sum ushr 1) or (sum shl 31)) + (b[off + i].toInt() and 0xFF)
        }
        return sum
    }

    /** SetChecksum de um conjunto de entradas (pula os bytes 2 e 3 da primeira). */
    fun setChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        for (i in 0 until len) {
            if (i == 2 || i == 3) continue
            sum = (((sum and 1) shl 15) + (sum ushr 1) + (b[off + i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /** NameHash: soma de 16 bits do nome em maiúsculas (UTF-16LE). */
    fun nameHash(name: String): Int {
        val up = UpCase.map
        var sum = 0
        for (ch in name) {
            val u = up[ch.code].code
            sum = (((sum and 1) shl 15) + (sum ushr 1) + (u and 0xFF)) and 0xFFFF
            sum = (((sum and 1) shl 15) + (sum ushr 1) + (u ushr 8)) and 0xFFFF
        }
        return sum
    }

    /** TableChecksum da tabela up-case. */
    fun tableChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        for (i in off until off + len) sum = ((sum ushr 1) or (sum shl 31)) + (b[i].toInt() and 0xFF)
        return sum
    }

    /** Nome em maiúsculas pelo mapeamento do volume (para ordenar e achar nomes repetidos). */
    fun upperName(name: String): String {
        val up = UpCase.map
        val c = CharArray(name.length) { up[name[it].code] }
        return String(c)
    }

    /** Quantas entradas de 32 bytes um arquivo/pasta com esse nome ocupa (85 + C0 + C1...). */
    fun entryCount(name: String) = 2 + (name.length + NAME_CHARS_PER_ENTRY - 1) / NAME_CHARS_PER_ENTRY

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    fun put64(b: ByteArray, o: Int, v: Long) {
        put32(b, o, v.toInt()); put32(b, o + 4, (v ushr 32).toInt())
    }

    fun get16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun get32(b: ByteArray, o: Int) = get16(b, o) or (get16(b, o + 2) shl 16)
    fun get64(b: ByteArray, o: Int) = (get32(b, o).toLong() and 0xFFFFFFFFL) or (get32(b, o + 4).toLong() shl 32)

    /**
     * Data/hora no formato do exFAT (igual ao DOS) em hora local, sem fuso (UtcOffset = 0).
     * Retorna (timestamp shl 8) or incremento de 10 ms (0..199).
     */
    fun timestamp(millisUtc: Long, tz: TimeZone): Long {
        val local = millisUtc + tz.getOffset(millisUtc)
        var days = Math.floorDiv(local, 86_400_000L)
        val msOfDay = Math.floorMod(local, 86_400_000L)
        // Dias desde 1970-01-01 -> data civil (algoritmo de H. Hinnant).
        days += 719_468
        val era = Math.floorDiv(days, 146_097L)
        val doe = days - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        var year = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        if (month <= 2) year++
        if (year < 1980) return dos(1980, 1, 1, 0, 0, 0, 0)
        if (year > 2107) return dos(2107, 12, 31, 23, 59, 58, 0)
        val secOfDay = (msOfDay / 1000).toInt()
        return dos(year.toInt(), month, day, secOfDay / 3600, secOfDay / 60 % 60, secOfDay % 60, (msOfDay % 1000).toInt())
    }

    private fun dos(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long {
        val ts = ((y - 1980) shl 25) or (mo shl 21) or (d shl 16) or (h shl 11) or (mi shl 5) or (s shr 1)
        val inc = (s and 1) * 100 + ms / 10
        return ((ts.toLong() and 0xFFFFFFFFL) shl 8) or inc.toLong()
    }

    /**
     * Grava o conjunto de entradas de um arquivo ou pasta em b[off...] e devolve o tamanho em bytes.
     * Sem cadeia contígua no FAT: NoFatChain = 0, o FAT tem a cadeia completa.
     */
    fun writeEntrySet(
        b: ByteArray, off: Int, name: String, isDir: Boolean,
        firstCluster: Int, dataLength: Long, time: Long,
    ): Int {
        val n = entryCount(name)
        val len = n * ENTRY
        java.util.Arrays.fill(b, off, off + len, 0)
        // 0x85: arquivo/pasta
        b[off] = ET_FILE.toByte()
        b[off + 1] = (n - 1).toByte()
        put16(b, off + 4, if (isDir) ATTR_DIR else ATTR_ARCHIVE)
        val ts = (time ushr 8).toInt()
        val inc = (time and 0xFF).toInt()
        put32(b, off + 8, ts)  // criação
        put32(b, off + 12, ts) // modificação
        put32(b, off + 16, ts) // último acesso
        b[off + 20] = inc.toByte()
        b[off + 21] = inc.toByte()
        // 0xC0: extensão de stream
        val s = off + ENTRY
        b[s] = ET_STREAM.toByte()
        b[s + 1] = 0x01 // AllocationPossible = 1, NoFatChain = 0
        b[s + 3] = name.length.toByte()
        put16(b, s + 4, nameHash(name))
        put64(b, s + 8, dataLength)  // ValidDataLength
        put32(b, s + 20, firstCluster)
        put64(b, s + 24, dataLength) // DataLength
        // 0xC1: nome, 15 caracteres por entrada
        var ci = 0
        for (e in 2 until n) {
            val p = off + e * ENTRY
            b[p] = ET_NAME.toByte()
            var k = 0
            while (k < NAME_CHARS_PER_ENTRY && ci < name.length) {
                put16(b, p + 2 + k * 2, name[ci].code)
                k++; ci++
            }
        }
        put16(b, off + 2, setChecksum(b, off, len))
        return len
    }
}
