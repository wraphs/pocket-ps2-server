package br.ps2ponte.core.fs

/**
 * LZ4 "block" (sem o cabeçalho de frame), o formato dos blocos do ZSO.
 * Puro Kotlin: roda em qualquer Android, sem biblioteca nativa.
 */
object Lz4 {
    /** Descompacta [src] em [dst]; devolve quantos bytes escreveu. Lança se o bloco estiver corrompido. */
    fun descompactar(src: ByteArray, sOff: Int, sLen: Int, dst: ByteArray, dOff: Int, dLen: Int): Int {
        var i = sOff
        val fim = sOff + sLen
        var o = dOff
        val oFim = dOff + dLen
        while (i < fim) {
            val token = src[i++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) {
                var b: Int
                do { b = src[i++].toInt() and 0xFF; lit += b } while (b == 255)
            }
            if (lit > 0) {
                if (i + lit > fim || o + lit > oFim) throw java.io.IOException("LZ4: literal fora do bloco")
                System.arraycopy(src, i, dst, o, lit)
                i += lit
                o += lit
            }
            // Bloco cheio: o que sobrar é enchimento de alinhamento do ZSO
            if (i >= fim || o >= oFim) break
            val dist = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            var m = token and 15
            if (m == 15) {
                var b: Int
                do { b = src[i++].toInt() and 0xFF; m += b } while (b == 255)
            }
            m += 4
            var de = o - dist
            if (dist == 0 || de < dOff || o + m > oFim) throw java.io.IOException("LZ4: referência inválida")
            if (dist >= m) {
                System.arraycopy(dst, de, dst, o, m)
                o += m
            } else {
                // Sobreposta (padrão repetido): byte a byte
                repeat(m) { dst[o++] = dst[de++] }
            }
        }
        return o - dOff
    }

    /** Tamanho máximo do bloco compactado para [n] bytes de entrada. */
    fun limite(n: Int) = n + n / 255 + 16

    private const val HASH_BITS = 12

    /**
     * Compacta [len] bytes de [src] em [dst] (que deve ter [limite] de espaço); devolve o tamanho.
     * Guloso com uma tabela de hash pequena ([tabela], 4096 posições, reaproveitada entre chamadas):
     * rápido e leve, bom para celular fraco.
     */
    fun compactar(src: ByteArray, off: Int, len: Int, dst: ByteArray, tabela: IntArray = IntArray(1 shl HASH_BITS)): Int {
        java.util.Arrays.fill(tabela, -1)
        val fim = off + len
        val limiteMatch = fim - 12 // o último match precisa começar a 12 bytes do fim
        var i = off
        var ancora = off
        var o = 0
        if (len >= 13) {
            while (i < limiteMatch) {
                val v = le32(src, i)
                val h = (v * -1640531535) ushr (32 - HASH_BITS)
                val ref = tabela[h]
                tabela[h] = i
                if (ref < 0 || i - ref > 65535 || le32(src, ref) != v) {
                    i++
                    continue
                }
                // Estende o match (deixando os 5 últimos bytes como literais)
                var m = 4
                val maxM = fim - 5 - i
                while (m < maxM && src[ref + m] == src[i + m]) m++
                o = sequencia(src, ancora, i - ancora, i - ref, m, dst, o)
                i += m
                ancora = i
            }
        }
        // Literais finais
        val lit = fim - ancora
        dst[o++] = (if (lit >= 15) 0xF0 else lit shl 4).toByte()
        if (lit >= 15) o = comprimento(lit - 15, dst, o)
        System.arraycopy(src, ancora, dst, o, lit)
        return o + lit
    }

    private fun sequencia(src: ByteArray, litOff: Int, lit: Int, dist: Int, m: Int, dst: ByteArray, o0: Int): Int {
        var o = o0
        val ml = m - 4
        val token = (if (lit >= 15) 0xF0 else lit shl 4) or (if (ml >= 15) 0x0F else ml)
        dst[o++] = token.toByte()
        if (lit >= 15) o = comprimento(lit - 15, dst, o)
        System.arraycopy(src, litOff, dst, o, lit)
        o += lit
        dst[o++] = dist.toByte()
        dst[o++] = (dist ushr 8).toByte()
        if (ml >= 15) o = comprimento(ml - 15, dst, o)
        return o
    }

    private fun comprimento(n0: Int, dst: ByteArray, o0: Int): Int {
        var n = n0
        var o = o0
        while (n >= 255) { dst[o++] = 0xFF.toByte(); n -= 255 }
        dst[o++] = n.toByte()
        return o
    }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)
}
