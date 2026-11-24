package br.ps2ponte

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.ps2ponte.core.ferramentas.Catalogo
import br.ps2ponte.core.fs.VFile
import java.util.concurrent.Executors

/**
 * Grade de jogos com capa (aba "Jogos"): PS2 na proporção da caixa de DVD (140×200), PS1 quadrado.
 * As capas vêm de ART/ (o mesmo nome que o loader usa), carregadas numa thread e reduzidas ao tamanho da
 * célula, com cache limitado a 1/8 da memória do app: leve mesmo em celular fraco.
 */
class GradeJogos(
    private val act: Activity,
    /** Estilo da tela (ver MainActivity.Estilo): escala dos tamanhos, do texto, largura da coluna e modo lista. */
    private val escala: Float = 1f,
    private val texto: Float = 1f,
    private val colunaDp: Int = 104,
    private val lista: Boolean = false,
    private val aoTocar: (Catalogo.Item) -> Unit,
) {
    val view: GridView
    private val adaptador = Adaptador()
    private var itens: List<Catalogo.Item> = emptyList()
    private var catalogo: Catalogo? = null
    private var filtro = ""

    private val carregador = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    /** Capas que não existem (para não procurar de novo a cada rolagem). */
    private val semCapa = HashSet<String>()

    // Raiz da pasta e arquivos da ART/, resolvidos na thread do carregador
    @Volatile private var pasta: String? = null
    private var raiz: VFile? = null
    private var arte: Map<String, VFile> = emptyMap()
    private var arteDe: Catalogo? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density * escala).toInt()

    init {
        view = GridView(act).apply {
            if (lista) {
                numColumns = 1
                verticalSpacing = dp(6)
            } else {
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(colunaDp)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(10)
                verticalSpacing = dp(14)
            }
            setPadding(dp(12), dp(8), dp(12), dp(24))
            clipToPadding = false
            adapter = adaptador
            setOnItemClickListener { _, _, pos, _ -> itens.getOrNull(pos)?.let(aoTocar) }
        }
    }

    /** Atualiza com o catálogo atual (chamado quando o [Estado] avisa). Barato se nada mudou. */
    fun mostrar(pastaAtual: String?, c: Catalogo?) {
        if (pastaAtual != pasta) {
            pasta = pastaAtual
            carregador.execute { raiz = null; arte = emptyMap(); arteDe = null }
            cache.evictAll()
            synchronized(semCapa) { semCapa.clear() }
        }
        if (c === catalogo) return
        if (c != null && catalogo != null && c.arte != catalogo!!.arte) synchronized(semCapa) { semCapa.clear() }
        catalogo = c
        filtrar(filtro)
    }

    fun filtrar(texto: String) {
        filtro = texto.trim()
        val c = catalogo
        // PS2 primeiro e PS1 depois: as linhas da grade ficam com capas do mesmo formato
        itens = c?.jogos?.filter { filtro.isEmpty() || titulo(it).contains(filtro, true) || it.id?.contains(filtro, true) == true }
            ?.sortedWith(compareBy({ it.ps1 }, { titulo(it).lowercase() })) ?: emptyList()
        adaptador.notifyDataSetChanged()
    }

    val quantos get() = itens.size

    fun fechar() = carregador.shutdownNow()

    // ------------------------------------------------------------------ capas

    private fun chave(i: Catalogo.Item) = i.chaveArte?.uppercase()

    private fun capa(i: Catalogo.Item, alvo: ImageView, largura: Int) {
        val k = chave(i)
        alvo.tag = k
        alvo.setImageDrawable(null)
        if (k == null) return
        cache.get(k)?.let { alvo.setImageBitmap(it); return }
        if (synchronized(semCapa) { k in semCapa }) return
        val c = catalogo
        val p = pasta ?: return
        carregador.execute {
            if (alvo.tag != k) return@execute // a célula já foi reaproveitada
            val bmp = try { carregar(p, c, k, largura) } catch (e: Exception) { null }
            if (bmp == null) {
                synchronized(semCapa) { semCapa += k }
                return@execute
            }
            cache.put(k, bmp)
            principal.post { if (alvo.tag == k) alvo.setImageBitmap(bmp) }
        }
    }

    /** Só na thread do carregador. */
    private fun carregar(p: String, c: Catalogo?, k: String, largura: Int): Bitmap? {
        val r = raiz ?: Fonte.raiz(act, p).also { raiz = it }
        if (arteDe !== c || arte.isEmpty()) {
            val dir = r.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("ART", true) }
            arte = dir?.listFiles()?.filter { it.isFile }?.associateBy { it.name.uppercase() } ?: emptyMap()
            arteDe = c
        }
        val f = arte["${k}_COV.PNG"] ?: arte["${k}_COV.JPG"] ?: arte["${k}_COV.JPEG"] ?: return null
        val dados = f.open(false).use { h -> ByteArray(h.length().toInt()).also { h.readFully(0, it, 0, it.size) } }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(dados, 0, dados.size, o)
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= largura) amostra *= 2
        return BitmapFactory.decodeByteArray(dados, 0, dados.size, BitmapFactory.Options().apply {
            inSampleSize = amostra
            inPreferredConfig = Bitmap.Config.RGB_565 // metade da memória; capa não precisa de transparência
        })
    }

    // ------------------------------------------------------------------ células

    /** Moldura que mantém a proporção da capa (altura = largura × [proporcao]). */
    private class Moldura(ctx: android.content.Context) : FrameLayout(ctx) {
        var proporcao = 200f / 140f
        override fun onMeasure(w: Int, h: Int) {
            val largura = MeasureSpec.getSize(w)
            super.onMeasure(MeasureSpec.makeMeasureSpec(largura, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((largura * proporcao).toInt(), MeasureSpec.EXACTLY))
        }
    }

    private class Celula(val moldura: Moldura, val img: ImageView, val vazio: TextView, val selo: TextView, val nome: TextView, val info: TextView?)

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = itens.size
        override fun getItem(pos: Int) = itens[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: nova()
            val c = v.tag as Celula
            val i = itens[pos]
            c.moldura.proporcao = if (i.ps1) 1f else 200f / 140f
            c.nome.text = titulo(i)
            c.vazio.text = if (lista) "" else titulo(i)
            c.info?.text = listOfNotNull(
                when (i.tipo) { Catalogo.Tipo.PS2 -> if (i.compactado) "PS2 · ZSO" else "PS2"; Catalogo.Tipo.VCD -> "PS1 · POPS"; Catalogo.Tipo.CUE -> "PS1 · Ember" },
                i.id,
                "${i.tamanho / (1024 * 1024)} MiB",
            ).joinToString(" · ")
            c.selo.text = when {
                i.foraDoLugar -> "📁"
                i.tipo == Catalogo.Tipo.VCD -> "PS1"
                i.tipo == Catalogo.Tipo.CUE -> "PS1 · Ember"
                i.compactado -> "ZSO"
                else -> ""
            }
            c.selo.visibility = if (c.selo.text.isEmpty() || lista) View.GONE else View.VISIBLE
            if (lista && i.foraDoLugar) c.info?.text = "📁 " + c.info?.text
            c.moldura.requestLayout()
            val largura = if (lista) dp(48) else (parent as GridView).columnWidth.takeIf { it > 0 } ?: dp(colunaDp)
            // O nome fica por baixo da imagem: aparece enquanto a capa carrega ou se ela não existir
            capa(i, c.img, largura)
            return v
        }

        private fun nova(): View {
            val raiz = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val moldura = Moldura(act).apply {
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x33808080) }
            }
            val vazio = TextView(act).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                textSize = 12f * texto
                alpha = 0.7f
            }
            val img = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val selo = TextView(act).apply {
                textSize = 10f * texto
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xB0000000.toInt()) }
            }
            moldura.addView(vazio, FrameLayout.LayoutParams(-1, -1))
            moldura.addView(img, FrameLayout.LayoutParams(-1, -1))
            moldura.addView(selo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
            val nome = TextView(act).apply {
                textSize = (if (lista) 14f else 12f) * texto
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = if (lista) Gravity.START else Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            if (lista) {
                // Linha: capinha pequena à esquerda, nome e detalhes ao lado
                raiz.orientation = LinearLayout.HORIZONTAL
                raiz.gravity = Gravity.CENTER_VERTICAL
                val info = TextView(act).apply { textSize = 12f * texto; alpha = 0.7f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                val textos = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(nome)
                    addView(info)
                }
                raiz.addView(moldura, LinearLayout.LayoutParams(dp(48), -2))
                raiz.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
                raiz.tag = Celula(moldura, img, vazio, selo, nome, info)
                return raiz
            }
            raiz.addView(moldura, LinearLayout.LayoutParams(-1, -2))
            raiz.addView(nome, LinearLayout.LayoutParams(-1, -2))
            raiz.tag = Celula(moldura, img, vazio, selo, nome, null)
            return raiz
        }
    }

    companion object {
        private val PREFIXO_ID = Regex("""^[A-Z]{4}[_-]\d{3}\.\d{2}\.""", RegexOption.IGNORE_CASE)

        /** Nome para mostrar: sem extensão, sem o "SLUS_123.45." da convenção antiga; pasta do Ember. */
        fun titulo(i: Catalogo.Item): String =
            (i.pastaEmber ?: i.base).replace(PREFIXO_ID, "").trim().ifEmpty { i.base }
    }
}
