package br.ps2ponte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import br.ps2ponte.core.ferramentas.IdJogo
import br.ps2ponte.core.ferramentas.Png8
import br.ps2ponte.core.ferramentas.Tar
import br.ps2ponte.core.fs.Comprimido
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Ferramentas da pasta do OPL (aba "Ferramentas"): organizar, capas, art.tar, compactar/descompactar.
 * Uma tarefa por vez, numa thread própria (continua se a tela fechar), com wake lock e progresso em [Estado].
 */
object Ferramentas {
    @Volatile var titulo: String? = null
        private set
    /** 0..1000, ou -1 sem medida. */
    @Volatile var progresso = -1
        private set
    @Volatile var detalhe = ""
        private set
    @Volatile private var cancelado = false

    val ocupado get() = titulo != null

    class Cancelado : Exception()

    /** Chamado pelas tarefas: atualiza o progresso e para se o usuário cancelou. */
    fun passo(feito: Long, total: Long, texto: String = detalhe) {
        if (cancelado) throw Cancelado()
        progresso = if (total > 0) (feito * 1000 / total).toInt() else -1
        val mudou = texto != detalhe
        detalhe = texto
        // No máximo ~4 avisos por segundo: redesenhar a tela a cada bloco pesaria em celular fraco
        val agora = System.currentTimeMillis()
        if (mudou || agora - ultimoAviso >= 250) {
            ultimoAviso = agora
            Estado.avisar()
        }
    }

    @Volatile private var ultimoAviso = 0L

    fun cancelar() {
        cancelado = true
    }

    /** Roda [corpo] em segundo plano; o texto que ele devolve vai para o log. */
    fun rodar(ctx: Context, nome: String, corpo: () -> String) {
        if (ocupado) return
        val app = ctx.applicationContext
        titulo = nome
        progresso = -1
        detalhe = ""
        cancelado = false
        Estado.avisar()
        Thread({
            val wl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketps2:ferramentas").apply { acquire(6 * 60 * 60 * 1000L) }
            try {
                Estado.log("🧰 $nome…")
                Estado.log("🧰 " + corpo())
            } catch (e: Cancelado) {
                Estado.log("🧰 " + app.getString(R.string.ft_cancelado, nome))
            } catch (e: Exception) {
                Estado.log("🧰 " + app.getString(R.string.ft_erro, nome, e.message ?: e.javaClass.simpleName))
            } finally {
                if (wl.isHeld) wl.release()
                titulo = null
                progresso = -1
                detalhe = ""
                Estado.avisar()
            }
        }, "pocketps2-ferramentas").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    // ------------------------------------------------------------------ jogos

    val PASTAS_OPL = listOf("DVD", "CD", "ART", "CFG", "CHT", "LNG", "THM", "VMC", "POPS", "APPS")
    private val EXT_JOGO = listOf(".iso", ".zso", ".cso")
    private const val LIMITE_CD = 700L * 1024 * 1024

    class Jogo(val arquivo: VFile, val pasta: String) {
        val nome get() = arquivo.name
        val compactado get() = Comprimido.ehCompactado(nome)
        var id: String? = null
    }

    fun ehJogo(nome: String) = EXT_JOGO.any { nome.endsWith(it, true) }

    private fun pasta(raiz: VFile, nome: String): VFile? =
        raiz.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(nome, true) }

    fun jogos(raiz: VFile): List<Jogo> = listOf("DVD", "CD").flatMap { p ->
        pasta(raiz, p)?.listFiles()?.filter { it.isFile && ehJogo(it.name) }?.map { Jogo(it, p) } ?: emptyList()
    }.sortedBy { it.nome.lowercase() }

    /** Abre o jogo já descompactado (ISO, ZSO ou CSO). */
    private fun abrirIso(f: VFile): VHandle = if (Comprimido.ehCompactado(f.name)) Comprimido.abrir(f.open(false)) else f.open(false)

    fun lerId(j: Jogo): String? {
        j.id?.let { return it }
        return try { abrirIso(j.arquivo).use { IdJogo.ler(it) } } catch (e: IOException) { null }.also { j.id = it }
    }

