package br.ps2ponte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.ps2ponte.core.ferramentas.Catalogo
import br.ps2ponte.core.ferramentas.OrganizadorPs1
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.priv.Privilegio
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Duas abas embaixo (Servidor e Jogos) e as configurações no ⚙ do topo.
 *
 * - Servidor: escolhe o protocolo, mostra o que preencher no PS2 (sempre com os dados do celular), um botão
 *   Iniciar no meio e o log. Sem cabo ou sem jogos, o botão fica cinza e uma dica diz o que falta.
 * - Jogos: onde estão os jogos (pasta ou pendrive inteiro) e a grade de capas; capas e fichas se baixam sozinhas.
 * - ⚙: aparência, acesso aos arquivos, opções do servidor e ferramentas.
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val fundo = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // Abas
    private lateinit var abaServidor: View
    private lateinit var abaJogos: View
    private lateinit var navServidor: TextView
    private lateinit var navJogos: TextView

    // Servidor
    private val botoesProtocolo = LinkedHashMap<String, TextView>()
    private lateinit var btnIniciar: Button
    private lateinit var tvDica: TextView
    private lateinit var btnDica: Button
    private lateinit var cartaoStatus: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    // Jogos
    private lateinit var tvLocal: TextView
    private lateinit var tvLocalEstado: TextView
    private lateinit var btnLocalAcao: Button
    private lateinit var cartaoTarefa: LinearLayout
    private lateinit var tvTarefa: TextView
    private lateinit var barraTarefa: ProgressBar
    private lateinit var tvTarefaDetalhe: TextView
    private lateinit var tvResumo: TextView
    private lateinit var grade: GradeJogos
    private lateinit var tvVazio: TextView

    private var ultimoBytes = 0L
    private var ultimoTempo = 0L
    private var velocidade = ""

    /** Resultado da última verificação da pasta: null = verificando; true = abriu. */
    private var pastaAbriu: Boolean? = null
    private var temOpl: Boolean? = null
    private var erroPasta: String? = null
    private var verificacao = 0
    private var mostrandoPedido = false

    private val ouvinte: () -> Unit = { runOnUiThread { atualizarEstado() } }

    /** Já tentou ligar sozinho nesta conexão do cabo (volta a false quando o cabo sai). */
    private var autoTentado = false

    private val tick = object : Runnable {
        override fun run() {
            medirVelocidade()
            ligarSozinho()
            atualizarEstado()
            handler.postDelayed(this, 1000)
        }
    }

    /** Pendrive conectado/desconectado (aviso do sistema): a lista de lugares e o aviso de formato mudam. */
    private val receptorUsb = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // O Android leva um instante para montar o pendrive depois de conectado
            handler.postDelayed({ atualizarEstado() }, 1500)
        }
    }

    private val ouvinteShizuku = Shizuku.OnRequestPermissionResultListener { _, resultado ->
        runOnUiThread {
            if (resultado == PackageManager.PERMISSION_GRANTED) verificarPasta() else aviso(getString(R.string.shizuku_negado))
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ps2ponte", MODE_PRIVATE)
        estilo = estilos.firstOrNull { it.id == prefs.getString("estilo", "compacto") } ?: estilos.first()
        setContentView(montarTela())
        migrarModoPendrive()

        val filtro = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        Compat.registrar(this, receptorUsb, filtro, exportado = true)
        if (Build.VERSION.SDK_INT >= 23) try { Shizuku.addRequestPermissionResultListener(ouvinteShizuku) } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Estado.ouvir(ouvinte)
    }

    override fun onResume() {
        super.onResume()
        verificarPasta()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        Estado.esquecer(ouvinte)
        unregisterReceiver(receptorUsb)
        if (Build.VERSION.SDK_INT >= 23) try { Shizuku.removeRequestPermissionResultListener(ouvinteShizuku) } catch (_: Throwable) {}
        fundo.shutdown()
        if (::grade.isInitialized) grade.fechar()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ estilo e peças da tela

    /**
     * Aparência experimental (⚙ › Aparência): [escala] dos espaços, [texto] dos textos, [reto] = botões planos de
     * canto pequeno, [coluna] da grade de jogos (dp) e [lista] = jogos em lista.
     */
    private class Estilo(val id: String, val nome: Int, val escala: Float, val texto: Float, val reto: Boolean, val coluna: Int, val lista: Boolean)

    private val estilos = listOf(
        Estilo("compacto", R.string.est_compacto, 0.85f, 0.92f, true, 90, false),
        Estilo("mini", R.string.est_mini, 0.72f, 0.84f, true, 74, false),
        Estilo("lista", R.string.est_lista, 0.85f, 0.92f, true, 90, true),
        Estilo("classico", R.string.est_classico, 1f, 1f, false, 104, false),
    )
    private lateinit var estilo: Estilo

    private fun dp(v: Int) = (v * resources.displayMetrics.density * estilo.escala).toInt()
    private fun tam(sp: Float) = sp * estilo.texto

    private fun texto(t: String = "", tamanho: Float = 15f) = TextView(this).apply {
        text = t
        textSize = tam(tamanho)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { acao() }
        if (estilo.reto) {
            // Plano, canto pequeno, sem a altura mínima de 48dp do tema
            background = fundoBotao()
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(12), dp(9), dp(12), dp(9))
            textSize = tam(14f)
        }
    }

    private fun fundoBotao(): android.graphics.drawable.Drawable {
        fun cor(c: Int) = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(c) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), cor(0x4D808080))
            addState(intArrayOf(-android.R.attr.state_enabled), cor(0x12808080))
            addState(intArrayOf(), cor(0x29808080))
        }
    }

    /** Fundo arredondado, translúcido: funciona no tema claro e no escuro. */
    private fun fundoCartao(cor: Int) = GradientDrawable().apply {
        cornerRadius = dp(if (estilo.reto) 8 else 14).toFloat()
        setColor(cor)
    }

    private fun cartao(pai: LinearLayout, topo: Int = 12): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = fundoCartao(COR_CARTAO)
        pai.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topo) })
    }

    private fun dialogo(titulo: String, msg: String) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(msg).setPositiveButton(R.string.ok, null).show()
    }

    /** Botãozinho "ⓘ" que abre uma explicação. */
    private fun info(titulo: String, conteudo: () -> String) = TextView(this).apply {
        text = "ⓘ"
        textSize = tam(20f)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        contentDescription = getString(R.string.ajuda, titulo)
        setOnClickListener { dialogo(titulo, conteudo()) }
    }

    /** Linha: [principal] ocupando o espaço, seguido de [extras]. */
    private fun linha(principal: View, vararg extras: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(principal, LinearLayout.LayoutParams(0, -2, 1f))
        extras.forEach { addView(it) }
    }

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        textSize = tam(17f)
        setTypeface(typeface, Typeface.BOLD)
    }

    // ------------------------------------------------------------------ esqueleto: topo, abas embaixo

    private fun montarTela(): View {
        val tela = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Topo: nome e o ⚙ no canto
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(4))
        }
        topo.addView(titulo(getString(R.string.app_name)).apply { textSize = tam(19f) }, LinearLayout.LayoutParams(0, -2, 1f))
        topo.addView(TextView(this).apply {
            text = "⚙"
            textSize = tam(24f)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            contentDescription = getString(R.string.cfg_titulo)
            setOnClickListener { abrirConfiguracoes() }
        })
        tela.addView(topo)

        abaServidor = ScrollView(this).apply { addView(montarServidor()) }
        abaJogos = montarJogos()
        val corpo = FrameLayout(this)
        corpo.addView(abaServidor)
        corpo.addView(abaJogos)
        tela.addView(corpo, LinearLayout.LayoutParams(-1, 0, 1f))

        // Abas embaixo, perto do dedo
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0x1A808080) }
        }
        fun aba(t: String, jogos: Boolean) = TextView(this).apply {
            text = t
            gravity = Gravity.CENTER
            textSize = tam(15f)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { mostrarAba(jogos) }
            nav.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        navServidor = aba(getString(R.string.aba_servidor), false)
        navJogos = aba(getString(R.string.aba_ferramentas), true)
        tela.addView(nav)

        mostrarAba(prefs.getBoolean("aba_ferramentas", false))
        return tela
    }

    private fun mostrarAba(jogos: Boolean) {
        prefs.edit().putBoolean("aba_ferramentas", jogos).apply()
        abaServidor.visibility = if (jogos) View.GONE else View.VISIBLE
        abaJogos.visibility = if (jogos) View.VISIBLE else View.GONE
        for ((v, ativo) in listOf(navServidor to !jogos, navJogos to jogos)) {
            v.setTypeface(null, if (ativo) Typeface.BOLD else Typeface.NORMAL)
            v.alpha = if (ativo) 1f else 0.55f
            v.background = if (ativo) GradientDrawable().apply { setColor(0x26808080) } else null
        }
        if (jogos) {
            atualizarJogos()
            // Capas e fichas sempre frescas: confere (e baixa o que falta) toda vez que a aba abre
            pastaUsavel()?.let { Ferramentas.rotinaArte(this, it) }
        }
    }

    // ------------------------------------------------------------------ aba Servidor

    private fun montarServidor(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(24))
        }

        // Protocolo
        val rotulo = texto(getString(R.string.prot_titulo), 13f).apply { alpha = 0.7f; setPadding(dp(2), dp(4), 0, dp(4)) }
        val comoFaz = TextView(this).apply {
            text = "ⓘ"
            textSize = tam(18f)
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = getString(R.string.prot_como)
            setOnClickListener { mostrarComoConfigurar() }
        }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(rotulo)
            addView(comoFaz)
        })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, nome, sub) in PROTOCOLOS) {
            val chip = TextView(this).apply {
                text = "$nome\n$sub"
                gravity = Gravity.CENTER
                textSize = tam(12f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    if (Estado.rodando) return@setOnClickListener aviso(getString(R.string.prot_pare_antes))
                    prefs.edit().putString(PREF_PROTOCOLO, id).apply()
                    atualizarEstado()
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            botoesProtocolo[id] = chip
        }
        raiz.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // Iniciar no meio, com a dica do que falta logo abaixo
        btnIniciar = botao("") { iniciarOuParar() }.apply {
            textSize = tam(19f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
        }
        raiz.addView(btnIniciar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        tvDica = texto(tamanho = 13f).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), 0) }.also { raiz.addView(it) }
        btnDica = botao("") {}.also { raiz.addView(it, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) }) }

        // Andamento (servidor ligado)
        cartaoStatus = cartao(raiz)
        tvStatus = texto(tamanho = 13f).also { cartaoStatus.addView(it) }

        // Log do servidor
        val cLog = cartao(raiz)
        cLog.addView(linha(
            titulo(getString(R.string.sec_log)).apply { textSize = tam(15f) },
            botao(getString(R.string.btn_copiar)) { copiar(Estado.texto()) },
            botao(getString(R.string.btn_limpar)) { Estado.limpar() },
        ))
        tvLog = texto(tamanho = 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }.also { cLog.addView(it) }
        return raiz
    }

    private fun protocolo(): String = prefs.getString(PREF_PROTOCOLO, null)?.takeIf { p -> PROTOCOLOS.any { it.first == p } } ?: ServidorService.SRV_UDPFS

    /**
     * O modo "pendrive inteiro" (o PS2 lendo o disco cru) saiu: o modo pasta faz o mesmo, mais rápido e com a grade.
     * Quem estava nele volta para o modo pasta, com um aviso uma vez só.
     */
    private fun migrarModoPendrive() {
        if (prefs.getString("modo", null) != "usb") return
        prefs.edit().remove("modo").remove("usb").apply()
        AlertDialog.Builder(this).setTitle(R.string.mig_titulo).setMessage(R.string.mig_pendrive)
            .setPositiveButton(R.string.ok, null).show()
    }

    /** Há um pendrive conectado que o Android não montou (formato que o sistema não lê)? */
    private fun pendriveNaoMontado(): Boolean {
        val usb = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val temArmazenamento = try {
            usb.deviceList.values.any { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE } }
        } catch (e: Exception) { false }
        return temArmazenamento && Compat.volumes(this).none { !it.primario }
    }

    private fun mostrarPendriveNaoMontado() = dialogo(getString(R.string.usb_nao_montado_titulo), getString(R.string.usb_nao_montado))

    private fun ipCelular() = Rede.cabo()?.ip ?: getString(R.string.ip_celular)
    private fun ipPs2() = Rede.cabo()?.let { Rede.sugestaoPs2(it.ip) } ?: getString(R.string.ip_ps2)

    /** O passo a passo do protocolo escolhido, sempre com o IP do celular e o IP sugerido para o PS2. */
    private fun comoConfigurar(p: String): String {
        val rede = getString(R.string.cfg_rede, ipPs2(), ipCelular())
        val prot = when (p) {
            ServidorService.SRV_SMB -> getString(R.string.ajuda_smb, ipCelular(), ipPs2(), ServidorService.PORTA_SMB)
            ServidorService.SRV_UDPBD -> getString(R.string.ajuda_udpbd, ipPs2())
            ServidorService.SRV_NFS -> getString(R.string.ajuda_nfs, ipCelular())
            ServidorService.SRV_HTTP -> getString(R.string.ajuda_http, ipCelular())
            else -> getString(R.string.ajuda_udpfs, ipPs2())
        }
        return prot + "\n\n" + rede
    }

    /** Pop-up do ⓘ: o que preencher no loader para o protocolo escolhido, sempre com o IP do celular. */
    private fun mostrarComoConfigurar() {
        val p = protocolo()
        val nome = PROTOCOLOS.firstOrNull { it.first == p }?.second ?: p
        val tv = texto(comoConfigurar(p), 14f).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.1f)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.prot_como_titulo, nome))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.ok, null).show()
    }

    /** O que impede iniciar agora (texto da dica, rótulo e ação do botão), ou null se está tudo pronto. */
    private fun impedimento(): Triple<String, String?, (() -> Unit)?>? {
        return when {
            Rede.cabo() == null -> Triple(getString(R.string.hint_sem_cabo), getString(R.string.btn_ancoragem)) { abrirAncoragem() }
            // Pendrive conectado num formato que o Android não abre: nem pasta nem protocolo resolvem
            pastaAbriu != true && pendriveNaoMontado() ->
                Triple(getString(R.string.usb_nao_montado_curto), getString(R.string.usb_nao_montado_titulo)) { mostrarPendriveNaoMontado() }
            pastaAbriu != true ->
                Triple(if (pastaAbriu == null && pastaSalva() != null) getString(R.string.pasta_verificando) else getString(R.string.hint_sem_pasta),
                    getString(R.string.btn_ir_jogos)) { mostrarAba(true) }
            else -> null
        }
    }

    // ------------------------------------------------------------------ aba Jogos

    private fun montarJogos(): View {
        val tela = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }

        // Onde estão os jogos (toque para escolher)
        val cLocal = cartao(topo, 4)
        cLocal.setOnClickListener { escolherLocal() }
        cLocal.addView(texto(getString(R.string.jg_local_titulo), 12f).apply { alpha = 0.7f })
        tvLocal = texto(tamanho = 15f).apply { setTypeface(typeface, Typeface.BOLD) }
        cLocal.addView(linha(tvLocal, texto("▾", 18f)))
        tvLocalEstado = texto(tamanho = 12f).also { cLocal.addView(it) }
        btnLocalAcao = botao("") {}.also { cLocal.addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) }) }

        // Tarefa em andamento
        cartaoTarefa = cartao(topo, 8)
        tvTarefa = texto(tamanho = 14f).apply { setTypeface(typeface, Typeface.BOLD) }.also { cartaoTarefa.addView(it) }
        barraTarefa = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        cartaoTarefa.addView(barraTarefa, LinearLayout.LayoutParams(-1, -2))
        tvTarefaDetalhe = texto(tamanho = 12f).also { cartaoTarefa.addView(it) }
        cartaoTarefa.addView(botao(getString(R.string.cancelar)) { Ferramentas.cancelar() })

        tvResumo = texto(tamanho = 12f).apply {
            setPadding(dp(2), dp(6), 0, 0)
            setOnClickListener {
                when {
                    Ferramentas.pendentesCache > 0 -> mostrarPendentes()
                    Ferramentas.soLeitura -> mostrarSoLeitura()
                    (Ferramentas.catalogo?.foraDoLugar ?: 0) > 0 -> planejarOrganizacao()
                }
            }
        }.also { topo.addView(it) }
        val busca = EditText(this).apply {
            hint = getString(R.string.jg_buscar)
            setSingleLine()
            textSize = tam(15f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { grade.filtrar(s?.toString().orEmpty()); atualizarVazio() }
            })
        }
        topo.addView(busca)
        tela.addView(topo)

        grade = GradeJogos(this, estilo.escala, estilo.texto, estilo.coluna, estilo.lista) { detalhesJogo(it) }
        tvVazio = texto(tamanho = 15f).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(32), dp(24), dp(32)) }
        val corpo = FrameLayout(this)
        corpo.addView(grade.view)
        corpo.addView(tvVazio, FrameLayout.LayoutParams(-1, -2))
        tela.addView(corpo, LinearLayout.LayoutParams(-1, 0, 1f))
        return tela
    }

    /** Pasta pronta para as ferramentas e a grade (modo pasta e já aberta), ou null. */
    private fun pastaUsavel(): String? = pastaSalva()?.takeIf { pastaAbriu == true }

    private fun atualizarJogos() {
        if (!::tvLocal.isInitialized) return
        val pasta = pastaSalva()
        // Onde estão os jogos
        run {
            tvLocal.text = pasta?.let { Fonte.descricao(this, it) } ?: getString(R.string.jg_escolher)
            val precisaArquivos = pasta != null && acesso() == ACESSO_NORMAL && !Fonte.ehSaf(pasta) && !Fonte.ehPriv(pasta) && !temAcessoArquivos()
            tvLocalEstado.text = when {
                pasta == null -> getString(R.string.pasta_nenhuma)
                precisaArquivos -> getString(R.string.pasta_sem_perm)
                pastaAbriu == null -> getString(R.string.pasta_verificando)
                erroPasta != null -> erroPasta
                pastaAbriu == false -> getString(R.string.pasta_nao_abriu)
                temOpl == false -> getString(R.string.pasta_sem_dvd)
                else -> ""
            }
            when {
                pendriveNaoMontado() && pastaAbriu != true -> mostrarAcaoLocal(getString(R.string.usb_nao_montado_titulo)) { mostrarPendriveNaoMontado() }
                precisaArquivos -> mostrarAcaoLocal(getString(R.string.btn_perm_arquivos)) { abrirPermissaoArquivos() }
                temOpl == false && pastaAbriu == true -> mostrarAcaoLocal(getString(R.string.btn_criar_opl)) { criarPastasOpl() }
                else -> btnLocalAcao.visibility = View.GONE
            }
        }
        tvLocalEstado.visibility = if (tvLocalEstado.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Grade e resumo
        val usavel = pastaUsavel()
        val c = if (usavel != null) Ferramentas.catalogo else null
        grade.mostrar(usavel, c)
        atualizarVazio()
        tvResumo.text = if (c == null) "" else buildString {
            append(getString(R.string.jg_resumo, c.ps2.size, c.ps1.size))
            if (c.foraDoLugar > 0) append("\n").append(getString(R.string.ft_resumo_fora, c.foraDoLugar))
            if (Ferramentas.soLeitura) append("\n").append(getString(R.string.ft_so_leitura))
            if (Ferramentas.pendentesCache > 0) append("\n").append(getString(R.string.ft_pendentes, Ferramentas.pendentesCache))
        }
        tvResumo.visibility = if (tvResumo.text.isEmpty()) View.GONE else View.VISIBLE

        val ocupado = Ferramentas.ocupado
        cartaoTarefa.visibility = if (ocupado) View.VISIBLE else View.GONE
        if (ocupado) {
            tvTarefa.text = Ferramentas.titulo
            barraTarefa.isIndeterminate = Ferramentas.progresso < 0
            if (Ferramentas.progresso >= 0) barraTarefa.progress = Ferramentas.progresso
            tvTarefaDetalhe.text = Ferramentas.detalhe
        }
    }

    private fun mostrarAcaoLocal(rotulo: String, acao: () -> Unit) {
        btnLocalAcao.text = rotulo
        btnLocalAcao.setOnClickListener { acao() }
        btnLocalAcao.visibility = View.VISIBLE
    }

    private fun atualizarVazio() {
        tvVazio.text = when {
            pastaSalva() == null -> getString(R.string.jg_escolha_local)
            pastaAbriu != true -> ""
            Ferramentas.catalogo == null -> getString(R.string.ft_resumo_vazio)
            grade.quantos == 0 -> getString(R.string.jg_nenhum)
            else -> ""
        }
        tvVazio.visibility = if (tvVazio.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** "Onde estão os jogos": uma pasta (memória, cartão, pendrive, outra) ou o pendrive inteiro (modo bloco). */
    private fun escolherLocal() {
        if (Estado.rodando) return aviso(getString(R.string.prot_pare_antes))
        val modo = acesso()
        val opcoes = mutableListOf<Pair<String, () -> Unit>>()
        for (v in Compat.volumes(this)) {
            val rotulo = if (v.primario) getString(R.string.btn_vol, v.descricao) else getString(R.string.btn_vol_usb, v.descricao)
            opcoes += rotulo to { escolherVolume(v, modo) }
        }
        opcoes += getString(R.string.btn_outra_pasta) to { escolherPasta() }
        // Pendrive conectado que o Android não abriu: não dá para escolher; explica o porquê
        if (pendriveNaoMontado()) opcoes += ("⚠ " + getString(R.string.usb_nao_montado_titulo)) to { mostrarPendriveNaoMontado() }
        AlertDialog.Builder(this).setTitle(R.string.jg_local_titulo)
            .setItems(opcoes.map { it.first }.toTypedArray()) { _, i -> opcoes[i].second() }
            .setNegativeButton(R.string.cancelar, null).show()
    }

    /** Detalhes do jogo: ficha (do CFG), capa e as ações que cabem a ele. */
    private fun detalhesJogo(j: Catalogo.Item) {
        val pasta = pastaUsavel() ?: return
        fundo.execute {
            val ficha = j.id?.let { id -> try { Ferramentas.ficha(Ferramentas.raizDe(this, pasta), id) } catch (e: Exception) { emptyMap() } } ?: emptyMap()
            runOnUiThread { if (!isFinishing) mostrarDetalhes(j, ficha) }
        }
    }

    private fun mostrarDetalhes(j: Catalogo.Item, ficha: Map<String, String>) {
        val formato = when (j.tipo) {
            Catalogo.Tipo.PS2 -> if (j.compactado) "PS2 · ZSO" else "PS2 · ISO"
            Catalogo.Tipo.VCD -> "PS1 · POPStarter"
            Catalogo.Tipo.CUE -> "PS1 · Ember"
        }
        val msg = buildString {
            listOfNotNull(ficha["Genre"], ficha["Release"]?.takeLast(4), ficha["Developer"]).filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }?.let { append(it.joinToString(" · ")).append("\n\n") }
            ficha["Description"]?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            append(formato).append(" · ").append(String.format(Locale.getDefault(), "%.0f MiB", j.tamanho / 1048576.0)).append("\n")
            append(getString(R.string.jg_codigo, j.id ?: getString(R.string.ft_sem_codigo))).append("\n")
            append(j.rel)
            if (j.foraDoLugar) append("\n\n").append(getString(R.string.jg_fora_lugar))
        }
        val b = AlertDialog.Builder(this).setTitle(ficha["Title"]?.takeIf { it.isNotBlank() } ?: GradeJogos.titulo(j))
            .setMessage(msg).setNegativeButton(R.string.btn_fechar_escolha, null)
        when {
            j.foraDoLugar -> b.setPositiveButton(R.string.ft_btn_organizar) { _, _ -> planejarOrganizacao() }
            j.tipo == Catalogo.Tipo.PS2 -> {
                val compactar = !j.compactado
                // Só compacta ou descompacta: o arquivo é trocado depois de conferido, sem perguntas
                b.setPositiveButton(if (compactar) R.string.jg_compactar else R.string.jg_descompactar) { _, _ ->
                    compactarComEspaco(listOf(j), compactar, getString(if (compactar) R.string.ft_btn_compactar else R.string.ft_btn_descompactar))
                }
            }
        }
        b.show()
    }

    private fun mostrarLogJogos() {
        val tv = texto(Estado.textoJogos().ifEmpty { getString(R.string.jg_log_vazio) }, 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle(R.string.jg_log).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.btn_fechar_escolha, null)
            .setNeutralButton(R.string.btn_copiar) { _, _ -> copiar(Estado.textoJogos()) }
            .setNegativeButton(R.string.btn_limpar) { _, _ -> Estado.limparJogos() }
            .show()
    }

    private fun mostrarPendentes() {
        AlertDialog.Builder(this).setTitle(R.string.ft_pendentes_titulo)
            .setMessage(getString(R.string.ft_pendentes_ajuda, Ferramentas.pendentesCache))
            .setPositiveButton(R.string.ft_btn_trazer) { _, _ ->
                tarefa(getString(R.string.ft_btn_trazer), pendrive = true) { p, r -> Ferramentas.trazerPendentes(this, p, r) }
            }
            .setNegativeButton(R.string.btn_fechar_escolha, null).show()
    }

    private fun mostrarSoLeitura() {
        val pasta = pastaSalva() ?: return
        dialogo("🔒", getString(R.string.ft_so_leitura) + "\n\n" + getString(R.string.ft_apoio_onde, Ferramentas.pastaApoio(this, pasta).path))
    }

    // ------------------------------------------------------------------ ⚙ Configurações

    private fun abrirConfiguracoes() {
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        raiz.addView(linha(titulo(getString(R.string.cfg_titulo)).apply { textSize = tam(20f) },
            TextView(this).apply { text = "✕"; textSize = tam(22f); setPadding(dp(14), dp(4), dp(8), dp(4)); setOnClickListener { d.dismiss() } }))
        val pasta = pastaUsavel()
        fun secao(t: String) = cartao(raiz).also { it.addView(titulo(t).apply { textSize = tam(15f) }) }
        fun opcao(pai: LinearLayout, rotulo: String, chave: String, padrao: Boolean, ajuda: (() -> String)? = null, aoMudar: (Boolean) -> Unit = {}) {
            val cb = CheckBox(this).apply {
                text = rotulo
                isChecked = prefs.getBoolean(chave, padrao)
                setOnCheckedChangeListener { _, m -> prefs.edit().putBoolean(chave, m).apply(); aoMudar(m) }
            }
            pai.addView(if (ajuda != null) linha(cb, info(rotulo, ajuda)) else cb)
        }
        fun acao(pai: LinearLayout, rotulo: String, f: () -> Unit) {
            pai.addView(botao(rotulo) { d.dismiss(); f() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        // Aparência
        val cAp = secao(getString(R.string.cfg_sec_aparencia))
        val gEst = RadioGroup(this)
        for (e in estilos) gEst.addView(RadioButton(this).apply { text = getString(e.nome); id = View.generateViewId(); tag = e.id; isChecked = e == estilo })
        gEst.setOnCheckedChangeListener { g, id ->
            val novo = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            if (novo != estilo.id) { prefs.edit().putString("estilo", novo).apply(); d.dismiss(); recreate() }
        }
        cAp.addView(gEst)

        // Jogos e capas
        val cJ = secao(getString(R.string.cfg_sec_jogos))
        cJ.addView(texto(getString(R.string.cfg_capa_tam), 13f).apply { alpha = 0.8f })
        val gTam = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPeq = RadioButton(this).apply { text = getString(R.string.ft_capa_opl); id = View.generateViewId() }
        val rbGrd = RadioButton(this).apply { text = getString(R.string.ft_capa_ript); id = View.generateViewId() }
        gTam.addView(rbPeq); gTam.addView(rbGrd)
        if (prefs.getBoolean("capa_grande", false)) rbGrd.isChecked = true else rbPeq.isChecked = true
        gTam.setOnCheckedChangeListener { _, id -> prefs.edit().putBoolean("capa_grande", id == rbGrd.id).apply() }
        cJ.addView(gTam)
        opcao(cJ, getString(R.string.ft_sw_auto_tar), Ferramentas.PREF_AUTO_TAR, false, { getString(R.string.ft_ajuda_tar) }) { m ->
            if (m) pasta?.let { Ferramentas.rotina(this, it, forcar = true) }
        }
        cJ.addView(texto(getString(R.string.ft_ps1_destino), 13f).apply { alpha = 0.8f; setPadding(0, dp(8), 0, 0) })
        val gPs1 = RadioGroup(this)
        val atual = Ferramentas.destinoPs1(this)
        for ((dest, rot) in listOf(OrganizadorPs1.Destino.MANTER to R.string.ft_ps1_manter, OrganizadorPs1.Destino.POPS to R.string.ft_ps1_pops, OrganizadorPs1.Destino.EMBER to R.string.ft_ps1_ember)) {
            gPs1.addView(RadioButton(this).apply { text = getString(rot); id = View.generateViewId(); tag = dest.name; isChecked = dest == atual })
        }
        gPs1.setOnCheckedChangeListener { g, id -> g.findViewById<RadioButton>(id)?.tag?.let { prefs.edit().putString(Ferramentas.PREF_PS1_DESTINO, it as String).apply() } }
        cJ.addView(linha(gPs1, info(getString(R.string.ft_ps1_destino)) { getString(R.string.ft_ajuda_ps1) }))
        opcao(cJ, getString(R.string.ft_sw_auto_organizar), Ferramentas.PREF_AUTO_ORGANIZAR, false) { m ->
            if (m) pasta?.let { Ferramentas.rotina(this, it, forcar = true) }
        }

        // Ferramentas
        val cF = secao(getString(R.string.cfg_sec_ferramentas))
        if (pasta == null) cF.addView(texto(getString(R.string.ft_sem_pasta_escolhida), 13f))
        else {
            acao(cF, getString(R.string.ft_btn_organizar)) { planejarOrganizacao() }
            acao(cF, getString(R.string.ft_btn_atualizar)) { Ferramentas.rotinaArte(this, pasta, forcar = true) }
            acao(cF, getString(R.string.ft_btn_tar)) { tarefa(getString(R.string.ft_btn_tar)) { _, r -> Ferramentas.empacotarArt(this, r) } }
            acao(cF, getString(R.string.ft_btn_corrigir)) { tarefa(getString(R.string.ft_btn_corrigir), arte = true) { _, r -> Ferramentas.corrigirCapas(this, r) } }
            acao(cF, getString(R.string.ft_btn_verificar)) { verificarArquivos() }
            acao(cF, getString(R.string.btn_criar_opl)) { criarPastasOpl() }
        }
        acao(cF, getString(R.string.jg_log)) { mostrarLogJogos() }

        // Servidor
        val cS = secao(getString(R.string.cfg_sec_servidor))
        opcao(cS, getString(R.string.cfg_auto_iniciar), PREF_AUTO_INICIAR, true)
        opcao(cS, getString(R.string.op_descompactar), ServidorService.PREF_DESCOMPACTAR, true, { getString(R.string.ajuda_descompactar) })
        val cbAntigo = CheckBox(this).apply {
            text = getString(R.string.op_http_antigo)
            isChecked = !prefs.getBoolean(ServidorService.PREF_HTTP_RIPTOPL, true)
            setOnCheckedChangeListener { _, m -> prefs.edit().putBoolean(ServidorService.PREF_HTTP_RIPTOPL, !m).apply() }
        }
        cS.addView(linha(cbAntigo, info(getString(R.string.op_http_antigo)) { getString(R.string.ajuda_http_antigo) }))
        val bateriaOk = Build.VERSION.SDK_INT < 23 || (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        if (!bateriaOk) acao(cS, getString(R.string.btn_perm_bateria)) { pedirBateria() }

        // Acesso aos arquivos
        val cA = secao(getString(R.string.cfg_sec_acesso))
        val gAc = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((m, rot) in listOf(ACESSO_NORMAL to R.string.acesso_normal, Privilegio.ROOT to R.string.acesso_root, Privilegio.SHIZUKU to R.string.acesso_shizuku)) {
            gAc.addView(RadioButton(this).apply { text = getString(rot); id = View.generateViewId(); tag = m; isChecked = acesso() == m; isEnabled = !Estado.rodando })
        }
        gAc.setOnCheckedChangeListener { g, id -> (g.findViewById<RadioButton>(id)?.tag as? String)?.let { mudarAcesso(it) } }
        cA.addView(linha(gAc, info(getString(R.string.ajuda_acesso_titulo)) { getString(R.string.ajuda_acesso) }))

        // Sobre
        val versao = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        raiz.addView(texto("${getString(R.string.app_name)} $versao · github.com/wraphs/pocket-ps2-server", 12f).apply {
            alpha = 0.6f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        })

        d.setContentView(ScrollView(this).apply { addView(raiz) })
        d.show()
    }

    // ------------------------------------------------------------------ atualizações

    private fun atualizarEstado() {
        if (!::btnIniciar.isInitialized) return
        val rodando = Estado.rodando
        val p = protocolo()

        // Protocolo escolhido em destaque
        for ((id, chip) in botoesProtocolo) {
            val ativo = id == p
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(if (estilo.reto) 6 else 14).toFloat()
                setColor(if (ativo) COR_ESCOLHIDO else 0x14808080)
            }
            chip.setTypeface(null, if (ativo) Typeface.BOLD else Typeface.NORMAL)
            chip.alpha = if (rodando && !ativo) 0.4f else 1f
        }

        // Iniciar: cinza com a dica do que falta (sem cabo, sem jogos…); some a dica quando está tudo certo
        val imp = if (rodando) null else impedimento()
        btnIniciar.text = getString(if (rodando) R.string.btn_parar else R.string.btn_iniciar)
        btnIniciar.isEnabled = rodando || imp == null
        btnIniciar.alpha = if (btnIniciar.isEnabled) 1f else 0.45f
        tvDica.text = imp?.first ?: ""
        tvDica.visibility = if (imp == null) View.GONE else View.VISIBLE
        val rotulo = imp?.second
        btnDica.visibility = if (rotulo == null) View.GONE else View.VISIBLE
        if (rotulo != null) {
            btnDica.text = rotulo
            btnDica.setOnClickListener { imp?.third?.invoke() }
        }

        // Andamento
        if (rodando) {
            val linhas = mutableListOf<String>()
            Estado.dica?.let { linhas += it }
            linhas += if (Estado.ligadoEm == 0L) getString(R.string.st_iniciando) else Estado.resumo
            Estado.stats.lastClient?.let { ip ->
                val s = ((System.currentTimeMillis() - Estado.stats.lastRequestAt.get()) / 1000).coerceAtLeast(0)
                linhas += getString(R.string.st_ultimo_pedido, ip, if (s < 60) "${s}s" else "${s / 60}min")
            }
            if (velocidade.isNotEmpty()) linhas += velocidade
            tvStatus.text = linhas.joinToString("\n")
            cartaoStatus.background = fundoCartao(if (Estado.dica != null) COR_ALERTA else COR_OK)
            cartaoStatus.visibility = View.VISIBLE
        } else {
            cartaoStatus.visibility = View.GONE
        }
        tvLog.text = Estado.texto().ifEmpty { getString(R.string.log_vazio) }

        if (abaJogos.visibility == View.VISIBLE) atualizarJogos()
        // A rotina automática achou conversões de PS1: pergunta antes (em qualquer aba)
        Ferramentas.pedido?.let { if (!Ferramentas.ocupado && !mostrandoPedido) mostrarOrganizacao(it) }
    }

    /**
     * Cabo do PS2 conectado e jogos prontos: liga o servidor sem precisar tocar em Iniciar (⚙ › Servidor desliga
     * isso). Tenta uma vez por conexão: se a pessoa parar, ele só volta a ligar sozinho depois que o cabo sair e voltar.
     */
    private fun ligarSozinho() {
        if (Rede.cabo() == null) {
            autoTentado = false
            return
        }
        if (autoTentado || Estado.rodando || !prefs.getBoolean(PREF_AUTO_INICIAR, true) || impedimento() != null) return
        autoTentado = true
        Estado.log(getString(R.string.log_auto_iniciou))
        iniciarOuParar()
    }

    private fun medirVelocidade() {
        val agora = System.currentTimeMillis()
        val bytes = Estado.stats.bytesRead.get() + Estado.stats.bytesWritten.get()
        if (ultimoTempo > 0 && agora > ultimoTempo) {
            val mbs = (bytes - ultimoBytes) / 1048576.0 / ((agora - ultimoTempo) / 1000.0)
            velocidade = if (bytes == 0L) "" else String.format(Locale.getDefault(), getString(R.string.velocidade), mbs, bytes / 1048576.0)
        }
        ultimoBytes = bytes
        ultimoTempo = agora
    }

    // ------------------------------------------------------------------ servidor: iniciar e parar

    private fun iniciarOuParar() {
        if (Estado.rodando) {
            ServidorService.parar(this)
            autoTentado = true // parou na mão: não religa sozinho até o cabo sair e voltar
            return
        }
        impedimento()?.let { return aviso(it.first) }
        val pasta = pastaSalva() ?: return aviso(getString(R.string.av_escolha_pasta))
        if (Fonte.ehSaf(pasta) && !Fonte.temPermissao(this, pasta)) return aviso(getString(R.string.av_saf_de_novo))
        if (!Fonte.ehSaf(pasta) && !Fonte.ehPriv(pasta) && !temAcessoArquivos()) {
            aviso(getString(R.string.av_perm_arquivos))
            abrirPermissaoArquivos()
            return
        }
        ServidorService.iniciar(this, pasta, setOf(protocolo()))
    }

    // ------------------------------------------------------------------ pasta dos jogos

    private fun acesso(): String = prefs.getString("acesso", ACESSO_NORMAL) ?: ACESSO_NORMAL

    /** Pasta salva, corrigindo caminhos /mnt/media_rw/... gravados por versões anteriores (só root lê lá). */
    private fun pastaSalva(): String? = prefs.getString("pasta", null)?.let {
        if (it.startsWith("/mnt/media_rw/")) "/storage/" + it.removePrefix("/mnt/media_rw/") else it
    }

    private fun escolherVolume(v: Compat.Volume, modo: String) {
        when (modo) {
            Privilegio.ROOT -> {
                // Com root, o pendrive é lido direto do sistema de arquivos (sem a camada FUSE/SAF: mais rápido)
                val p = if (!v.primario && v.uuid != null && File("/mnt/media_rw").exists()) "/mnt/media_rw/${v.uuid}" else v.caminho
                p?.let { definirPasta("${Privilegio.ROOT}:$it") }
            }
            Privilegio.SHIZUKU -> v.caminho?.let { definirPasta("${Privilegio.SHIZUKU}:$it") }
            else -> {
                val dir = v.caminho
                if (v.primario) {
                    dir?.let { definirPasta(it) }
                } else if (dir != null && temAcessoArquivos() && File(dir).list() != null) {
                    // Com "todos os arquivos" o pendrive às vezes abre por caminho: bem mais rápido que o SAF
                    definirPasta(dir)
                } else {
                    // Senão, o Android só libera pelo seletor, que abre já na raiz ("Usar esta pasta")
                    try {
                        if (Build.VERSION.SDK_INT >= 29 && v.sv != null) startActivityForResult(v.sv.createOpenDocumentTreeIntent(), REQ_PASTA)
                        else escolherPasta()
                    } catch (e: Exception) {
                        escolherPasta()
                    }
                }
            }
        }
    }

    /** Abre a pasta numa thread (no root/Shizuku pode esperar o "Permitir") e guarda o resultado. */
    private fun verificarPasta() {
        val pasta = pastaSalva()
        val minha = ++verificacao
        erroPasta = null
        if (pasta == null) {
            pastaAbriu = false
            atualizarEstado()
            return
        }
        pastaAbriu = null
        temOpl = null
        atualizarEstado()
        if (Fonte.ehPriv(pasta) && Privilegio.modo(pasta) == Privilegio.SHIZUKU && !Privilegio.shizukuPermitido()) {
            if (Privilegio.shizukuRodando()) Privilegio.pedirShizuku(REQ_SHIZUKU)
            return mostrarPasta(minha, null, getString(if (Privilegio.shizukuRodando()) R.string.shizuku_negado else R.string.shizuku_parado))
        }
        val saf = Fonte.ehSaf(pasta)
        val acesso = if (saf) Fonte.temPermissao(this, pasta) else Fonte.ehPriv(pasta) || temAcessoArquivos()
        if (!acesso) return mostrarPasta(minha, null, getString(if (saf) R.string.pasta_saf_de_novo else R.string.pasta_sem_perm))
        fundo.execute {
            var filhos: List<VFile>? = null
            var erro: String? = null
            try {
                filhos = Fonte.raiz(this, pasta).listFiles()
            } catch (e: Exception) {
                erro = getString(R.string.pasta_erro, e.message ?: e.javaClass.simpleName)
            }
            val opl = listOf("DVD", "CD").any { nome -> filhos?.any { it.isDirectory && it.name.equals(nome, true) } == true }
            runOnUiThread { mostrarPasta(minha, filhos?.let { opl }, erro) }
        }
    }

    /** [opl]: null = não abriu (com [erro]); senão se tem DVD/ ou CD/. */
    private fun mostrarPasta(minha: Int, opl: Boolean?, erro: String?) {
        if (minha != verificacao || isFinishing) return
        pastaAbriu = opl != null
        temOpl = opl
        erroPasta = erro
        if (opl != null) {
            // Pasta abriu: catálogo de jogos em dia (e as rotinas automáticas, se ligadas)
            pastaSalva()?.let {
                Ferramentas.carregarCatalogo(this, it)
                Ferramentas.rotina(this, it)
                if (abaJogos.visibility == View.VISIBLE) Ferramentas.rotinaArte(this, it)
            }
        }
        atualizarEstado()
    }

    private fun definirPasta(caminho: String) {
        prefs.edit().putString("pasta", caminho).apply()
        verificarPasta()
    }

    /** Troca o modo de acesso, levando junto a pasta já escolhida quando ela é um caminho. */
    private fun mudarAcesso(novo: String) {
        prefs.edit().putString("acesso", novo).apply()
        if (novo == Privilegio.SHIZUKU && !Privilegio.shizukuRodando()) aviso(getString(R.string.shizuku_parado))
        val pasta = pastaSalva()
        if (pasta != null && !Fonte.ehSaf(pasta)) {
            val caminho = if (Fonte.ehPriv(pasta)) Privilegio.caminho(pasta) else pasta
            // /mnt/media_rw só o root lê; nos outros modos o mesmo pendrive é /storage/<uuid>
            val visivel = if (caminho.startsWith("/mnt/media_rw/")) "/storage/" + caminho.removePrefix("/mnt/media_rw/") else caminho
            prefs.edit().putString("pasta", if (novo == ACESSO_NORMAL) visivel else "$novo:${if (novo == Privilegio.ROOT) caminho else visivel}").apply()
        }
        if (novo == ACESSO_NORMAL) Privilegio.fechar()
        verificarPasta()
    }

    private fun criarPastasOpl() {
        val pasta = pastaSalva() ?: return
        fundo.execute {
            try {
                val raiz = Ferramentas.raizDe(this, pasta)
                for (nome in Ferramentas.PASTAS_OPL) raiz.child(nome).mkdirs()
                runOnUiThread { aviso(getString(R.string.opl_criadas)); verificarPasta() }
            } catch (e: Exception) {
                runOnUiThread { aviso(getString(R.string.pasta_erro, e.message ?: "")) }
            }
        }
    }

    private fun escolherPasta() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PASTA)
        } catch (e: Exception) {
            aviso(getString(R.string.av_seletor_falhou))
        }
    }

    @Deprecated("API da plataforma; sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PASTA || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.authority != "com.android.externalstorage.documents") return aviso(getString(R.string.av_nao_e_armazenamento))
        val modo = acesso()
        if (modo != ACESSO_NORMAL) {
            // Root/Shizuku: a pasta escolhida no seletor vira caminho, lido pelo auxiliar com privilégio
            val c = caminhoDaArvore(uri) ?: return aviso(getString(R.string.av_nao_e_armazenamento))
            return definirPasta("$modo:$c")
        }
        val vol = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
        if (vol.equals("primary", true) || vol.equals("home", true)) {
            // Memória interna: por caminho (mais rápido; usa o "acesso a todos os arquivos")
            caminhoDaArvore(uri)?.let { definirPasta(it) }
        } else {
            // Pendrive: guarda a árvore do SAF com permissão permanente de leitura e gravação
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                return aviso(getString(R.string.av_saf_sem_persistir))
            }
            definirPasta(uri.toString())
        }
    }

    /** "primary:PS2" -> /storage/emulated/0/PS2 ; "home:PS2" -> .../Documents/PS2 */
    private fun caminhoDaArvore(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(uri)
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = when {
            vol.equals("primary", true) -> Environment.getExternalStorageDirectory().path
            vol.equals("home", true) -> File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS).path
            else -> Compat.caminhoDoVolume(this, vol)
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    // ------------------------------------------------------------------ ferramentas

    /**
     * Roda uma ferramenta na pasta escolhida (a raiz é aberta já na thread da tarefa). No fim, o catálogo é
     * atualizado e, se [arte] e o interruptor estiver ligado, o art.tar é refeito.
     */
    private fun tarefa(nome: String, arte: Boolean = false, pendrive: Boolean = false, corpo: (String, VFile) -> String) {
        val pasta = pastaSalva() ?: return aviso(getString(R.string.av_escolha_pasta))
        Ferramentas.rodar(this, nome) {
            if (pendrive) Ferramentas.exigirPendrive(this)
            val r = Ferramentas.raizDe(this, pasta)
            val res = corpo(pasta, r)
            (res + Ferramentas.depois(this, pasta, r, arte)).trim().ifEmpty { getString(R.string.ft_lista_ok) }
        }
    }

    /** Monta o plano de Organizar numa tarefa; o resultado volta por [Ferramentas.pedido] e vira um diálogo. */
    private fun planejarOrganizacao() {
        val pasta = pastaSalva() ?: return aviso(getString(R.string.av_escolha_pasta))
        Ferramentas.rodar(this, getString(R.string.ft_planejando), silencioso = true) {
            val o = Ferramentas.planejarOrganizacao(this, pasta, Ferramentas.raizDe(this, pasta))
            o.manual = true
            Ferramentas.pedido = o
            ""
        }
    }

    /** Mostra o que Organizar vai fazer; conversões de PS1 pedem para escolher se apaga os originais. */
    private fun mostrarOrganizacao(o: Ferramentas.Organizacao) {
        Ferramentas.pedido = null
        if (isFinishing) return
        if (o.vazio) {
            if (o.manual) aviso(getString(R.string.ft_nada_organizar))
            return
        }
        mostrandoPedido = true
        val titulo = getString(R.string.ft_btn_organizar)
        val fazer = { apagar: Boolean ->
            val conversoes = o.plano.acoes.filter { it.conversao }
            if (conversoes.isEmpty()) {
                Ferramentas.rodar(this, titulo) { Ferramentas.executarOrganizacao(this, o, apagar) }
            } else {
                // Conversões de PS1 precisam de espaço para o arquivo novo: mesma conferência da compactação
                comEspaco(apagar, { p, r -> Ferramentas.espacoCom(this, p, r, conversoes.maxOf { it.bytes }) }) { temp ->
                    Ferramentas.rodar(this, titulo) { Ferramentas.executarOrganizacao(this, o, apagar, temp) }
                }
            }
        }
        val b = AlertDialog.Builder(this).setTitle(titulo).setMessage(o.resumo(this))
            .setOnDismissListener { mostrandoPedido = false }
            .setNegativeButton(R.string.cancelar, null)
        if (o.plano.conversoes > 0) {
            b.setPositiveButton(R.string.ft_converter_manter) { _, _ -> fazer(false) }
            b.setNeutralButton(R.string.ft_converter_apagar) { _, _ -> fazer(true) }
        } else {
            b.setPositiveButton(R.string.ok) { _, _ -> fazer(false) }
        }
        b.show()
    }

    /** Confere os arquivos dos loaders (POPStarter, Ember, Neutrino) e mostra a lista. */
    private fun verificarArquivos() {
        val pasta = pastaSalva() ?: return
        aviso(getString(R.string.ft_lendo))
        fundo.execute {
            val texto = try {
                Ferramentas.verificar(this, pasta, Ferramentas.raizDe(this, pasta))
            } catch (e: Exception) {
                getString(R.string.pasta_erro, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { if (!isFinishing) dialogo(getString(R.string.ft_btn_verificar), texto) }
        }
    }

    /**
     * Compacta (ISO → ZSO) ou descompacta um jogo, trocando o arquivo depois de conferido. Se faltar espaço na
     * pasta, oferece montar em outro volume e trazer de volta.
     */
    private fun compactarComEspaco(itens: List<Catalogo.Item>, compactar: Boolean, titulo: String) {
        comEspaco(true, { p, r -> Ferramentas.espacoPara(this, p, r, itens, compactar) }) { temp ->
            tarefa(titulo, pendrive = true) { _, r ->
                val jogos = Ferramentas.jogosDe(r, itens)
                if (compactar) Ferramentas.compactar(this, jogos, true, temp) else Ferramentas.descompactar(this, jogos, true, temp)
            }
        }
    }

    /** Confere o espaço com [calcular] (numa thread) e roda [rodar] com a pasta temporária escolhida, ou null. */
    private fun comEspaco(apagar: Boolean, calcular: (String, VFile) -> Ferramentas.Espaco, rodar: (File?) -> Unit) {
        val pasta = pastaSalva() ?: return
        if (apagar && Estado.rodando) {
            // Com o servidor ligado o PS2 pode estar com o jogo aberto: apagar não libera o espaço até ele fechar,
            // e o arquivo novo não caberia na volta. Para antes (os arquivos fecham em segundo plano).
            AlertDialog.Builder(this).setTitle(R.string.esp_titulo).setMessage(R.string.esp_servidor)
                .setPositiveButton(R.string.esp_parar) { _, _ ->
                    ServidorService.parar(this)
                    handler.postDelayed({ if (!isFinishing) comEspaco(apagar, calcular, rodar) }, 3000)
                }
                .setNegativeButton(R.string.cancelar, null).show()
            return
        }
        fundo.execute {
            val e = try { calcular(pasta, Ferramentas.raizDe(this, pasta)) } catch (ex: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (e == null || !e.falta) return@runOnUiThread rodar(null)
                val gb = { b: Long -> String.format(Locale.getDefault(), "%.1f GB", b / 1073741824.0) }
                val lugar = e.lugar
                if (lugar != null) {
                    AlertDialog.Builder(this).setTitle(R.string.esp_titulo)
                        .setMessage(getString(R.string.esp_temporario, gb(e.precisa), gb(e.livre), lugar.nome, gb(lugar.livre)) +
                            if (apagar) "" else "\n\n" + getString(R.string.esp_manter_aviso))
                        .setPositiveButton(R.string.esp_usar) { _, _ -> rodar(lugar.dir) }
                        .setNegativeButton(R.string.cancelar, null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(R.string.esp_titulo)
                        .setMessage(getString(R.string.esp_nenhum, gb(e.precisa), gb(e.livre), gb(e.precisa - e.livre)) +
                            e.lugares.joinToString("") { "\n• ${it.nome}: ${gb(it.livre)}" })
                        .setPositiveButton(R.string.ok, null).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ sistema

    private fun abrirAncoragem() {
        val tentativas = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tentativas) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }

    /** Android 11+: "todos os arquivos"; do 6 ao 10, a permissão comum de armazenamento; no 5, já vem com a instalação. */
    private fun temAcessoArquivos(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 -> checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun abrirPermissaoArquivos() {
        if (Build.VERSION.SDK_INT < 30) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            }
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {
                aviso(getString(R.string.av_abra_config, getString(R.string.app_name), getString(R.string.perm_arquivos)))
            }
        }
    }

    private fun pedirBateria() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {
                aviso(getString(R.string.av_abra_config, getString(R.string.app_name), getString(R.string.perm_bateria)))
            }
        }
    }

    private fun copiar(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), t))
        aviso(getString(R.string.log_copiado))
    }

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PASTA = 10
        private const val REQ_SHIZUKU = 11
        private const val ACESSO_NORMAL = "normal"
        private const val PREF_PROTOCOLO = "protocolo"
        private const val PREF_AUTO_INICIAR = "auto_iniciar"

        /** (servidor, nome, para quem) */
        private val PROTOCOLOS = listOf(
            Triple(ServidorService.SRV_UDPFS, "UDPFS", "NHDDL · Neutrino · RiptOPL"),
            Triple(ServidorService.SRV_SMB, "SMB", "OPL · RiptOPL"),
            Triple(ServidorService.SRV_UDPBD, "UDPBD", "Neutrino · XEB+"),
            Triple(ServidorService.SRV_NFS, "NFS", "Neutrino · RiptOPL"),
            Triple(ServidorService.SRV_HTTP, "HTTP", "RiptOPL"),
        )

        private const val COR_CARTAO = 0x1A808080
        private const val COR_ESCOLHIDO = 0x4D3F8CFF
        private const val COR_OK = 0x3322AA55
        private const val COR_ALERTA = 0x40E0A020
    }
}
