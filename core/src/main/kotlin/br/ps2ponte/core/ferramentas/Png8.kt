package br.ps2ponte.core.ferramentas

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Converte uma imagem (pixels ARGB) em PNG indexado de 8 bits (até 256 cores), o formato que o OPL
 * e o RiptOPL leem sem problemas nas capas. Quantização por "median cut" sobre um histograma de 15 bits
 * (rápido e com pouca memória: roda bem em celular fraco). Transparência vira a cor 0 (via tRNS).
 */
object Png8 {
    fun codificar(argb: IntArray, largura: Int, altura: Int): ByteArray {
        // Histograma 5-5-5 dos pixels opacos
        val hist = IntArray(32768)
        var transparente = false
        for (p in argb) {
            if ((p ushr 24) < 128) { transparente = true; continue }
            hist[q15(p)]++
        }
        val maxCores = if (transparente) 255 else 256
        val paleta = medianCut(hist, maxCores)
        // Mapa 15 bits -> índice (só para as cores usadas)
        val mapa = IntArray(32768) { -1 }
        val base = if (transparente) 1 else 0
        val linha = largura + 1
        val dados = ByteArray(linha * altura)
        for (y in 0 until altura) {
            dados[y * linha] = 0 // filtro "None"
            for (x in 0 until largura) {
                val p = argb[y * largura + x]
                val idx = if ((p ushr 24) < 128) 0 else {
                    val c = q15(p)
                    var m = mapa[c]
                    if (m < 0) { m = maisProxima(paleta, c) + base; mapa[c] = m }
                    m
                }
                dados[y * linha + 1 + x] = idx.toByte()
            }
        }

        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(largura); writeInt(altura); write(8); write(3); write(0); write(0); write(0) }
        }.toByteArray()
        bloco(d, "IHDR", ihdr)
        val plte = ByteArray((paleta.size + base) * 3)
        for (i in paleta.indices) {
            val c = paleta[i]
            plte[(i + base) * 3] = (c ushr 16).toByte()
            plte[(i + base) * 3 + 1] = (c ushr 8).toByte()
            plte[(i + base) * 3 + 2] = c.toByte()
        }
        bloco(d, "PLTE", plte)
        if (transparente) bloco(d, "tRNS", byteArrayOf(0))
        val def = Deflater(9)
        try {
            def.setInput(dados)
            def.finish()
            val z = ByteArrayOutputStream(dados.size / 2 + 64)
            val buf = ByteArray(16384)
            while (!def.finished()) z.write(buf, 0, def.deflate(buf))
            bloco(d, "IDAT", z.toByteArray())
        } finally {
            def.end()
        }
        bloco(d, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Tamanho para caber em [maxL]x[maxA] mantendo a proporção (nunca aumenta). */
    fun caber(l: Int, a: Int, maxL: Int, maxA: Int): Pair<Int, Int> {
        if (l <= maxL && a <= maxA) return l to a
        val f = minOf(maxL.toDouble() / l, maxA.toDouble() / a)
        return maxOf(1, (l * f).toInt()) to maxOf(1, (a * f).toInt())
    }

    /** Já é PNG indexado (tipo de cor 3) de 8 bits? Olha só o cabeçalho. */
    fun jaEh8Bits(png: ByteArray): Boolean =
        png.size > 26 && png[0] == 0x89.toByte() && png[1] == 'P'.code.toByte() && png[24].toInt() == 8 && png[25].toInt() == 3

    private fun q15(p: Int) = ((p ushr 19) and 31 shl 10) or ((p ushr 11) and 31 shl 5) or ((p ushr 3) and 31)

    private fun bloco(d: DataOutputStream, tipo: String, dados: ByteArray) {
        d.writeInt(dados.size)
        val t = tipo.toByteArray(Charsets.US_ASCII)
        d.write(t)
        d.write(dados)
        val crc = CRC32()
        crc.update(t)
        crc.update(dados)
        d.writeInt(crc.value.toInt())
    }

    /** Caixa do median cut: cores (15 bits) com contagem. */
    private class Caixa(val cores: IntArray) {
        fun faixa(canal: Int): Int {
            var mn = 31; var mx = 0
            for (c in cores) { val v = (c ushr (canal * 5)) and 31; if (v < mn) mn = v; if (v > mx) mx = v }
            return mx - mn
        }
    }

    private fun medianCut(hist: IntArray, maxCores: Int): IntArray {
        val usadas = (0 until 32768).filter { hist[it] > 0 }.toIntArray()
        if (usadas.isEmpty()) return intArrayOf(0)
        val caixas = mutableListOf(Caixa(usadas))
        while (caixas.size < maxCores) {
            // Divide a caixa com mais pixels que ainda tem mais de uma cor
            val alvo = caixas.filter { it.cores.size > 1 }.maxByOrNull { cx -> cx.cores.sumOf { hist[it].toLong() } } ?: break
            val canal = (0..2).maxByOrNull { alvo.faixa(it) }!!
            val ordenadas = alvo.cores.sortedBy { (it ushr (canal * 5)) and 31 }
            val total = ordenadas.sumOf { hist[it].toLong() }
            var acc = 0L
            var corte = 1
            for (i in ordenadas.indices) {
                acc += hist[ordenadas[i]]
                if (acc * 2 >= total) { corte = (i + 1).coerceIn(1, ordenadas.size - 1); break }
            }
            caixas.remove(alvo)
            caixas += Caixa(ordenadas.subList(0, corte).toIntArray())
            caixas += Caixa(ordenadas.subList(corte, ordenadas.size).toIntArray())
        }
        // Cor média ponderada de cada caixa (RGB de 8 bits)
        return IntArray(caixas.size) { i ->
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (c in caixas[i].cores) {
                val w = hist[c].toLong()
                r += ((c ushr 10) and 31) * w; g += ((c ushr 5) and 31) * w; b += (c and 31) * w; n += w
            }
            val f = { v: Long -> ((v * 255) / (31 * n)).toInt() }
            (f(r) shl 16) or (f(g) shl 8) or f(b)
        }
    }

    private fun maisProxima(paleta: IntArray, c15: Int): Int {
        val r = ((c15 ushr 10) and 31) * 255 / 31
        val g = ((c15 ushr 5) and 31) * 255 / 31
        val b = (c15 and 31) * 255 / 31
        var melhor = 0
        var dist = Int.MAX_VALUE
        for (i in paleta.indices) {
            val p = paleta[i]
            val dr = ((p ushr 16) and 255) - r
            val dg = ((p ushr 8) and 255) - g
            val db = (p and 255) - b
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < dist) { dist = d; melhor = i }
        }
        return melhor
    }
}
