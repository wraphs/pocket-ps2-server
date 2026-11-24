package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.Comprimido
import br.ps2ponte.core.fs.VFile
import java.io.IOException

/**
 * Catálogo dos jogos da pasta servida: PS2 (.iso/.zso/.cso) e PS1 (.VCD do POPS e .cue do Ember ou soltos),
 * onde quer que estejam, com o código de cada um. Guardado em texto pelo app e refeito a cada abertura: só os
 * arquivos novos ou alterados (tamanho/data) são abertos de novo.
 */
class Catalogo(
    val jogos: List<Item>,
    /** Nomes dos arquivos da pasta ART, em maiúsculas. */
    val arte: Set<String>,
    /** Data do PNG mais novo da pasta ART (0 = nenhum). */
    val arteMaisNova: Long,
    /** Data do ART/art.tar (0 = não existe). */
    val tarData: Long,
    val quando: Long,
) {
    /** [tipo]: PS2, VCD (POPS) ou CUE (Ember / CUE+BIN solto). */
    class Item(val rel: String, val tamanho: Long, val data: Long, val id: String?, val tipo: Tipo) {
        val nome get() = rel.substringAfterLast('/')
        val base get() = nome.substringBeforeLast('.')
        /** Pasta de cima ("DVD", "CD", "POPS", "EMBER"…); "" na raiz. */
        val pasta get() = if ('/' in rel) rel.substringBefore('/') else ""
        private val partes get() = rel.split('/')
        val ps1 get() = tipo != Tipo.PS2
        val compactado get() = tipo == Tipo.PS2 && Comprimido.ehCompactado(nome)

        /** Fora do lugar onde o loader procura (o PS2 não vê). */
        val foraDoLugar: Boolean
            get() = when (tipo) {
                Tipo.PS2 -> partes.size != 2 || !(pasta.equals("DVD", true) || pasta.equals("CD", true))
                Tipo.VCD -> partes.size != 2 || !ehPastaPops(pasta)
                Tipo.CUE -> !noEmber
            }

        /** EMBER/games/<pasta do jogo>/<arquivo>.cue */
        val noEmber get() = partes.size == 4 && partes[0].equals("EMBER", true) && partes[1].equals("games", true)
        val pastaEmber get() = if (noEmber) partes[2] else null

        /** Nome que o loader usa para a capa: código (PS2), nome do VCD (POPS) ou da pasta (Ember). */
        val chaveArte: String?
            get() = when (tipo) {
                Tipo.PS2 -> id
                Tipo.VCD -> base
                Tipo.CUE -> pastaEmber ?: base
            }
    }

    enum class Tipo { PS2, VCD, CUE }

    fun temCapa(i: Item) = i.chaveArte?.let { k -> "${k.uppercase()}_COV.PNG" in arte || arte.any { it.startsWith("${k.uppercase()}_COV.") } } ?: false
    val ps2 get() = jogos.filter { !it.ps1 }
    val ps1 get() = jogos.filter { it.ps1 }
    val semCapa get() = jogos.count { !temCapa(it) }
    val foraDoLugar get() = jogos.count { it.foraDoLugar }
    /** ISOs de PS2 prontas para compactar (dentro de DVD/ e CD/). */
    val isos get() = jogos.filter { it.tipo == Tipo.PS2 && !it.compactado && !it.foraDoLugar }
    /** O art.tar está velho (ou falta) em relação às imagens. */
    val tarDesatualizado get() = arteMaisNova > 0 && arteMaisNova > tarData

    /** Uma linha por jogo, separada por tabulação; as duas primeiras linhas têm os dados da pasta ART. */
    fun texto(): String = buildString {
        append("v2\t").append(quando).append('\t').append(arteMaisNova).append('\t').append(tarData).append('\n')
        append(arte.joinToString("\t")).append('\n')
        for (j in jogos) append(j.rel).append('\t').append(j.tamanho).append('\t').append(j.data).append('\t').append(j.id ?: "").append('\t').append(j.tipo.name).append('\n')
    }

    companion object {
        private val EXT_PS2 = listOf(".iso", ".zso", ".cso")
        /** Pastas do OPL que não têm jogos (APPS = programas; VMC/CFG… = dados). */
        private val PULAR = setOf("ART", "CFG", "CHT", "LNG", "THM", "VMC", "APPS")
        private const val PROFUNDIDADE = 4

        fun ehPastaPops(nome: String) = nome.equals("POPS", true) || Regex("POPS\\d", RegexOption.IGNORE_CASE).matches(nome)

        fun tipoDe(nome: String): Tipo? = when {
            EXT_PS2.any { nome.endsWith(it, true) } -> Tipo.PS2
            nome.endsWith(".vcd", true) -> Tipo.VCD
            nome.endsWith(".cue", true) -> Tipo.CUE
            else -> null
        }

        fun ler(texto: String): Catalogo? {
            val linhas = texto.split('\n')
            val cab = linhas.firstOrNull()?.split('\t') ?: return null
            if (cab.size < 4 || cab[0] != "v2") return null
            val arte = linhas.getOrNull(1)?.split('\t')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val jogos = linhas.drop(2).mapNotNull { l ->
                val c = l.split('\t')
                if (c.size < 5) return@mapNotNull null
                val tipo = try { Tipo.valueOf(c[4]) } catch (e: IllegalArgumentException) { return@mapNotNull null }
                Item(c[0], c[1].toLongOrNull() ?: return@mapNotNull null, c[2].toLongOrNull() ?: 0, c[3].ifEmpty { null }, tipo)
            }
            return Catalogo(jogos, arte, cab[2].toLongOrNull() ?: 0, cab[3].toLongOrNull() ?: 0, cab[1].toLongOrNull() ?: 0)
        }

        /**
         * Varre [raiz]. [anterior] evita reabrir arquivos que não mudaram. [passo] recebe o nome de cada jogo
         * novo sendo lido (pode lançar para cancelar).
         */
        fun varrer(raiz: VFile, anterior: Catalogo?, passo: (String) -> Unit = {}): Catalogo {
            val antes = anterior?.jogos?.associateBy { it.rel } ?: emptyMap()
            val achados = mutableListOf<Triple<String, VFile, Tipo>>()
            var arte = emptySet<String>()
            var arteMaisNova = 0L
            var tarData = 0L
            fun andar(dir: VFile, rel: String, nivel: Int) {
                for (f in dir.listFiles().orEmpty()) {
                    val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    if (f.isDirectory) {
                        if (f.name.startsWith(".") || nivel >= PROFUNDIDADE) continue
                        if (nivel == 0 && f.name.equals("ART", true)) {
                            val itens = f.listFiles().orEmpty().filter { it.isFile }
                            arte = itens.map { it.name.uppercase() }.toSet()
                            for (a in itens) {
                                if (a.name.equals("art.tar", true)) tarData = a.lastModified()
                                else if (a.name.endsWith(".png", true)) arteMaisNova = maxOf(arteMaisNova, a.lastModified())
                            }
                            continue
                        }
                        if (nivel == 0 && PULAR.any { it.equals(f.name, true) }) continue
                        andar(f, r, nivel + 1)
                    } else if (f.isFile && '\t' !in r && '\n' !in r) {
                        tipoDe(f.name)?.let { achados += Triple(r, f, it) }
                    }
                }
            }
            andar(raiz, "", 0)
            val jogos = achados.map { (rel, f, tipo) ->
                val tam = f.length()
                val data = f.lastModified()
                val velho = antes[rel]
                if (velho != null && velho.tamanho == tam && velho.data == data && velho.tipo == tipo) velho
                else {
                    passo(f.name)
                    Item(rel, tam, data, lerId(f, tipo), tipo)
                }
            }.sortedBy { it.nome.lowercase() }
            return Catalogo(jogos, arte, arteMaisNova, tarData, System.currentTimeMillis())
        }

        private fun lerId(f: VFile, tipo: Tipo): String? = try {
            when (tipo) {
                Tipo.PS2 -> {
                    val h = f.open(false)
                    val img = try { if (Comprimido.ehCompactado(f.name)) Comprimido.abrir(h) else h } catch (e: IOException) { h.close(); throw e }
                    img.use { IdJogo.ler(it) }
                }
                Tipo.VCD -> f.open(false).use { Ps1.lerId(it, vcd = true) }
                Tipo.CUE -> {
                    // A primeira .bin citada na folha
                    val texto = f.open(false).use { h -> ByteArray(minOf(h.length(), 64 * 1024).toInt()).also { h.readFully(0, it, 0, it.size) } }
                        .toString(Charsets.ISO_8859_1)
                    val bin = Regex("""FILE\s+(?:"([^"]+)"|(\S+))""", RegexOption.IGNORE_CASE).find(texto)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    bin?.let { n -> f.parentFile?.child(n)?.takeIf { it.exists() }?.open(false)?.use { Ps1.lerId(it, vcd = false) } }
                }
            }
        } catch (e: IOException) {
            null
        }
    }
}
