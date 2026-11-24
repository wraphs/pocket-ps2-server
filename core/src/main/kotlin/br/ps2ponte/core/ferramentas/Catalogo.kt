package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.Comprimido
import br.ps2ponte.core.fs.VFile
import java.io.IOException

/**
 * Catálogo dos jogos da pasta servida: todos os .iso/.zso/.cso encontrados (não só em DVD/ e CD/), com o
 * código de cada um. Guardado em texto pelo app e refeito a cada abertura: só os arquivos novos ou
 * alterados (tamanho/data) são abertos de novo, então a varredura é rápida mesmo com muitos jogos.
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
    class Item(val rel: String, val tamanho: Long, val data: Long, val id: String?) {
        val nome get() = rel.substringAfterLast('/')
        /** Pasta de cima ("DVD", "CD" ou outra); "" na raiz. */
        val pasta get() = if ('/' in rel) rel.substringBefore('/') else ""
        val compactado get() = Comprimido.ehCompactado(nome)
        /** Fora de DVD/ ou CD/ (ou em subpasta delas): o OPL não vê. */
        val foraDoLugar get() = rel.count { it == '/' } != 1 || !(pasta.equals("DVD", true) || pasta.equals("CD", true))
    }

    fun temCapa(i: Item) = i.id != null && arte.any { it.startsWith("${i.id}_COV.") }
    val semCapa get() = jogos.count { !temCapa(it) }
    val foraDoLugar get() = jogos.count { it.foraDoLugar }
    /** ISOs prontas para compactar (dentro de DVD/ e CD/). */
    val isos get() = jogos.filter { !it.compactado && !it.foraDoLugar }
    /** O art.tar está velho (ou falta) em relação às imagens. */
    val tarDesatualizado get() = arteMaisNova > 0 && arteMaisNova > tarData

    /** Uma linha por jogo, separada por tabulação; a primeira linha tem os dados da pasta ART. */
    fun texto(): String = buildString {
        append("v1\t").append(quando).append('\t').append(arteMaisNova).append('\t').append(tarData).append('\n')
        append(arte.joinToString("\t")).append('\n')
        for (j in jogos) append(j.rel).append('\t').append(j.tamanho).append('\t').append(j.data).append('\t').append(j.id ?: "").append('\n')
    }

    companion object {
        private val EXT = listOf(".iso", ".zso", ".cso")
        /** Pastas do OPL que não têm jogos de PS2 (POPS = PS1, APPS = programas). */
        private val PULAR = setOf("ART", "CFG", "CHT", "LNG", "THM", "VMC", "POPS", "APPS")
        private const val PROFUNDIDADE = 4

        fun ehJogo(nome: String) = EXT.any { nome.endsWith(it, true) }

        fun ler(texto: String): Catalogo? {
            val linhas = texto.split('\n')
            val cab = linhas.firstOrNull()?.split('\t') ?: return null
            if (cab.size < 4 || cab[0] != "v1") return null
            val arte = linhas.getOrNull(1)?.split('\t')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val jogos = linhas.drop(2).mapNotNull { l ->
                val c = l.split('\t')
                if (c.size < 4) null else Item(c[0], c[1].toLongOrNull() ?: return@mapNotNull null, c[2].toLongOrNull() ?: 0, c[3].ifEmpty { null })
            }
            return Catalogo(jogos, arte, cab[2].toLongOrNull() ?: 0, cab[3].toLongOrNull() ?: 0, cab[1].toLongOrNull() ?: 0)
        }

        /**
         * Varre [raiz]. [anterior] evita reabrir arquivos que não mudaram. [passo] recebe o nome de cada jogo
         * novo sendo lido (pode lançar para cancelar).
         */
        fun varrer(raiz: VFile, anterior: Catalogo?, passo: (String) -> Unit = {}): Catalogo {
            val antes = anterior?.jogos?.associateBy { it.rel } ?: emptyMap()
            val achados = mutableListOf<Pair<String, VFile>>()
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
                    } else if (f.isFile && ehJogo(f.name) && '\t' !in r && '\n' !in r) {
                        achados += r to f
                    }
                }
            }
            andar(raiz, "", 0)
            val jogos = achados.map { (rel, f) ->
                val tam = f.length()
                val data = f.lastModified()
                val velho = antes[rel]
                if (velho != null && velho.tamanho == tam && velho.data == data) velho
                else {
                    passo(f.name)
                    Item(rel, tam, data, lerId(f))
                }
            }.sortedBy { it.nome.lowercase() }
            return Catalogo(jogos, arte, arteMaisNova, tarData, System.currentTimeMillis())
        }

        private fun lerId(f: VFile): String? = try {
            val h = f.open(false)
            val img = try { if (Comprimido.ehCompactado(f.name)) Comprimido.abrir(h) else h } catch (e: IOException) { h.close(); throw e }
            img.use { IdJogo.ler(it) }
        } catch (e: IOException) {
            null
        }
    }
}
