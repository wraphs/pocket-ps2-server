package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.io.IOException

/**
 * Põe os jogos de PS1 no lugar que cada loader procura:
 * - POPStarter: POPS/<nome>.VCD; multidisco ganha POPS/<disco>/DISCS.TXT (até 4) e VMCDIR.TXT (cartão do disco 1).
 * - Ember: EMBER/games/<jogo>/<disco>.cue + .bin (+ .sbi/.lsd do LibCrypt); multidisco numa pasta só, com Discs.txt.
 *
 * [destino] decide o formato: MANTER (VCD vai para o POPS, CUE/BIN para o Ember), POPS ou EMBER (converte o que
 * estiver no outro formato). Primeiro [planejar] (nada muda no disco), depois [Plano.executar].
 */
class OrganizadorPs1(private val raiz: VFile) {
    enum class Destino { MANTER, POPS, EMBER }

    class Acao(val descricao: String, val conversao: Boolean, val bytes: Long, internal val fazer: (Progresso, Boolean, VFile?) -> Unit)

    fun interface Progresso {
        /** Lança para cancelar. */
        fun passo(feito: Long, total: Long, texto: String)
    }

    class Plano(val acoes: List<Acao>, val avisos: List<String>) {
        val conversoes get() = acoes.count { it.conversao }
        val vazio get() = acoes.isEmpty()

        /**
         * Executa tudo; [apagarOriginais] só vale para conversões (movidas não duplicam). Com [temp] (pasta em outro
         * volume, quando falta espaço), cada conversão é montada lá, o original é apagado (se pedido) e o resultado
         * volta para o lugar certo. Devolve as falhas.
         */
        fun executar(apagarOriginais: Boolean, p: Progresso, temp: VFile? = null): List<String> {
            val falhas = mutableListOf<String>()
            for (a in acoes) {
                try {
                    a.fazer(p, apagarOriginais, temp)
                } catch (e: IOException) {
                    falhas += "${a.descricao}: ${e.message}"
                }
            }
            return falhas
        }
    }

    private val arvore = LocalTree(raiz)

