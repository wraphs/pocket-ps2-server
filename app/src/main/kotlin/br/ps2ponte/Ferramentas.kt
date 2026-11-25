package br.ps2ponte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import br.ps2ponte.core.ferramentas.Catalogo
import br.ps2ponte.core.ferramentas.IdJogo
import br.ps2ponte.core.ferramentas.OrganizadorPs1
import br.ps2ponte.core.ferramentas.Png8
import br.ps2ponte.core.ferramentas.Tar
import br.ps2ponte.core.ferramentas.Verificador
import br.ps2ponte.core.fs.Comprimido
import br.ps2ponte.core.fs.LocalFile
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.fs.Sobreposicao
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.io.File
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

    /**
     * Roda [corpo] em segundo plano; o texto que ele devolve vai para o log.
     * [silencioso]: não anuncia o começo e só escreve no log se o resultado tiver algo (rotina automática).
     */
    fun rodar(ctx: Context, nome: String, silencioso: Boolean = false, corpo: () -> String) {
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
                if (!silencioso) Estado.logJogos("🧰 $nome…")
                val r = corpo()
                if (!silencioso || r.isNotBlank()) Estado.logJogos("🧰 " + r)
            } catch (e: Cancelado) {
                Estado.logJogos("🧰 " + app.getString(R.string.ft_cancelado, nome))
            } catch (e: Exception) {
                Estado.logJogos("🧰 " + app.getString(R.string.ft_erro, nome, e.message ?: e.javaClass.simpleName))
            } finally {
                if (wl.isHeld) wl.release()
                titulo = null
                progresso = -1
                detalhe = ""
                Estado.avisar()
            }
        }, "pocketps2-ferramentas").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    // ------------------------------------------------------------------ catálogo

    /** Catálogo da pasta [catalogoDe] (carregado do disco e refeito a cada abertura do app). */
    @Volatile var catalogo: Catalogo? = null
        private set
    @Volatile private var catalogoDe: String? = null
    @Volatile private var ultimaRotina = 0L

    const val PREF_AUTO_TAR = "auto_tar"
    const val PREF_AUTO_ZSO = "auto_zso"
    const val PREF_AUTO_ORGANIZAR = "auto_organizar"
    const val PREF_PS1_DESTINO = "ps1_destino"

    private fun arqCatalogo(ctx: Context, pasta: String) = File(ctx.filesDir, "catalogo-${Integer.toHexString(pasta.hashCode())}.txt")

    /** Lê o catálogo salvo da pasta (rápido: só o arquivo de texto). */
    fun carregarCatalogo(ctx: Context, pasta: String) {
        if (catalogoDe == pasta) return
        catalogo = try { arqCatalogo(ctx, pasta).takeIf { it.exists() }?.readText()?.let { Catalogo.ler(it) } } catch (e: IOException) { null }
        catalogoDe = pasta
    }

    /** Varre a pasta (reaproveitando o que não mudou) e salva. */
    fun atualizarCatalogo(ctx: Context, pasta: String, raiz: VFile): Catalogo {
        carregarCatalogo(ctx, pasta)
        val novo = Catalogo.varrer(raiz, catalogo) { nome -> passo(0, -1, ctx.getString(R.string.ft_lendo_jogo, nome)) }
        try { arqCatalogo(ctx, pasta).writeText(novo.texto()) } catch (_: IOException) {}
        catalogo = novo
        catalogoDe = pasta
        Estado.avisar()
        return novo
    }

    /**
     * Rotina ao abrir o app: atualiza o catálogo e, conforme os interruptores, organiza (as conversões de PS1
     * ficam esperando o "sim" em [pedido]), compacta as ISOs novas (só com o servidor parado: o PS2 pode estar
     * lendo) e refaz o art.tar. No máximo uma vez por minuto.
     */
    fun rotina(ctx: Context, pasta: String, forcar: Boolean = false) {
        val agora = System.currentTimeMillis()
        if (ocupado || (!forcar && catalogoDe == pasta && agora - ultimaRotina < 60_000)) return
        ultimaRotina = agora
        val prefs = ctx.getSharedPreferences("ps2ponte", Context.MODE_PRIVATE)
        rodar(ctx, ctx.getString(R.string.ft_atualizando), silencioso = true) {
            val raiz = raizDe(ctx, pasta)
            var c = atualizarCatalogo(ctx, pasta, raiz)
            val msgs = mutableListOf<String>()
            // Pendrive só leitura: mover e compactar jogos não dá (o art.tar vai para a pasta de apoio)
            val podeMexer = !soLeitura
            if (podeMexer && prefs.getBoolean(PREF_AUTO_ORGANIZAR, false) && !Estado.rodando && c.foraDoLugar + c.ps1.size > 0) {
                val o = planejarOrganizacao(ctx, pasta, raiz)
                if (!o.vazio) {
                    if (o.plano.conversoes == 0) msgs += executarOrganizacao(ctx, o, apagarConvertidos = false)
                    else pedido = o // a tela pergunta antes de converter
                    c = catalogo ?: c
                }
            }
            if (podeMexer && prefs.getBoolean(PREF_AUTO_ZSO, false) && !Estado.rodando && c.isos.isNotEmpty()) {
                msgs += ctx.getString(R.string.ft_auto_zso) + " " + compactar(ctx, jogosDe(raiz, c.isos), apagarOriginal = true)
                c = atualizarCatalogo(ctx, pasta, raiz)
            }
            if (prefs.getBoolean(PREF_AUTO_TAR, false) && c.tarDesatualizado) {
                msgs += ctx.getString(R.string.ft_auto_tar) + " " + empacotarArt(ctx, raiz)
                atualizarCatalogo(ctx, pasta, raiz)
            }
            msgs.joinToString("\n")
        }
    }

    @Volatile private var gravavelDe: Pair<String, Boolean>? = null

    /** A pasta de verdade aceita gravação? (Pendrive em NTFS costuma ser montado só para leitura no Android.) */
    private fun gravavel(pasta: String, raiz: VFile): Boolean {
        gravavelDe?.let { if (it.first == pasta) return it.second }
        val teste = raiz.child(".pocketps2-teste")
        val ok = try {
            teste.open(true).close()
            teste.delete()
            true
        } catch (e: Exception) {
            // Qualquer falha (E/S, provedor do SAF, root) conta como "não grava"
            false
        }
        gravavelDe = pasta to ok
        return ok
    }

    /** Pasta de apoio no celular (sem permissão extra: é a pasta do próprio app). */
    fun pastaApoio(ctx: Context, pasta: String): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "apoio/${Integer.toHexString(pasta.hashCode())}").apply { mkdirs() }

    /**
     * A pasta como os servidores e as ferramentas devem vê-la: ela mesma, ou, se for só leitura, ela com a pasta
     * de apoio por cima (capas, art.tar, CFG, VMC e games.bin gravados no celular; o PS2 vê tudo junto).
     * Pode bloquear (root/Shizuku/SAF): não chame na thread principal.
     */
    fun raizDe(ctx: Context, pasta: String): VFile {
        val r = Fonte.raiz(ctx, pasta)
        if (gravavel(pasta, r)) return r
        return Sobreposicao.raiz(r, LocalFile.root(pastaApoio(ctx, pasta)))
    }

    /** A última pasta testada é só leitura (e está usando a pasta de apoio). */
    val soLeitura get() = gravavelDe?.second == false

    /** Mover ou compactar jogos precisa gravar no próprio pendrive (não dá para copiar 4 GB para o celular). */
    fun exigirPendrive(ctx: Context) {
        if (soLeitura) throw IOException(ctx.getString(R.string.ft_so_leitura_mover))
    }

    /** Organização que precisa de confirmação (conversões de PS1), achada pela rotina automática. */
    @Volatile var pedido: Organizacao? = null

    /** Depois de uma ferramenta manual: art.tar automático (se ligado) e catálogo em dia. */
    fun depois(ctx: Context, pasta: String, raiz: VFile, mexeuNaArte: Boolean): String {
        val prefs = ctx.getSharedPreferences("ps2ponte", Context.MODE_PRIVATE)
        val c = atualizarCatalogo(ctx, pasta, raiz)
        if (mexeuNaArte && prefs.getBoolean(PREF_AUTO_TAR, false) && c.tarDesatualizado) {
            val r = empacotarArt(ctx, raiz)
            atualizarCatalogo(ctx, pasta, raiz)
            return "\n" + ctx.getString(R.string.ft_auto_tar) + " " + r
        }
        return ""
    }

    // ------------------------------------------------------------------ jogos

    val PASTAS_OPL = listOf("DVD", "CD", "ART", "CFG", "CHT", "LNG", "THM", "VMC", "POPS", "APPS")
    private const val LIMITE_CD = 700L * 1024 * 1024

    class Jogo(val arquivo: VFile, val pasta: String) {
        val nome get() = arquivo.name
        val compactado get() = Comprimido.ehCompactado(nome)
        var id: String? = null
    }

    private fun pasta(raiz: VFile, nome: String): VFile? =
        raiz.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(nome, true) }

    /** Itens do catálogo como arquivos da pasta (os que ainda existem). */
    fun jogosDe(raiz: VFile, itens: List<Catalogo.Item>): List<Jogo> {
        val arvore = LocalTree(raiz)
        return itens.mapNotNull { i ->
            val f = arvore.resolve(i.rel)?.takeIf { it.isFile } ?: return@mapNotNull null
            Jogo(f, i.pasta).also { it.id = i.id }
        }
    }

    /** Abre o jogo já descompactado (ISO, ZSO ou CSO). */
    private fun abrirIso(f: VFile): VHandle = if (Comprimido.ehCompactado(f.name)) Comprimido.abrir(f.open(false)) else f.open(false)

    fun lerId(j: Jogo): String? {
        j.id?.let { return it }
        return try { abrirIso(j.arquivo).use { IdJogo.ler(it) } } catch (e: IOException) { null }.also { j.id = it }
    }

    private fun tamanhoReal(f: VFile): Long =
        if (Comprimido.ehCompactado(f.name)) try { f.open(false).use { Comprimido.tamanhoReal(it) } } catch (e: IOException) { f.length() } else f.length()

    // ------------------------------------------------------------------ organizar

    /** O que Organizar vai fazer: PS2 fora de DVD/CD e o plano dos jogos de PS1. Nada mudou no disco ainda. */
    class Organizacao(val pasta: String, val raiz: VFile, val ps2: List<Jogo>, val plano: OrganizadorPs1.Plano) {
        /** Pedido pelo botão (mostra "nada a fazer"); a rotina automática fica quieta. */
        var manual = false
        val vazio get() = ps2.isEmpty() && plano.vazio

        fun resumo(ctx: Context): String = buildString {
            if (ps2.isNotEmpty()) append(ctx.getString(R.string.ft_org_ps2, ps2.size)).append("\n")
            for (a in plano.acoes) append(if (a.conversao) "🔄 " else "• ").append(a.descricao).append("\n")
            for (a in plano.avisos) append(a).append("\n")
            if (plano.conversoes > 0) {
                val gb = plano.acoes.filter { it.conversao }.sumOf { it.bytes } / (1024.0 * 1024 * 1024)
                append("\n").append(ctx.getString(R.string.ft_org_conversao, plano.conversoes, gb))
            }
        }.trim()
    }

    fun destinoPs1(ctx: Context): OrganizadorPs1.Destino = try {
        OrganizadorPs1.Destino.valueOf(ctx.getSharedPreferences("ps2ponte", Context.MODE_PRIVATE).getString(PREF_PS1_DESTINO, "MANTER")!!)
    } catch (e: IllegalArgumentException) {
        OrganizadorPs1.Destino.MANTER
    }

    fun planejarOrganizacao(ctx: Context, pasta: String, raiz: VFile): Organizacao {
        exigirPendrive(ctx)
        val c = atualizarCatalogo(ctx, pasta, raiz)
        val ps2 = jogosDe(raiz, c.jogos.filter { !it.ps1 && it.foraDoLugar })
        return Organizacao(pasta, raiz, ps2, OrganizadorPs1(raiz).planejar(c, destinoPs1(ctx)))
    }

    /**
     * Cria as pastas do OPL, leva os jogos de PS2 fora do lugar para DVD/ ou CD/ e executa o plano de PS1
     * (POPS/ e EMBER/games/, multidisco, conversões). [apagarConvertidos] só vale para as conversões.
     */
    fun executarOrganizacao(ctx: Context, o: Organizacao, apagarConvertidos: Boolean, temp: File? = null): String {
        val raiz = o.raiz
        for (n in PASTAS_OPL) if (pasta(raiz, n) == null) raiz.child(n).mkdirs()
        val dvd = pasta(raiz, "DVD") ?: throw IOException(ctx.getString(R.string.ft_sem_pasta, "DVD"))
        val cd = pasta(raiz, "CD") ?: throw IOException(ctx.getString(R.string.ft_sem_pasta, "CD"))
        var movidos = 0
        val falhas = mutableListOf<String>()
        o.ps2.forEachIndexed { i, j ->
            val f = j.arquivo
            passo(i.toLong(), o.ps2.size.toLong(), f.name)
            val destino = (if (tamanhoReal(f) <= LIMITE_CD) cd else dvd).child(f.name)
            if (destino.exists() || !f.renameTo(destino)) falhas += f.name else movidos++
        }
        val falhasPs1 = o.plano.executar(apagarConvertidos, { feito, total, texto -> passo(feito, total, texto) }, temp?.let { LocalFile.root(it.apply { mkdirs() }) })
        val c = atualizarCatalogo(ctx, o.pasta, raiz)
        val sb = StringBuilder(ctx.getString(R.string.ft_organizado, movidos + o.plano.acoes.size - falhasPs1.size))
        if (falhas.isNotEmpty()) sb.append("\n").append(ctx.getString(R.string.ft_nao_movidos, falhas.joinToString()))
        for (f in falhasPs1) sb.append("\n✗ ").append(f)
        val longos = c.ps2.count { it.nome.length > 64 }
        if (longos > 0) sb.append("\n").append(ctx.getString(R.string.ft_nomes_longos, longos))
        return sb.toString()
    }

    // ------------------------------------------------------------------ capas

    private fun arquivosArt(raiz: VFile): List<VFile> = pasta(raiz, "ART")?.listFiles()?.filter { it.isFile } ?: emptyList()

    /**
     * Baixa as capas que faltam (bancos xlenore/ps2-covers e psx-covers) e grava como PNG 8 bits com o nome que
     * cada loader procura: ART/<código>_COV.png (PS2), ART/<nome do VCD>_COV.png (POPS), ART/<pasta>_COV.png (Ember).
     */
    fun baixarCapas(ctx: Context, pasta: String, raiz: VFile, maxL: Int, maxA: Int, so: String? = null): String {
        val art = pasta(raiz, "ART") ?: raiz.child("ART").also { it.mkdirs() }
        val existentes = arquivosArt(raiz).map { it.name.uppercase() }.toHashSet()
        val lista = atualizarCatalogo(ctx, pasta, raiz).jogos.filter { !it.foraDoLugar && (so == null || it.rel == so) }
        var baixadas = 0
        var semId = 0
        val naoAchadas = mutableListOf<String>()
        lista.forEachIndexed { i, j ->
            passo(i.toLong(), lista.size.toLong(), j.nome)
            val id = j.id ?: run { semId++; return@forEachIndexed }
            val chave = j.chaveArte ?: return@forEachIndexed
            if (existentes.any { it.startsWith("${chave.uppercase()}_COV.") }) return@forEachIndexed
            val banco = if (j.ps1) "psx-covers" else "ps2-covers"
            val bytes = baixar(ctx, "https://raw.githubusercontent.com/xlenore/$banco/main/covers/default/${IdJogo.serial(id)}.jpg")
            if (bytes == null) { naoAchadas += id; return@forEachIndexed }
            val png = paraPng8(bytes, maxL, maxA) ?: run { naoAchadas += id; return@forEachIndexed }
            gravar(art.child("${chave}_COV.png"), png)
            existentes += "${chave.uppercase()}_COV.PNG"
            baixadas++
        }
        val sb = StringBuilder(ctx.getString(R.string.ft_capas_baixadas, baixadas, lista.size))
        if (semId > 0) sb.append("\n").append(ctx.getString(R.string.ft_sem_id, semId))
        if (naoAchadas.isNotEmpty()) sb.append("\n").append(ctx.getString(R.string.ft_capas_nao_achadas, naoAchadas.joinToString()))
        return sb.toString()
    }

    /** Arquivos dos loaders (POPStarter, Ember, Neutrino, pastas do OPL). */
    fun verificar(ctx: Context, pasta: String, raiz: VFile): String {
        val c = atualizarCatalogo(ctx, pasta, raiz)
        val itens = Verificador.verificar(raiz, c)
        if (itens.isEmpty()) return ctx.getString(R.string.ft_verif_nada)
        return itens.groupBy { it.grupo }.entries.joinToString("\n\n") { (g, l) ->
            g + "\n" + l.joinToString("\n") { i ->
                val s = when (i.estado) {
                    Verificador.Estado.OK -> "✓"
                    Verificador.Estado.FALTA -> "✗"
                    Verificador.Estado.ERRADO -> "⚠"
                    Verificador.Estado.OPCIONAL -> "○"
                }
                "$s ${i.caminho}" + if (i.nota.isNotEmpty() && i.estado != Verificador.Estado.OK) "\n     ${i.nota}" else ""
            }
        } + "\n\n" + ctx.getString(R.string.ft_verif_legenda)
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

    // ------------------------------------------------------------------ espaço

    /** Outro lugar do celular com espaço (pasta do app no volume: não precisa de permissão). */
    class Lugar(val dir: File, val nome: String, val livre: Long)

    /** [precisa]: maior saída de um jogo (eles vão um por vez); [livre] no destino (0 = desconhecido). */
    class Espaco(val precisa: Long, val livre: Long, val lugares: List<Lugar>) {
        val falta get() = livre in 1 until precisa
        val lugar get() = lugares.firstOrNull { it.livre >= precisa + MARGEM }
    }

    private const val MARGEM = 32L * 1024 * 1024

    /** Volume de um caminho/pasta: "primary" (memória interna) ou o id do pendrive/cartão. */
    private fun volumeDe(caminhoOuPasta: String): String? {
        if (Fonte.ehSaf(caminhoOuPasta)) return try {
            android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(caminhoOuPasta)).substringBefore(':')
        } catch (e: Exception) { null }
        val c = if (Fonte.ehPriv(caminhoOuPasta)) br.ps2ponte.priv.Privilegio.caminho(caminhoOuPasta) else caminhoOuPasta
        val id = Regex("^/(?:storage|mnt/media_rw)/([^/]+)").find(c)?.groupValues?.get(1) ?: return null
        return if (id == "emulated" || id == "self") "primary" else id
    }

    /**
     * Quanto a compactação/descompactação de [itens] precisa e o que há livre; se faltar, quais outros volumes
     * (memória interna, cartão, outro pendrive) podem guardar a saída temporariamente.
     */
    fun espacoPara(ctx: Context, pasta: String, raiz: VFile, itens: List<Catalogo.Item>, compactar: Boolean): Espaco {
        val arvore = LocalTree(raiz)
        val precisa = itens.maxOfOrNull { i ->
            if (compactar) i.tamanho // pior caso: o ZSO do tamanho da ISO
            else arvore.resolve(i.rel)?.let { tamanhoReal(it) } ?: i.tamanho
        } ?: 0L
        return espacoCom(ctx, pasta, raiz, precisa)
    }

    /** Como [espacoPara], para uma necessidade já conhecida (ex.: as conversões de PS1 do Organizar). */
    fun espacoCom(ctx: Context, pasta: String, raiz: VFile, precisa: Long): Espaco {
        val livre = try { raiz.usableSpace() } catch (e: Exception) { 0L }
        val meu = volumeDe(pasta)
        val nomes = Compat.volumes(ctx).associate { (it.uuid ?: if (it.primario) "primary" else "") to it.descricao }
        val lugares = ctx.getExternalFilesDirs(null).filterNotNull().mapNotNull { d ->
            val v = volumeDe(d.path) ?: return@mapNotNull null
            if (v.equals(meu, true)) return@mapNotNull null
            Lugar(File(d, "temporario"), nomes[v] ?: nomes.entries.firstOrNull { it.key.equals(v, true) }?.value ?: v, d.usableSpace)
        }.sortedByDescending { it.livre }
        return Espaco(precisa, livre, lugares)
    }

    /** Onde montar a saída: na própria pasta, ou na pasta temporária [temp] de outro volume. */
    private fun saida(temp: File?, pai: VFile, nome: String): VFile =
        if (temp != null) LocalFile.root(temp.apply { mkdirs() }).child(nome) else pai.child(nome)

    /** Traz para a pasta do jogo o arquivo montado no lugar temporário (cópia com progresso) e apaga o temporário. */
    private fun trazer(ctx: Context, tmp: VFile, pai: VFile, nomeFinal: String, temp: File) {
        val parte = pai.child("$nomeFinal.part")
        if (parte.exists()) parte.delete()
        try {
            tmp.open(false).use { e ->
                parte.open(true).use { s ->
                    s.setLength(0)
                    val buf = ByteArray(1 shl 20)
                    var pos = 0L
                    val tam = e.length()
                    while (pos < tam) {
                        val n = e.readFully(pos, buf, 0, minOf(buf.size.toLong(), tam - pos).toInt())
                        if (n <= 0) throw IOException("fim inesperado")
                        s.write(pos, buf, 0, n)
                        pos += n
                        passo(pos, tam, ctx.getString(R.string.ft_trazendo, nomeFinal))
                    }
                }
            }
        } catch (e: Exception) {
            parte.delete()
            // O arquivo pronto fica no lugar temporário: nada se perde
            throw IOException(ctx.getString(R.string.ft_volta_falhou, nomeFinal, temp.path, e.message ?: ""))
        }
        val final = pai.child(nomeFinal)
        if (final.exists()) final.delete()
        if (!parte.renameTo(final)) throw IOException(ctx.getString(R.string.ft_nao_substituiu, nomeFinal))
        tmp.delete()
    }

    // ------------------------------------------------------------------ compactar

    /**
     * ISO -> ZSO (o OPL lê ZSO direto; para o Neutrino/UDPBD o app descompacta na hora).
     * [temp]: sem espaço na pasta, o ZSO é montado e conferido lá; a ISO é apagada (se pedido) e o ZSO volta.
     */
    fun compactar(ctx: Context, lista: List<Jogo>, apagarOriginal: Boolean, temp: File? = null): String {
        var economia = 0L
        var feitos = 0
        val totalGeral = lista.sumOf { it.arquivo.length() }
        var base = 0L
        for (j in lista) {
            val f = j.arquivo
            val pai = f.parentFile ?: continue
            val nome = f.name.substringBeforeLast('.')
            val livre = f.usableSpace()
            if (temp == null && livre in 1 until f.length()) throw IOException(ctx.getString(R.string.ft_sem_espaco, f.name))
            val tmp = saida(temp, pai, "$nome.zso.part")
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
            val original = f.length()
            if (temp != null) {
                // Primeiro libera o espaço da ISO, depois traz o ZSO conferido
                if (apagarOriginal && !f.delete()) throw IOException(ctx.getString(R.string.ft_nao_substituiu, f.name))
                trazer(ctx, tmp, pai, "$nome.zso", temp)
            } else {
                val final = pai.child("$nome.zso")
                if (final.exists()) final.delete()
                if (!tmp.renameTo(final)) throw IOException(ctx.getString(R.string.ft_nao_substituiu, final.name))
                if (apagarOriginal) f.delete()
            }
            economia += original - tam
            base += original
            feitos++
        }
        return ctx.getString(R.string.ft_compactados, feitos, economia / (1024.0 * 1024))
    }

    /** ZSO/CSO -> ISO. [temp] como em [compactar]. */
    fun descompactar(ctx: Context, lista: List<Jogo>, apagarOriginal: Boolean, temp: File? = null): String {
        var feitos = 0
        val totais = lista.map { tamanhoReal(it.arquivo) }
        val totalGeral = totais.sum()
        var base = 0L
        lista.forEachIndexed { k, j ->
            val f = j.arquivo
            val pai = f.parentFile ?: return@forEachIndexed
            val nome = f.name.substringBeforeLast('.')
            val livre = f.usableSpace()
            if (temp == null && livre in 1 until totais[k]) throw IOException(ctx.getString(R.string.ft_sem_espaco, f.name))
            val tmp = saida(temp, pai, "$nome.iso.part")
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
            if (temp != null) {
                if (apagarOriginal && !f.delete()) throw IOException(ctx.getString(R.string.ft_nao_substituiu, f.name))
                trazer(ctx, tmp, pai, "$nome.iso", temp)
            } else {
                val final = pai.child("$nome.iso")
                if (final.exists()) final.delete()
                if (!tmp.renameTo(final)) throw IOException(ctx.getString(R.string.ft_nao_substituiu, final.name))
                if (apagarOriginal) f.delete()
            }
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
