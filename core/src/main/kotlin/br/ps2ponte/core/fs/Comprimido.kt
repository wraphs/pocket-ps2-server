package br.ps2ponte.core.fs

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Imagens compactadas por blocos: CSO ("CISO", deflate) e ZSO ("ZISO", LZ4).
 *
 * Cabeçalho (24 bytes, little-endian): magia, tamanho do cabeçalho, tamanho descompactado (u64),
 * tamanho do bloco (u32), versão (u8), alinhamento (u8), 2 reservados. Depois, um índice de
 * (blocos + 1) u32: posição do bloco = (entrada & 0x7FFFFFFF) << alinhamento; o bit 31 marca um bloco
 * guardado sem compactar (no CSO v2 ele marca LZ4, e "sem compactar" é o bloco com tamanho cheio).
 */
object Comprimido {
    const val CSO = 0x4F534943 // "CISO"
    const val ZSO = 0x4F53495A // "ZISO"

    fun ehCompactado(nome: String, zso: Boolean = true): Boolean {
        val n = nome.lowercase()
        return n.endsWith(".cso") || (zso && n.endsWith(".zso"))
    }

    /** Tamanho descompactado lido do cabeçalho, ou -1 se não for CSO/ZSO válido. */
    fun tamanhoReal(h: VHandle): Long {
        val c = ByteArray(24)
        if (h.readFully(0, c, 0, 24) < 24) return -1
        val magia = le32(c, 0)
        if (magia != CSO && magia != ZSO) return -1
        return le64(c, 8)
    }

    /** Abre [h] (CSO ou ZSO) como uma imagem descompactada, só leitura. [h] passa a ser da imagem. */
    fun abrir(h: VHandle): VHandle = Leitor(h)