    private fun tamanhoReal(f: VFile): Long =
        if (Comprimido.ehCompactado(f.name)) try { f.open(false).use { Comprimido.tamanhoReal(it) } } catch (e: IOException) { f.length() } else f.length()

    // ------------------------------------------------------------------ organizar

    /** Cria as pastas do OPL e leva para DVD/ ou CD/ os jogos soltos na raiz (ou em subpastas que não são do OPL). */
    fun organizar(ctx: Context, raiz: VFile): String {
        for (n in PASTAS_OPL) if (pasta(raiz, n) == null) raiz.child(n).mkdirs()
        val dvd = pasta(raiz, "DVD") ?: throw IOException(ctx.getString(R.string.ft_sem_pasta, "DVD"))
        val cd = pasta(raiz, "CD") ?: throw IOException(ctx.getString(R.string.ft_sem_pasta, "CD"))
        val soltos = mutableListOf<VFile>()
        for (f in raiz.listFiles().orEmpty()) {
            if (f.isFile && ehJogo(f.name)) soltos += f
            else if (f.isDirectory && PASTAS_OPL.none { it.equals(f.name, true) } && !f.name.startsWith(".")) {
                f.listFiles()?.filter { it.isFile && ehJogo(it.name) }?.let { soltos += it }
            }
        }
        var movidos = 0
        val falhas = mutableListOf<String>()
        soltos.forEachIndexed { i, f ->
            passo(i.toLong(), soltos.size.toLong(), f.name)
            val destino = (if (tamanhoReal(f) <= LIMITE_CD) cd else dvd).child(f.name)
            if (destino.exists() || !f.renameTo(destino)) falhas += f.name else movidos++
        }
        val sb = StringBuilder(ctx.getString(R.string.ft_organizado, movidos))
        if (falhas.isNotEmpty()) sb.append("\n").append(ctx.getString(R.string.ft_nao_movidos, falhas.joinToString()))
        val longos = jogos(raiz).count { it.nome.length > 64 }
        if (longos > 0) sb.append("\n").append(ctx.getString(R.string.ft_nomes_longos, longos))
        return sb.toString()
    }

    // ------------------------------------------------------------------ capas

    private fun arquivosArt(raiz: VFile): List<VFile> = pasta(raiz, "ART")?.listFiles()?.filter { it.isFile } ?: emptyList()

    /** Baixa as capas que faltam (banco xlenore/ps2-covers) e grava como PNG 8 bits: ART/<ID>_COV.png. */
    fun baixarCapas(ctx: Context, raiz: VFile, maxL: Int, maxA: Int): String {
        val art = pasta(raiz, "ART") ?: raiz.child("ART").also { it.mkdirs() }
        val existentes = arquivosArt(raiz).map { it.name.uppercase() }.toHashSet()
        val lista = jogos(raiz)
        var baixadas = 0
        var semId = 0
        val naoAchadas = mutableListOf<String>()
        lista.forEachIndexed { i, j ->
            passo(i.toLong(), lista.size.toLong(), j.nome)
            val id = lerId(j) ?: run { semId++; return@forEachIndexed }
            if (existentes.any { it.startsWith("${id}_COV.") }) return@forEachIndexed
            val bytes = baixar(ctx, "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/${IdJogo.serial(id)}.jpg")
            if (bytes == null) { naoAchadas += id; return@forEachIndexed }
            val png = paraPng8(bytes, maxL, maxA) ?: run { naoAchadas += id; return@forEachIndexed }
            gravar(art.child("${id}_COV.png"), png)
            existentes += "${id}_COV.PNG"
            baixadas++
        }
        val sb = StringBuilder(ctx.getString(R.string.ft_capas_baixadas, baixadas, lista.size))
        if (semId > 0) sb.append("\n").append(ctx.getString(R.string.ft_sem_id, semId))
        if (naoAchadas.isNotEmpty()) sb.append("\n").append(ctx.getString(R.string.ft_capas_nao_achadas, naoAchadas.joinToString()))
        return sb.toString()
    }

