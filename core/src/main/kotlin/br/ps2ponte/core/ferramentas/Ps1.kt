package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.VHandle
import java.io.IOException

/**
 * Jogos de PS1: folha CUE (inclusive a do Redump, com um .bin por faixa), conversão CUE/BIN ⇄ VCD do POPStarter
 * e leitura do código do jogo de imagens com setores crus de 2352 bytes.
 *
 * VCD (formato do cue2pops 2.x): cabeçalho de 1 MiB com a tabela de faixas, seguido dos setores crus.
 * Cabeçalho: descritores de 10 bytes (A0 em 0, A1 em 10, A2 em 20, faixas a partir de 30), tempos em BCD com
 * +2 s (o pregap do disco); "kHn " em 1024; número de setores (LE) em 1032 e 1036.
 */
object Ps1 {
    const val SETOR = 2352
    const val CABECALHO_VCD = 0x100000

    class Faixa(val numero: Int, val audio: Boolean, val modo: String, val arquivo: Int) {
        /** Setores absolutos na imagem juntada (todas as .bin em sequência); -1 = sem INDEX 00. */
        var index00 = -1L
        var index01 = -1L
    }

    class Cue(val arquivos: List<String>, val faixas: List<Faixa>, val pregaps: Int, val postgaps: Int)

    /**
     * Lê a folha CUE. [tamanho] devolve o tamanho em bytes de cada .bin citado (para achar o início de cada
     * arquivo na imagem juntada). Só aceita faixas de 2352 bytes (o que o POPS e o Ember leem).
     */
    fun lerCue(texto: String, tamanho: (String) -> Long): Cue {
        val arquivos = mutableListOf<String>()
        val inicios = mutableListOf<Long>()
        var acumulado = 0L
        val faixas = mutableListOf<Faixa>()
        var pregaps = 0
        var postgaps = 0
        val linhaFile = Regex("""^FILE\s+(?:"([^"]+)"|(\S+))\s+(\S+)""", RegexOption.IGNORE_CASE)
        for (bruta in texto.lines()) {
            val l = bruta.trim()
            if (l.isEmpty() || l.startsWith("REM", true)) continue
            val p = l.split(Regex("\\s+"))
            when (p[0].uppercase()) {
                "FILE" -> {
                    val m = linhaFile.find(l) ?: throw IOException("CUE: linha FILE inválida")
                    val nome = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    if (!m.groupValues[3].equals("BINARY", true)) throw IOException("CUE: só BINARY é suportado ($nome)")
                    inicios += acumulado
                    arquivos += nome
                    val t = tamanho(nome)
                    if (t < 0) throw IOException("CUE: arquivo não encontrado: $nome")
                    acumulado += t / SETOR
                }
                "TRACK" -> {
                    if (arquivos.isEmpty()) throw IOException("CUE: TRACK antes de FILE")
                    val modo = p.getOrNull(2)?.uppercase() ?: throw IOException("CUE: TRACK sem tipo")
                    if (modo != "AUDIO" && !modo.endsWith("/2352")) throw IOException("CUE: faixa $modo (precisa ser de 2352 bytes)")
                    faixas += Faixa(p[1].toInt(), modo == "AUDIO", modo, arquivos.size - 1)
                }
                "INDEX" -> {
                    val f = faixas.lastOrNull() ?: throw IOException("CUE: INDEX antes de TRACK")
                    val abs = inicios[f.arquivo] + msf(p[2])
                    when (p[1].toInt()) {
                        0 -> f.index00 = abs
                        1 -> f.index01 = abs
                    }
                }
                "PREGAP" -> pregaps++
                "POSTGAP" -> postgaps++
            }
        }
        if (faixas.isEmpty() || faixas.any { it.index01 < 0 }) throw IOException("CUE: faixas sem INDEX 01")
        return Cue(arquivos, faixas, pregaps, postgaps)
    }