    internal fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)

    internal fun le64(b: ByteArray, i: Int) = (le32(b, i).toLong() and 0xFFFFFFFFL) or (le32(b, i + 4).toLong() shl 32)

    private fun poe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }

    private class Leitor(private val h: VHandle) : VHandle {
        private val tamanho: Long
        private val bloco: Int
        private val alinhamento: Int
        private val zso: Boolean
        private val versao: Int
        private val blocos: Int
        private val inicioIndice: Long

        // Índice em páginas de 1024 entradas (um DVD de 8 GB teria 16 MB de índice: não carrega tudo)
        private val paginas = object : LinkedHashMap<Int, IntArray>(16, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, IntArray>?) = size > 32
        }

        private val inflater = Inflater(true)
        private var bruto = ByteArray(0)
        // Último bloco descompactado (leituras que começam no meio de um bloco)
        private var cacheBloco = -1
        private val cache: ByteArray

        init {
            val c = ByteArray(24)
            if (h.readFully(0, c, 0, 24) < 24) throw IOException("CSO/ZSO: cabeçalho curto")
            val magia = le32(c, 0)
            if (magia != CSO && magia != ZSO) throw IOException("CSO/ZSO: não é uma imagem compactada")
            zso = magia == ZSO
            tamanho = le64(c, 8)
            bloco = le32(c, 16)
            versao = c[20].toInt() and 0xFF
            alinhamento = c[21].toInt() and 0xFF
            if (bloco < 512 || bloco > (1 shl 20) || bloco and (bloco - 1) != 0) throw IOException("CSO/ZSO: bloco de $bloco bytes")
            blocos = ((tamanho + bloco - 1) / bloco).toInt()
            val hs = le32(c, 4)
            inicioIndice = if (hs in 24..0x1000) hs.toLong() else 24L
            cache = ByteArray(bloco)
        }

        private fun entrada(i: Int): Int {
            val p = i ushr 10
            val pag = paginas[p] ?: run {
                val n = minOf(1024, blocos + 1 - (p shl 10))
                val b = ByteArray(n * 4)
                if (h.readFully(inicioIndice + (p.toLong() shl 12), b, 0, b.size) < b.size) throw IOException("CSO/ZSO: índice cortado")
                IntArray(n) { le32(b, it * 4) }.also { paginas[p] = it }
            }
            return pag[i and 1023]
        }

        private fun posicao(e: Int) = (e.toLong() and 0x7FFFFFFFL) shl alinhamento

        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(this) {
            if (pos >= tamanho) return -1
            val n = minOf(len.toLong(), tamanho - pos).toInt()
            var feito = 0
            var b = (pos / bloco).toInt()
            var dentro = (pos % bloco).toInt()
            while (feito < n) {
                // Lê de uma vez os dados compactados de vários blocos seguidos (menos chamadas de E/S)
                val ultimo = minOf(blocos - 1, b + ((n - feito + dentro + bloco - 1) / bloco) - 1)
                if (b == cacheBloco) {
                    val q = minOf(n - feito, bloco - dentro)
                    System.arraycopy(cache, dentro, buf, off + feito, q)
                    feito += q; dentro = 0; b++
                    continue
                }
                val ini = posicao(entrada(b))
                val fim = posicao(entrada(ultimo + 1))
                val total = (fim - ini).toInt()
                if (total < 0) throw IOException("CSO/ZSO: índice inválido no bloco $b")
                if (bruto.size < total + bloco) bruto = ByteArray(total + bloco)
                val lidos = h.readFully(ini, bruto, 0, total)
                var bb = b
                while (bb <= ultimo && feito < n) {
                    val e = entrada(bb)
                    val rel = (posicao(e) - ini).toInt()
                    val tam = minOf((posicao(entrada(bb + 1)) - posicao(e)).toInt(), lidos - rel)
                    val real = minOf(bloco.toLong(), tamanho - bb.toLong() * bloco).toInt()
                    descompactar(e, bruto, rel, tam, cache, real)
                    cacheBloco = bb
                    val q = minOf(n - feito, real - dentro)
                    System.arraycopy(cache, dentro, buf, off + feito, q)
                    feito += q; dentro = 0; bb++
                }
                b = bb
            }
            feito
        }

        private fun descompactar(e: Int, src: ByteArray, off: Int, tam: Int, dst: ByteArray, real: Int) {
            val marcado = e < 0
            val plano = if (versao >= 2 && !zso) tam >= bloco else marcado
            when {
                plano -> System.arraycopy(src, off, dst, 0, minOf(real, tam).also { if (it < real) throw IOException("CSO/ZSO: bloco cortado") })
                zso || (versao >= 2 && marcado) -> Lz4.descompactar(src, off, tam, dst, 0, real)
                else -> {
                    inflater.reset()
                    inflater.setInput(src, off, tam)
                    var o = 0
                    while (o < real) {
                        val r = inflater.inflate(dst, o, real - o)
                        if (r == 0 && (inflater.finished() || inflater.needsInput())) break
                        o += r
                    }
                    if (o < real) throw IOException("CSO: bloco corrompido")
                }
            }
        }

        override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) = throw IOException("imagem compactada: só leitura")
        override fun length() = tamanho
        override fun setLength(n: Long) = throw IOException("imagem compactada: só leitura")
        override fun close() {
            inflater.end()
            h.close()
        }
    }

    /**
     * Compacta [entrada] (ISO) em [saida] no formato ZSO ([zso]) ou CSO, blocos de 2048 bytes.
     * [progresso] recebe (feitos, total) e devolve false para cancelar. Devolve o tamanho final.
     */
    fun compactar(entrada: VHandle, saida: VHandle, zso: Boolean, alinhamentoMinimo: Int = 0, progresso: (Long, Long) -> Boolean): Long {
        val tamanho = entrada.length()
        val bloco = 2048
        val blocos = ((tamanho + bloco - 1) / bloco).toInt()
        // Alinhamento: as posições têm 31 bits; acima de 2 GB de saída elas passam a contar de 2 em 2, 4 em 4…
        var alinhamento = alinhamentoMinimo
        while ((tamanho + 0x1000000L) ushr alinhamento > 0x7FFFFFFFL) alinhamento++
        val cab = ByteArray(24)
        poe32(cab, 0, if (zso) ZSO else CSO)
        poe32(cab, 4, 24)
        poe32(cab, 8, tamanho.toInt()); poe32(cab, 12, (tamanho ushr 32).toInt())
        poe32(cab, 16, bloco)
        cab[20] = 1
        cab[21] = alinhamento.toByte()
        saida.setLength(0)
        saida.write(0, cab, 0, 24)
        val indice = IntArray(blocos + 1)
        var pos = 24L + 4L * (blocos + 1)

        val lote = 64 // blocos lidos por vez
        val ent = ByteArray(bloco * lote)
        val comp = ByteArray(Lz4.limite(bloco) + 64)
        val out = java.io.ByteArrayOutputStream(bloco * lote)
        val tabela = IntArray(4096)
        val deflater = if (zso) null else Deflater(9, true)
        try {
            var b = 0
            while (b < blocos) {
                val n = minOf(lote, blocos - b)
                val lidos = entrada.readFully(b.toLong() * bloco, ent, 0, n * bloco)
                out.reset()
                var base = pos
                for (k in 0 until n) {
                    val ini = k * bloco
                    val real = minOf(bloco, lidos - ini).coerceAtLeast(0)
                    // Alinha o começo do bloco
                    val resto = (base and ((1L shl alinhamento) - 1)).toInt()
                    if (resto != 0) repeat((1 shl alinhamento) - resto) { out.write(0) }
                    base += if (resto != 0) ((1 shl alinhamento) - resto).toLong() else 0L
                    val c = if (real == 0) 0 else if (zso) Lz4.compactar(ent, ini, real, comp, tabela) else {
                        deflater!!.reset()
                        deflater.setInput(ent, ini, real)
                        deflater.finish()
                        var t = 0
                        while (!deflater.finished() && t < comp.size) t += deflater.deflate(comp, t, comp.size - t)
                        if (deflater.finished()) t else Int.MAX_VALUE
                    }
                    val plano = c >= real
                    indice[b + k] = (base ushr alinhamento).toInt() or (if (plano) Int.MIN_VALUE else 0)
                    if (plano) {
                        out.write(ent, ini, real)
                        if (real < bloco) repeat(bloco - real) { out.write(0) }
                        base += bloco
                    } else {
                        out.write(comp, 0, c)
                        base += c
                    }
                }
                val bytes = out.toByteArray()
                saida.write(pos, bytes, 0, bytes.size)
                pos = base
                b += n
                if (!progresso(minOf(b.toLong() * bloco, tamanho), tamanho)) throw InterruptedException()
            }
            // O fim também fica alinhado (senão o tamanho do último bloco sairia arredondado para baixo)
            val resto = (pos and ((1L shl alinhamento) - 1)).toInt()
            if (resto != 0) {
                val z = ByteArray((1 shl alinhamento) - resto)
                saida.write(pos, z, 0, z.size)
                pos += z.size
            }
            indice[blocos] = (pos ushr alinhamento).toInt()
            val bi = ByteArray(indice.size * 4)
            for (i in indice.indices) poe32(bi, i * 4, indice[i])
            saida.write(24, bi, 0, bi.size)
            return pos
        } finally {
            deflater?.end()
        }
    }
}