    /** Converte JPG/WebP/BMP/PNG da pasta ART para PNG indexado de 8 bits (o que o OPL lê sem erro). */
    fun corrigirCapas(ctx: Context, raiz: VFile): String {
        val imagens = arquivosArt(raiz).filter { f -> listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp").any { f.name.endsWith(it, true) } }
        var convertidas = 0
        val falhas = mutableListOf<String>()
        imagens.forEachIndexed { i, f ->
            passo(i.toLong(), imagens.size.toLong(), f.name)
            val dados = ler(f)
            if (f.name.endsWith(".png", true) && Png8.jaEh8Bits(dados)) return@forEachIndexed
            // Não aumenta nada; só reduz o que passar de 640x480 (o tamanho do fundo, _BG)
            val png = paraPng8(dados, 640, 480) ?: run { falhas += f.name; return@forEachIndexed }
            val base = f.name.substringBeforeLast('.')
            val destino = f.parentFile!!.child("$base.png")
            if (destino == f || destino.path == f.path) {
                gravar(f, png)
            } else {
                gravar(destino, png)
                f.delete()
            }
            convertidas++
        }
        val sb = StringBuilder(ctx.getString(R.string.ft_capas_corrigidas, convertidas, imagens.size))
        if (falhas.isNotEmpty()) sb.append("\n").append(ctx.getString(R.string.ft_capas_falharam, falhas.joinToString()))
        return sb.toString()
    }

    /** Os PNG da pasta ART -> ART/art.tar (sem compactação), para o carregamento rápido do RiptOPL. */
    fun empacotarArt(ctx: Context, raiz: VFile): String {
        val art = pasta(raiz, "ART") ?: throw IOException(ctx.getString(R.string.ft_sem_pasta, "ART"))
        val pngs = arquivosArt(raiz).filter { it.name.endsWith(".png", true) && it.name.toByteArray().size <= 100 }
        if (pngs.isEmpty()) return ctx.getString(R.string.ft_art_vazia)
        val tmp = art.child("art.tar.part")
        if (tmp.exists()) tmp.delete()
        var total = 0L
        tmp.open(true).use { h ->
            val t = Tar(h)
            pngs.forEachIndexed { i, f ->
                passo(i.toLong(), pngs.size.toLong(), f.name)
                val d = ler(f)
                t.adicionar(f.name, d, f.lastModified())
                total += d.size
            }
            t.terminar()
        }
        val final = art.child("art.tar")
        if (final.exists() && !final.delete()) throw IOException(ctx.getString(R.string.ft_nao_substituiu, "art.tar"))
        if (!tmp.renameTo(final)) throw IOException(ctx.getString(R.string.ft_nao_substituiu, "art.tar"))
        return ctx.getString(R.string.ft_art_ok, pngs.size, total / (1024.0 * 1024))
    }

    private fun paraPng8(dados: ByteArray, maxL: Int, maxA: Int): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(dados, 0, dados.size, o)
        if (o.outWidth <= 0) return null
        // Decodifica já reduzido quando a imagem é muito maior (economiza memória em celular fraco)
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= maxL && o.outHeight / (amostra * 2) >= maxA) amostra *= 2
        val bmp = BitmapFactory.decodeByteArray(dados, 0, dados.size, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
        val (l, a) = Png8.caber(bmp.width, bmp.height, maxL, maxA)
        val final = if (l != bmp.width || a != bmp.height) Bitmap.createScaledBitmap(bmp, l, a, true).also { if (it !== bmp) bmp.recycle() } else bmp
        val px = IntArray(l * a)
        final.getPixels(px, 0, l, 0, 0, l, a)
        final.recycle()
        return Png8.codificar(px, l, a)
    }

