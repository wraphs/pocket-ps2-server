package br.ps2ponte.core.exfat

import br.ps2ponte.core.block.BlockDevice

/** Leitor exFAT mínimo, escrito à parte do gerador, para os testes conferirem o disco. */
class ExFatTestReader(private val dev: BlockDevice) {
    class Entry(
        val name: String,
        val isDir: Boolean,
        val attr: Int,
        val flags: Int,
        val firstCluster: Int,
        val dataLength: Long,
        val validLength: Long,
        val nameHash: Int,
        val setChecksumOk: Boolean,
        /** Posição (bytes) do conjunto de entradas dentro da pasta. */
        val offset: Int,
    )

    val mbr = sector(0)
    val partStart = u32(mbr, 454)
    val vbr = sector(partStart)
    val volumeLength = u64(vbr, 72)
    val fatOffset = u32(vbr, 80)
    val fatLength = u32(vbr, 84)
    val heapOffset = u32(vbr, 88)
    val clusterCount = u32(vbr, 92).toInt()
    val rootCluster = u32(vbr, 96).toInt()
    val spc = 1 shl (vbr[109].toInt() and 0xFF)
    val clusterBytes = spc * 512

    fun sector(lba: Long): ByteArray = ByteArray(512).also { dev.read(lba, 1, it, 0) }

    fun clusterLba(c: Int) = partStart + heapOffset + (c - 2).toLong() * spc

    fun cluster(c: Int): ByteArray = ByteArray(clusterBytes).also { dev.read(clusterLba(c), spc, it, 0) }

    fun fat(c: Int): Int {
        val s = sector(partStart + fatOffset + c / 128)
        return ExFat.get32(s, c % 128 * 4)
    }

    /** Cadeia a partir de [first] até 0xFFFFFFFF; falha se quebrar. */
    fun chain(first: Int): List<Int> {
        val out = ArrayList<Int>()
        var c = first
        while (true) {
            require(c in 2 until clusterCount + 2) { "cluster inválido $c" }
            require(out.size <= clusterCount) { "laço no FAT" }
            out.add(c)
            val n = fat(c)
            if (n == -1) return out
            c = n
        }
    }

    fun bytes(first: Int, length: Long): ByteArray {
        val out = ByteArray(length.toInt())
        var pos = 0
        for (c in chain(first)) {
            if (pos >= out.size) break
            val b = cluster(c)
            val n = minOf(b.size, out.size - pos)
            System.arraycopy(b, 0, out, pos, n)
            pos += n
        }
        return out
    }

    /** Entradas de arquivo/pasta de uma pasta (lê toda a cadeia). */
    fun dir(first: Int): List<Entry> {
        val raw = chain(first).map { cluster(it) }.reduce { a, b -> a + b }
        val out = ArrayList<Entry>()
        var o = 0
        while (o < raw.size) {
            val t = raw[o].toInt() and 0xFF
            if (t == 0) break
            if (t != ExFat.ET_FILE) {
                o += 32; continue
            }
            val sec = raw[o + 1].toInt() and 0xFF
            val len = (sec + 1) * 32
            val sumOk = ExFat.get16(raw, o + 2) == ExFat.setChecksum(raw, o, len)
            val s = o + 32
            require((raw[s].toInt() and 0xFF) == ExFat.ET_STREAM) { "sem stream extension" }
            val nameLen = raw[s + 3].toInt() and 0xFF
            val sb = StringBuilder()
            var e = 2
            while (sb.length < nameLen) {
                val p = o + e * 32
                require((raw[p].toInt() and 0xFF) == ExFat.ET_NAME) { "sem file name" }
                for (k in 0 until 15) if (sb.length < nameLen) sb.append(ExFat.get16(raw, p + 2 + k * 2).toChar())
                e++
            }
            require(e == sec + 1) { "SecondaryCount errado em $sb" }
            val attr = ExFat.get16(raw, o + 4)
            out.add(
                Entry(
                    sb.toString(), attr and ExFat.ATTR_DIR != 0, attr, raw[s + 1].toInt() and 0xFF,
                    ExFat.get32(raw, s + 20), ExFat.get64(raw, s + 24), ExFat.get64(raw, s + 8),
                    ExFat.get16(raw, s + 4), sumOk, o,
                )
            )
            o += len
        }
        return out
    }

    /** Entradas especiais da raiz: tipo -> (FirstCluster, DataLength, campo do byte 4). */
    fun rootSpecial(): Map<Int, Triple<Int, Long, Int>> {
        val raw = cluster(rootCluster)
        val out = HashMap<Int, Triple<Int, Long, Int>>()
        var o = 0
        while (o < raw.size && raw[o].toInt() != 0) {
            val t = raw[o].toInt() and 0xFF
            if (t == ExFat.ET_BITMAP || t == ExFat.ET_UPCASE || t == ExFat.ET_LABEL) {
                out[t] = Triple(ExFat.get32(raw, o + 20), ExFat.get64(raw, o + 24), ExFat.get32(raw, o + 4))
            }
            o += 32
        }
        return out
    }

    companion object {
        fun u32(b: ByteArray, o: Int) = ExFat.get32(b, o).toLong() and 0xFFFFFFFFL
        fun u64(b: ByteArray, o: Int) = ExFat.get64(b, o)
    }
}