    private fun msf(s: String): Long {
        val c = s.split(':').map { it.toInt() }
        return c[0] * 4500L + c[1] * 75L + c[2]
    }

    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)
    private fun deBcd(b: Int) = ((b ushr 4) and 0xF) * 10 + (b and 0xF)

    private fun poeMsf(h: ByteArray, at: Int, setor: Long) {
        h[at] = bcd((setor / 4500).toInt()).toByte()
        h[at + 1] = bcd(((setor % 4500) / 75).toInt()).toByte()
        h[at + 2] = bcd((setor % 75).toInt()).toByte()
    }

    private fun leMsf(h: ByteArray, at: Int): Long =
        deBcd(h[at].toInt() and 0xFF) * 4500L + deBcd(h[at + 1].toInt() and 0xFF) * 75L + deBcd(h[at + 2].toInt() and 0xFF)

    /** Faixa 1 precisa ser de dados MODE2/2352 (exigência do POPS). */
    fun prontoParaVcd(cue: Cue): String? = when {
        cue.faixas.first().modo != "MODE2/2352" -> "a faixa 1 é ${cue.faixas.first().modo}; o POPS precisa de MODE2/2352"
        cue.faixas.size > 99 -> "faixas demais"
        else -> null
    }

    /**
     * CUE/BIN (uma ou várias .bin) -> VCD. [bins] abre cada .bin na ordem da folha. Devolve o tamanho do VCD.
     * Corrige como o cue2pops as folhas do CDRWIN (um PREGAP sem dados na .bin: insere 2 s de silêncio).
     */
    fun paraVcd(cue: Cue, bins: List<VHandle>, saida: VHandle, progresso: (Long, Long) -> Unit): Long {
        prontoParaVcd(cue)?.let { throw IOException(it) }
        val setoresBin = bins.sumOf { it.length() / SETOR }
        val fixCdrwin = cue.pregaps == 1 && cue.postgaps == 0
        val setores = setoresBin + 150L * (cue.pregaps + cue.postgaps)
        val h = ByteArray(CABECALHO_VCD)
        val ultima = cue.faixas.last()
        val tipoUltima = if (ultima.audio) 0x01 else 0x41
        h[0] = 0x41; h[2] = 0xA0.toByte(); h[7] = 0x01; h[8] = 0x20
        h[10] = tipoUltima.toByte(); h[12] = 0xA1.toByte(); h[17] = bcd(ultima.numero).toByte()
        h[20] = tipoUltima.toByte(); h[22] = 0xA2.toByte()
        poeMsf(h, 27, setores + 150)
        cue.faixas.forEachIndexed { i, f ->
            val at = 30 + 10 * i
            h[at] = (if (f.audio) 0x01 else 0x41).toByte()
            h[at + 2] = bcd(f.numero).toByte()
            val extra = if (i != 0 && fixCdrwin) 150 else 0
            val i00 = if (f.index00 >= 0) f.index00 else f.index01
            poeMsf(h, at + 3, i00 + (if (i != 0) 150 else 0) + extra)
            poeMsf(h, at + 7, f.index01 + 150 + extra)
        }
        h[1024] = 0x6B; h[1025] = 0x48; h[1026] = 0x6E; h[1027] = 0x20
        for (at in listOf(1032, 1036)) {
            h[at] = setores.toByte(); h[at + 1] = (setores ushr 8).toByte(); h[at + 2] = (setores ushr 16).toByte(); h[at + 3] = (setores ushr 24).toByte()
        }
        saida.setLength(0)
        saida.write(0, h, 0, h.size)
        var pos = CABECALHO_VCD.toLong()
        // CDRWIN: 150 setores de silêncio antes do INDEX 00 (ou 01) da primeira faixa de áudio
        val primeiraAudio = cue.faixas.firstOrNull { it.audio }
        val pontoSilencio = if (fixCdrwin && primeiraAudio != null) (if (primeiraAudio.index00 >= 0) primeiraAudio.index00 else primeiraAudio.index01) * SETOR else -1L
        val total = setoresBin * SETOR
        val buf = ByteArray(SETOR * 256)
        var lido = 0L
        var silencioFeito = pontoSilencio < 0
        fun silencio() {
            val z = ByteArray(150 * SETOR)
            saida.write(pos, z, 0, z.size)
            pos += z.size
            silencioFeito = true
        }
        for (b in bins) {
            val tam = b.length() / SETOR * SETOR
            var p = 0L
            while (p < tam) {
                if (!silencioFeito && lido == pontoSilencio) silencio()
                var n = minOf(buf.size.toLong(), tam - p).toInt()
                if (!silencioFeito && lido + n > pontoSilencio) n = (pontoSilencio - lido).toInt()
                if (b.readFully(p, buf, 0, n) < n) throw IOException("BIN cortado")
                saida.write(pos, buf, 0, n)
                pos += n; p += n; lido += n
                progresso(lido, total)
            }
        }
        if (!silencioFeito && lido == pontoSilencio) silencio()
        return pos
    }

    /** Faixas de um VCD (para voltar a CUE/BIN, ex.: para o Ember). */
    fun cueDoVcd(h: ByteArray, nomeBin: String): String {
        if (h.size < 1040) throw IOException("VCD curto")
        val n = deBcd(h[17].toInt() and 0xFF)
        if (n < 1 || n > 99 || (h[2].toInt() and 0xFF) != 0xA0) throw IOException("não é um VCD do POPS")
        val sb = StringBuilder("FILE \"$nomeBin\" BINARY\r\n")
        for (i in 0 until n) {
            val at = 30 + 10 * i
            val audio = (h[at].toInt() and 0xFF) == 0x01
            val num = deBcd(h[at + 2].toInt() and 0xFF)
            val i01 = leMsf(h, at + 7) - 150
            val i00 = leMsf(h, at + 3) - (if (i != 0) 150 else 0)
            sb.append("  TRACK %02d %s\r\n".format(num, if (audio) "AUDIO" else "MODE2/2352"))
            if (i != 0 && i00 in 0 until i01) sb.append("    INDEX 00 ${msfTexto(i00)}\r\n")
            sb.append("    INDEX 01 ${msfTexto(maxOf(0, i01))}\r\n")
        }
        return sb.toString()
    }

    private fun msfTexto(s: Long) = "%02d:%02d:%02d".format(s / 4500, (s % 4500) / 75, s % 75)

    /**
     * Imagem crua (2352 bytes por setor, a partir de [base]) vista como setores de 2048 bytes de dados, para
     * ler o ISO 9660 (código do jogo). [modo2]: dados em +24 (MODE2 form 1); senão +16 (MODE1).
     */
    class Cozido(private val h: VHandle, private val base: Long, private val modo2: Boolean = true) : VHandle {
        private val setor = ByteArray(SETOR)
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(this) {
            var feito = 0
            while (feito < len) {
                val s = (pos + feito) / 2048
                val dentro = ((pos + feito) % 2048).toInt()
                if (h.readFully(base + s * SETOR, setor, 0, SETOR) < SETOR) break
                val q = minOf(len - feito, 2048 - dentro)
                System.arraycopy(setor, (if (modo2) 24 else 16) + dentro, buf, off + feito, q)
                feito += q
            }
            if (feito == 0) -1 else feito
        }
        override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) = throw IOException("só leitura")
        override fun length() = (h.length() - base) / SETOR * 2048
        override fun setLength(n: Long) = throw IOException("só leitura")
        override fun close() = h.close()
    }

    /** Código do jogo de um VCD ou de uma .bin crua (a faixa 1). */
    fun lerId(h: VHandle, vcd: Boolean): String? {
        val base = if (vcd) CABECALHO_VCD.toLong() else 0L
        // MODE2 é o comum no PS1; tenta MODE1 se não achar
        return IdJogo.ler(Cozido(h, base, true)) ?: IdJogo.ler(Cozido(h, base, false))
    }

    private val DISCO = Regex("""\s*[\(\[]\s*(?:disc|disco|disk|cd)\s*(\d+)(?:\s*(?:of|de)\s*\d+)?\s*[\)\]]""", RegexOption.IGNORE_CASE)
    private val DISCO_SOLTO = Regex("""[\s_\-]+(?:disc|disco|disk|cd)\s*_?(\d+)$""", RegexOption.IGNORE_CASE)

    /** ("Final Fantasy VII", 2) para "Final Fantasy VII (Disc 2)"; disco null se não for multidisco. */
    fun disco(nomeSemExtensao: String): Pair<String, Int?> {
        DISCO.find(nomeSemExtensao)?.let { m ->
            return nomeSemExtensao.removeRange(m.range).replace(Regex("\\s{2,}"), " ").trim() to m.groupValues[1].toInt()
        }
        DISCO_SOLTO.find(nomeSemExtensao)?.let { m -> return nomeSemExtensao.substring(0, m.range.first).trim() to m.groupValues[1].toInt() }
        return nomeSemExtensao to null
    }
}