    private fun pasta(nome: String): VFile {
        raiz.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(nome, true) }?.let { return it }
        val p = raiz.child(nome)
        if (!p.mkdirs()) throw IOException("não consegui criar $nome/")
        return p
    }

    private fun emberGames(): VFile {
        val e = pasta("EMBER")
        e.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("games", true) }?.let { return it }
        val g = e.child("games")
        if (!g.mkdirs()) throw IOException("não consegui criar EMBER/games/")
        return g
    }

    /** Nome de pasta/arquivo aceito em FAT/exFAT e pelos loaders (sem / \ : * ? " < > |). */
    private fun limpo(s: String) = s.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().trimEnd('.').ifEmpty { "Jogo" }

    private class Disco(val item: Catalogo.Item, val arquivo: VFile, val titulo: String, val numero: Int?)

    fun planejar(cat: Catalogo, destino: Destino): Plano {
        val acoes = mutableListOf<Acao>()
        val avisos = mutableListOf<String>()
        // Agrupa por pasta de origem + título sem "(Disc N)"
        val discos = cat.ps1.mapNotNull { i ->
            val f = arvore.resolve(i.rel)?.takeIf { it.isFile } ?: return@mapNotNull null
            val (titulo, n) = Ps1.disco(i.base)
            Disco(i, f, titulo, n)
        }
        val grupos = discos.groupBy { (it.item.rel.substringBeforeLast('/', "")) + "\u0000" + it.titulo.lowercase() }

        for ((_, g0) in grupos) {
            val g = g0.sortedBy { it.numero ?: 0 }
            val todosNoLugar = g.all { !it.item.foraDoLugar }
            val alvo = when (destino) {
                Destino.MANTER -> if (g.first().item.tipo == Catalogo.Tipo.VCD) Destino.POPS else Destino.EMBER
                else -> destino
            }
            val precisaConverter = g.any { (it.item.tipo == Catalogo.Tipo.VCD) != (alvo == Destino.POPS) }
            if (todosNoLugar && !precisaConverter) {
                // Já no lugar: só confere o multidisco
                if (g.size > 1) multidiscoNoLugar(g, alvo)?.let { acoes += it }
                continue
            }
            if (todosNoLugar && destino == Destino.MANTER) continue
            if (alvo == Destino.POPS) planejarPops(g, acoes, avisos) else planejarEmber(g, acoes, avisos)
        }
        return Plano(acoes, avisos)
    }

    // ------------------------------------------------------------------ POPS

    private fun planejarPops(g: List<Disco>, acoes: MutableList<Acao>, avisos: MutableList<String>) {
        val nomes = g.map { d -> limpo(d.item.base) + ".VCD" }
        for ((k, d) in g.withIndex()) {
            val nome = nomes[k]
            if (nome.length > 60) avisos += "⚠ $nome: nome longo (o POPStarter aceita até ~60 letras com segurança)"
            if (d.item.tipo == Catalogo.Tipo.VCD) {
                acoes += Acao("${d.item.rel} → POPS/$nome", false, 0) { _, _, _ ->
                    mover(d.arquivo, pasta("POPS"), nome)
                }
            } else {
                val cue = lerCue(d.arquivo)
                Ps1.prontoParaVcd(cue)?.let { avisos += "✗ ${d.item.rel}: $it"; return }
                val bytes = cue.arquivos.sumOf { d.arquivo.parentFile!!.child(it).length() }
                acoes += Acao("${d.item.rel} → POPS/$nome (CUE/BIN → VCD)", true, bytes) { p, apagar, temp ->
                    val pops = pasta("POPS")
                    val destino = pops.child(nome)
                    if (destino.exists()) throw IOException("POPS/$nome já existe")
                    val tmp = (temp ?: pops).child("$nome.part")
                    if (tmp.exists()) tmp.delete()
                    val bins = cue.arquivos.map { d.arquivo.parentFile!!.child(it).open(false) }
                    try {
                        tmp.open(true).use { s -> Ps1.paraVcd(cue, bins, s) { f, t -> p.passo(f, t, nome) } }
                    } catch (e: Throwable) {
                        tmp.delete(); throw e
                    } finally {
                        bins.forEach { it.close() }
                    }
                    if (temp == null) {
                        if (!tmp.renameTo(destino)) throw IOException("não consegui gravar POPS/$nome")
                        if (apagar) apagarCueBin(d.arquivo, cue)
                    } else {
                        // Pronto e com o nome final no temporário; libera o espaço do CUE/BIN e traz o VCD
                        val pronto = Temporario.marcar(tmp, nome, "${pops.name}/$nome")
                        if (apagar) apagarCueBin(d.arquivo, cue)
                        Temporario.trazer(pronto, destino, pops.usableSpace()) { f, t -> p.passo(f, t, nome) }
                    }
                }
            }
        }
        if (g.size > 1) acoes += Acao("multidisco: ${g.first().titulo} (DISCS.TXT/VMCDIR.TXT)", false, 0) { _, _, _ -> escreverDiscsPops(nomes) }
        if (g.size > 4) avisos += "⚠ ${g.first().titulo}: ${g.size} discos; o POPStarter troca entre no máximo 4"
    }

    private fun escreverDiscsPops(nomes: List<String>) {
        val pops = pasta("POPS")
        val lista = nomes.take(4).joinToString("\r\n") + "\r\n"
        val pastaDisco1 = nomes.first().removeSuffix(".VCD")
        for ((k, n) in nomes.withIndex()) {
            val d = pops.child(n.removeSuffix(".VCD"))
            if (!d.exists()) d.mkdirs()
            gravarTexto(d.child("DISCS.TXT"), lista)
            if (k > 0) gravarTexto(d.child("VMCDIR.TXT"), pastaDisco1) // mesmo cartão do disco 1
        }
    }

    // ------------------------------------------------------------------ Ember

    private fun planejarEmber(g: List<Disco>, acoes: MutableList<Acao>, avisos: MutableList<String>) {
        val jogo = limpo(g.first().titulo)
        val cues = mutableListOf<String>()
        for (d in g) {
            val base = limpo(d.item.base)
            cues += "$base.cue"
            if (d.item.tipo == Catalogo.Tipo.CUE) {
                val cue = try { lerCue(d.arquivo) } catch (e: IOException) { avisos += "✗ ${d.item.rel}: ${e.message}"; return }
                acoes += Acao("${d.item.rel} → EMBER/games/$jogo/", false, 0) { _, _, _ ->
                    val dest = pastaJogo(jogo)
                    val pai = d.arquivo.parentFile!!
                    for (b in cue.arquivos) mover(pai.child(b), dest, b)
                    // LibCrypt: .sbi/.lsd com o mesmo nome da folha
                    for (ext in listOf(".sbi", ".lsd", ".SBI", ".LSD")) pai.child(d.item.base + ext).takeIf { it.exists() }?.let { mover(it, dest, base + ext.lowercase()) }
                    mover(d.arquivo, dest, "$base.cue")
                }
            } else {
                acoes += Acao("${d.item.rel} → EMBER/games/$jogo/ (VCD → CUE/BIN)", true, d.item.tamanho) { p, apagar, temp ->
                    val dest = pastaJogo(jogo)
                    val bin = dest.child("$base.bin")
                    if (bin.exists()) throw IOException("EMBER/games/$jogo/$base.bin já existe")
                    val tmp = (temp ?: dest).child("$base.bin.part")
                    if (tmp.exists()) tmp.delete()
                    val cueTexto = d.arquivo.open(false).use { e ->
                        val cab = ByteArray(2048).also { e.readFully(0, it, 0, it.size) }
                        val texto = Ps1.cueDoVcd(cab, "$base.bin")
                        try {
                            tmp.open(true).use { s -> copiar(e, Ps1.CABECALHO_VCD.toLong(), s, p, "$base.bin") }
                        } catch (t: Throwable) {
                            tmp.delete(); throw t
                        }
                        texto
                    }
                    // A folha primeiro: se a .bin ficar esperando no temporário, o app sabe para onde ela vai
                    gravarTexto(dest.child("$base.cue"), cueTexto)
                    if (temp == null) {
                        if (!tmp.renameTo(bin)) throw IOException("não consegui gravar $base.bin")
                        if (apagar) d.arquivo.delete()
                    } else {
                        val pronto = Temporario.marcar(tmp, "$base.bin", "EMBER/games/${dest.name}/$base.bin")
                        if (apagar) d.arquivo.delete()
                        Temporario.trazer(pronto, bin, dest.usableSpace()) { f, t -> p.passo(f, t, "$base.bin") }
                    }
                }
            }
        }
        if (g.size > 1) acoes += Acao("multidisco: $jogo (Discs.txt)", false, 0) { _, _, _ -> gravarTexto(pastaJogo(jogo).child("Discs.txt"), cues.joinToString("\r\n") + "\r\n") }
    }

    private fun pastaJogo(jogo: String): VFile {
        val games = emberGames()
        games.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(jogo, true) }?.let { return it }
        val d = games.child(jogo)
        if (!d.mkdirs()) throw IOException("não consegui criar EMBER/games/$jogo")
        return d
    }

    /** Multidisco já no lugar: confere DISCS.TXT (POPS) ou Discs.txt (Ember). */
    private fun multidiscoNoLugar(g: List<Disco>, alvo: Destino): Acao? {
        if (alvo == Destino.POPS) {
            val nomes = g.map { it.item.nome }
            val esperado = nomes.take(4).joinToString("\r\n") + "\r\n"
            val pops = g.first().arquivo.parentFile ?: return null
            val ok = nomes.all { n -> lerTexto(pops.child(n.substringBeforeLast('.')).child("DISCS.TXT")) == esperado }
            return if (ok) null else Acao("multidisco: ${g.first().titulo} (DISCS.TXT/VMCDIR.TXT)", false, 0) { _, _, _ -> escreverDiscsPopsEm(pops, nomes) }
        }
        val pastas = g.map { it.item.pastaEmber }.toSet()
        if (pastas.size != 1) return null // discos em pastas diferentes do Ember: o usuário escolheu assim
        val dir = g.first().arquivo.parentFile ?: return null
        val esperado = g.joinToString("\r\n") { it.item.nome } + "\r\n"
        val atual = lerTexto(dir.listFiles()?.firstOrNull { it.name.equals("Discs.txt", true) } ?: dir.child("Discs.txt"))
        return if (atual?.replace("\r\n", "\n")?.trim() == esperado.replace("\r\n", "\n").trim()) null
        else Acao("multidisco: ${g.first().titulo} (Discs.txt)", false, 0) { _, _, _ -> gravarTexto(dir.child("Discs.txt"), esperado) }
    }

    private fun escreverDiscsPopsEm(pops: VFile, nomes: List<String>) {
        val lista = nomes.take(4).joinToString("\r\n") + "\r\n"
        val disco1 = nomes.first().substringBeforeLast('.')
        for ((k, n) in nomes.withIndex()) {
            val d = pops.child(n.substringBeforeLast('.'))
            if (!d.exists()) d.mkdirs()
            gravarTexto(d.child("DISCS.TXT"), lista)
            if (k > 0) gravarTexto(d.child("VMCDIR.TXT"), disco1)
        }
    }

    // ------------------------------------------------------------------ apoio

    private fun lerCue(f: VFile): Ps1.Cue {
        val texto = lerTexto(f) ?: throw IOException("não consegui ler ${f.name}")
        val pai = f.parentFile!!
        return Ps1.lerCue(texto) { n -> pai.child(n).let { if (it.exists()) it.length() else -1L } }
    }

    private fun apagarCueBin(cue: VFile, c: Ps1.Cue) {
        val pai = cue.parentFile!!
        for (b in c.arquivos) pai.child(b).delete()
        cue.delete()
    }

    private fun mover(f: VFile, dest: VFile, nome: String) {
        val alvo = dest.child(nome)
        if (alvo.exists()) throw IOException("${dest.name}/$nome já existe")
        if (!f.renameTo(alvo)) throw IOException("o Android não deixou mover ${f.name}")
    }

    private fun copiar(e: VHandle, de: Long, s: VHandle, p: Progresso, nome: String) {
        s.setLength(0)
        val total = e.length() - de
        val buf = ByteArray(1 shl 20)
        var pos = 0L
        while (pos < total) {
            val n = e.readFully(de + pos, buf, 0, minOf(buf.size.toLong(), total - pos).toInt())
            if (n <= 0) throw IOException("fim inesperado")
            s.write(pos, buf, 0, n)
            pos += n
            p.passo(pos, total, nome)
        }
    }

    private fun lerTexto(f: VFile): String? = try {
        if (!f.exists()) null else f.open(false).use { h -> ByteArray(h.length().toInt()).also { h.readFully(0, it, 0, it.size) }.toString(Charsets.ISO_8859_1) }
    } catch (e: IOException) {
        null
    }

    private fun gravarTexto(f: VFile, s: String) {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        f.open(true).use { h -> h.setLength(0); h.write(0, b, 0, b.size) }
    }
}
