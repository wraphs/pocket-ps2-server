package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.fs.VFile
import java.io.IOException

/**
 * Arquivos montados num volume temporário (quando falta espaço na pasta dos jogos) esperando voltar.
 *
 * Depois de pronto e conferido, o arquivo fica no temporário **já com o nome final** e ganha ao lado um
 * "<nome>.destino" com o caminho de destino relativo à pasta dos jogos. Assim, se a volta falhar, nada se perde:
 * o app oferece trazer depois ([pendentes]) e, se alguém copiar à mão, o nome já está certo.
 */
object Temporario {
    fun interface Progresso {
        fun passo(feito: Long, total: Long)
    }

    private const val MARCA = ".destino"
    /** Folga além do tamanho do arquivo ao conferir o espaço do destino. */
    private const val FOLGA = 8L * 1024 * 1024

    /** Dá a [arquivo] o nome final e anota o destino ([rel], relativo à pasta dos jogos). Devolve o arquivo renomeado. */
    fun marcar(arquivo: VFile, nomeFinal: String, rel: String): VFile {
        val pai = arquivo.parentFile ?: throw IOException("temporário sem pasta")
        val final = pai.child(nomeFinal)
        if (final != arquivo) {
            if (final.exists()) final.delete()
            if (!arquivo.renameTo(final)) throw IOException("não consegui renomear ${arquivo.name} no temporário")
        }
        val m = pai.child(nomeFinal + MARCA)
        val b = rel.toByteArray(Charsets.UTF_8)
        m.open(true).use { h -> h.setLength(0); h.write(0, b, 0, b.size) }
        return final
    }

    /**
     * Copia [arquivo] para [destino] (via "<nome>.part", depois renomeia) e apaga o temporário e a marca.
     * Confere antes o espaço livre do destino ([livre]: bytes livres agora, 0 = desconhecido). Os erros
     * dizem a causa de verdade; o arquivo continua no temporário.
     */
    fun trazer(arquivo: VFile, destino: VFile, livre: Long, p: Progresso) {
        val pai = destino.parentFile ?: throw IOException("destino sem pasta")
        val tam = arquivo.length()
        if (livre in 1 until tam + FOLGA) {
            throw SemEspaco(tam + FOLGA - livre)
        }
        val parte = pai.child(destino.name + ".part")
        if (parte.exists()) parte.delete()
        try {
            arquivo.open(false).use { e ->
                parte.open(true).use { s ->
                    s.setLength(0)
                    val buf = ByteArray(1 shl 20)
                    var pos = 0L
                    while (pos < tam) {
                        val n = e.readFully(pos, buf, 0, minOf(buf.size.toLong(), tam - pos).toInt())
                        if (n <= 0) throw IOException("fim inesperado de ${arquivo.name}")
                        s.write(pos, buf, 0, n)
                        pos += n
                        p.passo(pos, tam)
                    }
                }
            }
            if (parte.length() != tam) throw IOException("cópia incompleta (${parte.length()} de $tam bytes)")
        } catch (t: Exception) {
            parte.delete()
            throw if (t is IOException) t else IOException("${t.javaClass.simpleName}: ${t.message}", t)
        }
        if (destino.exists()) destino.delete()
        if (!parte.renameTo(destino)) throw IOException("não consegui renomear ${parte.name} para ${destino.name}")
        arquivo.delete()
        arquivo.parentFile?.child(arquivo.name + MARCA)?.delete()
    }

    /** Falta espaço no destino: [faltam] bytes. */
    class SemEspaco(val faltam: Long) : IOException("sem espaço no destino: faltam ${faltam / (1024 * 1024)} MiB")

    /** Arquivos esperando voltar em [dir]: (arquivo, destino relativo). */
    fun pendentes(dir: VFile): List<Pair<VFile, String>> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(MARCA) }.mapNotNull { m ->
            val arq = dir.child(m.name.removeSuffix(MARCA)).takeIf { it.exists() } ?: return@mapNotNull null
            val rel = try {
                m.open(false).use { h -> ByteArray(h.length().toInt()).also { h.readFully(0, it, 0, it.size) } }.toString(Charsets.UTF_8).trim()
            } catch (e: IOException) { return@mapNotNull null }
            if (rel.isEmpty() || ".." in rel.split('/')) null else arq to rel
        }

    /** O destino de [rel] na pasta dos jogos (as pastas do caminho são criadas se faltarem). */
    fun destinoEm(raiz: VFile, rel: String): VFile {
        val pai = rel.substringBeforeLast('/', "")
        val dir = if (pai.isEmpty()) raiz else (LocalTree(raiz).resolve(pai) ?: throw IOException("caminho inválido: $rel"))
        if (!dir.exists()) dir.mkdirs()
        return dir.child(rel.substringAfterLast('/'))
    }
}
