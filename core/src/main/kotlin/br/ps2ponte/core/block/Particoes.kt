package br.ps2ponte.core.block

/**
 * Descobre os sistemas de arquivos das partições de um disco (MBR, GPT ou sem tabela),
 * pelo nome OEM do setor de boot: "EXFAT", "NTFS", "FAT32", "FAT"...
 * Serve para avisar quando o pendrive em modo bloco está num formato que o PS2 não lê.
 */
object Particoes {
    fun sistemas(bd: BlockDevice): List<String> {
        val s0 = setor(bd, 0) ?: return emptyList()
        fs(s0)?.let { return listOf(it) } // disco sem tabela de partições
        if (s0[510] != 0x55.toByte() || s0[511] != 0xAA.toByte()) return emptyList()
        val inicios = ArrayList<Long>()
        for (i in 0 until 4) {
            val e = 446 + i * 16
            val tipo = s0[e + 4].toInt() and 0xFF
            if (tipo == 0) continue
            if (tipo == 0xEE) return gpt(bd)
            inicios += u32(s0, e + 8)
        }
        return inicios.mapNotNull { setor(bd, it)?.let(::fs) }
    }

    private fun gpt(bd: BlockDevice): List<String> {
        val h = setor(bd, 1) ?: return emptyList()
        if (String(h, 0, 8, Charsets.US_ASCII) != "EFI PART") return emptyList()
        val tabela = u64(h, 72)
        val tamanho = u32(h, 84).toInt()
        if (tamanho <= 0 || tamanho > 512) return emptyList()
        val porSetor = 512 / tamanho
        val out = ArrayList<String>()
        for (i in 0 until 8) { // as primeiras partições bastam
            val sec = setor(bd, tabela + i / porSetor) ?: break
            val o = (i % porSetor) * tamanho
            val inicio = u64(sec, o + 32)
            if (inicio == 0L) continue
            setor(bd, inicio)?.let(::fs)?.let { out += it }
        }
        return out
    }

    private fun fs(b: ByteArray): String? {
        val oem = String(b, 3, 8, Charsets.US_ASCII).trim()
        return when {
            oem == "EXFAT" -> "EXFAT"
            oem == "NTFS" -> "NTFS"
            String(b, 82, 8, Charsets.US_ASCII).startsWith("FAT32") -> "FAT32"
            String(b, 54, 8, Charsets.US_ASCII).startsWith("FAT") -> "FAT"
            else -> null
        }
    }

    private fun setor(bd: BlockDevice, lba: Long): ByteArray? =
        if (lba < 0 || lba >= bd.sectorCount) null
        else try { ByteArray(512).also { bd.read(lba, 1, it, 0) } } catch (e: Exception) { null }

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)
}