/**
 * A árvore vista pelo PS2 com os jogos compactados aparecendo como ".iso" normais (descompactados na hora).
 * [zso]: também o ZSO (o OPL lê ZSO sozinho pelo SMB; o Neutrino e o UDPBD precisam do ISO). Se já existir um
 * ".iso" com o mesmo nome, o compactado fica escondido.
 */
class ArvoreIso private constructor(private val real: VFile, private val pai: ArvoreIso?, private val zso: Boolean) : VFile {
    constructor(raiz: VFile, zso: Boolean) : this(raiz, null, zso)

    private val compactado = real.isFile && Comprimido.ehCompactado(real.name, zso)

    override val name: String = if (compactado) real.name.dropLast(4) + ".iso" else real.name
    override val path: String get() = real.path
    override val parentFile: VFile? get() = pai
    override val isDirectory get() = real.isDirectory
    override val isFile get() = real.isFile
    override fun exists() = real.exists()
    override fun length(): Long = if (compactado) tamanho(real) else real.length()
    override fun lastModified() = real.lastModified()

    override fun listFiles(): List<VFile>? {
        val itens = real.listFiles() ?: return null
        val isos = itens.mapNotNullTo(HashSet()) { f -> f.name.lowercase().takeIf { it.endsWith(".iso") } }
        return itens.mapNotNull { f ->
            if (f.isFile && Comprimido.ehCompactado(f.name, zso) && (f.name.dropLast(4) + ".iso").lowercase() in isos) null
            else ArvoreIso(f, this, zso)
        }
    }

    override fun child(name: String): VFile {
        val direto = real.child(name)
        if (!name.endsWith(".iso", true) || direto.exists()) return ArvoreIso(direto, this, zso)
        val base = name.dropLast(4)
        for (ext in if (zso) listOf(".zso", ".cso", ".ZSO", ".CSO") else listOf(".cso", ".CSO")) {
            val c = real.child(base + ext)
            if (c.exists()) return ArvoreIso(c, this, zso)
        }
        return ArvoreIso(direto, this, zso)
    }

    override fun mkdir() = real.mkdir()
    override fun mkdirs() = real.mkdirs()
    override fun delete() = real.delete()
    override fun renameTo(dest: VFile): Boolean {
        val d = (dest as? ArvoreIso)?.real ?: dest
        // Renomear o ".iso" virtual renomeia o arquivo compactado, mantendo a extensão de verdade
        if (compactado && d.name.endsWith(".iso", true)) return real.renameTo(d.parentFile?.child(d.name.dropLast(4) + real.name.takeLast(4)) ?: d)
        return real.renameTo(d)
    }

    override fun open(write: Boolean): VHandle {
        if (!compactado) return real.open(write)
        if (write) throw IOException("imagem compactada: só leitura")
        return Comprimido.abrir(real.open(false))
    }

    override fun totalSpace() = real.totalSpace()
    override fun usableSpace() = real.usableSpace()
    override fun equals(other: Any?) = other is ArvoreIso && other.real == real
    override fun hashCode() = real.hashCode()
    override fun toString() = path

    companion object {
        // Tamanho descompactado (lido do cabeçalho) por arquivo; a listagem não abre o mesmo arquivo de novo
        private val tamanhos = ConcurrentHashMap<String, Pair<Long, Long>>()

        private fun tamanho(f: VFile): Long {
            val chave = f.path
            val marca = f.length() xor (f.lastModified() shl 1)
            tamanhos[chave]?.let { if (it.first == marca) return it.second }
            val t = try { f.open(false).use { Comprimido.tamanhoReal(it) } } catch (e: IOException) { -1L }
            val r = if (t < 0) f.length() else t
            tamanhos[chave] = marca to r
            return r
        }
    }
}