    private fun baixar(ctx: Context, url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "PocketPS2Server")
            if (c.responseCode != 200) return null
            c.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            if (e is java.net.UnknownHostException || e is java.net.ConnectException) throw IOException(ctx.getString(R.string.ft_sem_internet))
            null
        } finally {
            c.disconnect()
        }
    }

    private fun ler(f: VFile): ByteArray = f.open(false).use { h ->
        val n = h.length().toInt()
        ByteArray(n).also { h.readFully(0, it, 0, n) }
    }

    private fun gravar(f: VFile, dados: ByteArray) {
        f.open(true).use { h ->
            h.setLength(0)
            h.write(0, dados, 0, dados.size)
        }
    }

    // ------------------------------------------------------------------ compactar

    /** ISO -> ZSO (o OPL lê ZSO direto; para o Neutrino/UDPBD o app descompacta na hora). */
    fun compactar(ctx: Context, lista: List<Jogo>, apagarOriginal: Boolean): String {
        var economia = 0L
        var feitos = 0
        val totalGeral = lista.sumOf { it.arquivo.length() }
        var base = 0L
        for (j in lista) {
            val f = j.arquivo
            val pai = f.parentFile ?: continue
            val nome = f.name.substringBeforeLast('.')
            val livre = f.usableSpace()
            if (livre in 1 until f.length()) throw IOException(ctx.getString(R.string.ft_sem_espaco, f.name))
            val tmp = pai.child("$nome.zso.part")
            if (tmp.exists()) tmp.delete()
            val tam = try {
                f.open(false).use { e ->
                    tmp.open(true).use { s ->
                        Comprimido.compactar(e, s, zso = true) { feito, _ ->
                            passo(base + feito, totalGeral, f.name)
                            true
                        }
                    }
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            conferir(f, tmp)
            val final = pai.child("$nome.zso")
            if (final.exists()) final.delete()
            if (!tmp.renameTo(final)) throw IOException(ctx.getString(R.string.ft_nao_substituiu, final.name))
            val original = f.length()
            economia += original - tam
            if (apagarOriginal) f.delete()
            base += original
            feitos++
        }
        return ctx.getString(R.string.ft_compactados, feitos, economia / (1024.0 * 1024))
    }

    /** ZSO/CSO -> ISO. */
    fun descompactar(ctx: Context, lista: List<Jogo>, apagarOriginal: Boolean): String {
        var feitos = 0
        val totais = lista.map { tamanhoReal(it.arquivo) }
        val totalGeral = totais.sum()
        var base = 0L
        lista.forEachIndexed { k, j ->
            val f = j.arquivo
            val pai = f.parentFile ?: return@forEachIndexed
            val nome = f.name.substringBeforeLast('.')
            val livre = f.usableSpace()
            if (livre in 1 until totais[k]) throw IOException(ctx.getString(R.string.ft_sem_espaco, f.name))
            val tmp = pai.child("$nome.iso.part")
            if (tmp.exists()) tmp.delete()
            try {
                Comprimido.abrir(f.open(false)).use { e ->
                    tmp.open(true).use { s ->
                        s.setLength(0)
                        val buf = ByteArray(1 shl 20)
                        var pos = 0L
                        val tam = e.length()
                        while (pos < tam) {
                            val n = e.readFully(pos, buf, 0, minOf(buf.size.toLong(), tam - pos).toInt())
                            if (n <= 0) throw IOException("fim inesperado em ${f.name}")
                            s.write(pos, buf, 0, n)
                            pos += n
                            passo(base + pos, totalGeral, f.name)
                        }
                    }
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            val final = pai.child("$nome.iso")
            if (final.exists()) final.delete()
            if (!tmp.renameTo(final)) throw IOException(ctx.getString(R.string.ft_nao_substituiu, final.name))
            if (apagarOriginal) f.delete()
            base += totais[k]
            feitos++
        }
        return ctx.getString(R.string.ft_descompactados, feitos)
    }

    /** Lê o ZSO recém-criado em pontos aleatórios e compara com a ISO antes de apagar qualquer coisa. */
    private fun conferir(iso: VFile, zso: VFile) {
        iso.open(false).use { a ->
            Comprimido.abrir(zso.open(false)).use { b ->
                if (a.length() != b.length()) throw IOException("ZSO com tamanho errado")
                val r = Random(a.length())
                val x = ByteArray(65536)
                val y = ByteArray(65536)
                repeat(32) { k ->
                    val pos = if (k == 0) 0L else if (k == 1) maxOf(0L, a.length() - x.size) else (r.nextLong(maxOf(1L, a.length())))
                    val n = a.readFully(pos, x, 0, x.size)
                    if (b.readFully(pos, y, 0, n) != n || !x.copyOf(n).contentEquals(y.copyOf(n))) throw IOException("ZSO não confere com a ISO")
                }
            }
        }
    }
}
