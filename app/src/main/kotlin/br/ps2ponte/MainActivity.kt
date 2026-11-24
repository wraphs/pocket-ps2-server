package br.ps2ponte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
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
import android.hardware.usb.UsbDevice
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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.ps2ponte.priv.Privilegio
import br.ps2ponte.usb.UsbMassStorage
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var usb: UsbManager
    private val fundo = Executors.newSingleThreadExecutor()

    private lateinit var cartaoStatus: LinearLayout
    private lateinit var tvStatusTitulo: TextView
    private lateinit var tvStatusDetalhe: TextView
    private lateinit var btnIniciar: Button
    private lateinit var tvRede: TextView
    private lateinit var btnAncoragem: Button
    private lateinit var btnArquivos: Button
    private lateinit var btnBateria: Button
    private lateinit var rbPasta: RadioButton
    private lateinit var rbUsb: RadioButton
    private lateinit var painelPasta: LinearLayout
    private lateinit var grupoAcesso: RadioGroup
    private lateinit var rbNormal: RadioButton
    private lateinit var rbRoot: RadioButton
    private lateinit var rbShizuku: RadioButton
    private lateinit var tvPasta: TextView
    private lateinit var btnCriarPastas: Button
    private lateinit var listaVolumes: LinearLayout
    private lateinit var btnOutraPasta: Button
    private lateinit var painelUsb: LinearLayout
    private lateinit var listaUsb: RadioGroup
    private lateinit var tvUsbVazio: TextView
    private lateinit var cbSmb: CheckBox
    private lateinit var cbUdpfs: CheckBox
    private lateinit var cbUdpbd: CheckBox
    private lateinit var tvLog: TextView
    private lateinit var cbDescompactar: CheckBox
    private lateinit var cbHttp: CheckBox
    private lateinit var cbHttpAntigo: CheckBox
    private lateinit var btnTrocarPasta: Button
    private lateinit var painelEscolha: LinearLayout
    private var escolhaAberta = false

    private lateinit var abaServidor: View
    private lateinit var abaFerramentas: View
    private lateinit var btnAbaServidor: Button
    private lateinit var btnAbaFerramentas: Button
    private lateinit var cartaoTarefa: LinearLayout
    private lateinit var tvTarefa: TextView
    private lateinit var barraTarefa: android.widget.ProgressBar
    private lateinit var tvTarefaDetalhe: TextView
    private lateinit var tvFtPasta: TextView
    private val botoesFt = mutableListOf<Button>()

    private val handler = Handler(Looper.getMainLooper())
    private var ultimoBytes = 0L
    private var ultimoTempo = 0L
    private var velocidade = ""

    /** Resultado da última verificação da pasta: null = verificando; true = abriu. */
    private var pastaAbriu: Boolean? = null
    private var verificacao = 0

    private val ouvinte: () -> Unit = { runOnUiThread { atualizarEstado() } }

    private val tick = object : Runnable {
        override fun run() {
            medirVelocidade()
            atualizarRede()
            atualizarEstado()
            handler.postDelayed(this, 1000)
        }
    }

    /** Resposta do pedido de permissão USB (só o próprio app manda: não exportado). */
    private val receptorPermissao = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                ServidorService.iniciar(this@MainActivity, ServidorService.MODO_USB, null, dev.deviceName, servidoresEscolhidos())
            } else {
                aviso(getString(R.string.av_usb_sem_perm))
            }
        }
    }

    /** Pendrive conectado/desconectado (aviso do sistema). */
    private val receptorUsb = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            atualizarUsb()
            atualizarVolumes()
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
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        setContentView(montarTela())

        Compat.registrar(this, receptorPermissao, IntentFilter(ACAO_PERMISSAO_USB), exportado = false)
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
        atualizarPermissoes()
        atualizarVolumes()
        atualizarUsb()
        atualizarModo()
        verificarPasta()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        Estado.esquecer(ouvinte)
        unregisterReceiver(receptorPermissao)
        unregisterReceiver(receptorUsb)
        if (Build.VERSION.SDK_INT >= 23) try { Shizuku.removeRequestPermissionResultListener(ouvinteShizuku) } catch (_: Throwable) {}
        fundo.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tela

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texto(t: String = "", tamanho: Float = 15f) = TextView(this).apply {
        text = t
        textSize = tamanho
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { acao() }
    }

    /** Fundo arredondado, translúcido: funciona no tema claro e no escuro. */
    private fun fundoCartao(cor: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(cor)
    }

    private fun cartao(pai: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = fundoCartao(COR_CARTAO)
        pai.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun dialogo(titulo: String, msg: String) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(msg).setPositiveButton(R.string.ok, null).show()
    }

    /** Botãozinho "ⓘ" que abre uma explicação. [conteudo] é calculado na hora (usa o IP atual). */
    private fun info(titulo: String, conteudo: () -> String) = TextView(this).apply {
        text = "ⓘ"
        textSize = 20f
        setPadding(dp(12), dp(4), dp(12), dp(4))
        contentDescription = getString(R.string.ajuda, titulo)
        setOnClickListener { dialogo(titulo, conteudo()) }
    }

    /** Linha: [principal] ocupando o espaço, seguido de [extras]. */
    private fun linha(principal: View, vararg extras: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(principal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        extras.forEach { addView(it) }
    }

    private fun titulo(t: String, ajudaTitulo: String? = null, ajuda: (() -> String)? = null): View {
        val tv = TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        return if (ajuda != null) linha(tv, info(ajudaTitulo ?: t, ajuda)) else tv
    }

    /** Abas "Servidor" e "Ferramentas" no topo; cada uma rola sozinha. */
    private fun montarTela(): View {
        val tela = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(6), dp(14), 0)
        }
        abaServidor = ScrollView(this).apply { addView(montarServidor()) }
        abaFerramentas = ScrollView(this).apply { addView(montarFerramentas()) }
        btnAbaServidor = botao(getString(R.string.aba_servidor)) { mostrarAba(false) }
        btnAbaFerramentas = botao(getString(R.string.aba_ferramentas)) { mostrarAba(true) }
        for (b in listOf(btnAbaServidor, btnAbaFerramentas)) barra.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tela.addView(barra)
        val corpo = android.widget.FrameLayout(this)
        corpo.addView(abaServidor)
        corpo.addView(abaFerramentas)
        tela.addView(corpo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        mostrarAba(prefs.getBoolean("aba_ferramentas", false))
        return tela
    }

    private fun mostrarAba(ferramentas: Boolean) {
        prefs.edit().putBoolean("aba_ferramentas", ferramentas).apply()
        abaServidor.visibility = if (ferramentas) View.GONE else View.VISIBLE
        abaFerramentas.visibility = if (ferramentas) View.VISIBLE else View.GONE
        btnAbaServidor.setTypeface(null, if (ferramentas) Typeface.NORMAL else Typeface.BOLD)
        btnAbaFerramentas.setTypeface(null, if (ferramentas) Typeface.BOLD else Typeface.NORMAL)
        btnAbaServidor.alpha = if (ferramentas) 0.6f else 1f
        btnAbaFerramentas.alpha = if (ferramentas) 1f else 0.6f
        if (ferramentas) atualizarFerramentas()
    }

    private fun montarServidor(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(32))
        }

        // Status + Iniciar
        cartaoStatus = cartao(raiz)
        tvStatusTitulo = texto(tamanho = 20f).apply { setTypeface(typeface, Typeface.BOLD) }.also { cartaoStatus.addView(it) }
        tvStatusDetalhe = texto(tamanho = 14f).also { cartaoStatus.addView(it) }
        btnIniciar = Button(this).apply {
            textSize = 20f
            isAllCaps = false
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { iniciarOuParar() }
        }
        cartaoStatus.addView(btnIniciar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        btnArquivos = botao(getString(R.string.btn_perm_arquivos)) { abrirPermissaoArquivos() }.also { raiz.addView(it) }
        btnBateria = botao(getString(R.string.btn_perm_bateria)) { pedirBateria() }.also { raiz.addView(it) }

        // 1 · Cabo
        val cRede = cartao(raiz)
        cRede.addView(titulo(getString(R.string.sec_rede), getString(R.string.ajuda_rede_titulo)) {
            getString(R.string.ajuda_rede, ipCelular(), ipPs2())
        })
        tvRede = texto()
        btnAncoragem = botao(getString(R.string.btn_ancoragem)) { abrirAncoragem() }
        cRede.addView(linha(tvRede, btnAncoragem))

        // 2 · Jogos
        val cJogos = cartao(raiz)
        cJogos.addView(titulo(getString(R.string.sec_jogos), getString(R.string.ajuda_jogos_titulo)) { getString(R.string.ajuda_jogos) })
        // Os controles menos usados (modo, acesso, servidores) ficam no cartão "Avançado", montado no fim
        val cAvancado = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val grupo = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbPasta = RadioButton(this).apply { text = getString(R.string.modo_pasta); id = View.generateViewId() }
        rbUsb = RadioButton(this).apply { text = getString(R.string.modo_usb); id = View.generateViewId() }
        grupo.addView(rbPasta)
        grupo.addView(rbUsb)
        // Marca antes de pôr o ouvinte: o isChecked dispara o ouvinte, e o atualizarModo() mexeria em
        // painéis que ainda não existem. O onResume chama atualizarModo().
        if (prefs.getString("modo", ServidorService.MODO_PASTA) == ServidorService.MODO_USB) rbUsb.isChecked = true else rbPasta.isChecked = true
        grupo.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("modo", if (id == rbUsb.id) ServidorService.MODO_USB else ServidorService.MODO_PASTA).apply()
            atualizarModo()
        }
        cAvancado.addView(texto(getString(R.string.av_modo), 14f).apply { setPadding(0, dp(6), 0, 0) })
        cAvancado.addView(grupo)

        painelPasta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grupoAcesso = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rbNormal = RadioButton(this).apply { text = getString(R.string.acesso_normal); id = View.generateViewId() }
        rbRoot = RadioButton(this).apply { text = getString(R.string.acesso_root); id = View.generateViewId() }
        rbShizuku = RadioButton(this).apply { text = getString(R.string.acesso_shizuku); id = View.generateViewId() }
        grupoAcesso.addView(texto(getString(R.string.acesso)).apply { setPadding(0, 0, dp(6), 0) })
        listOf(rbNormal, rbRoot, rbShizuku).forEach { grupoAcesso.addView(it) }
        when (acesso()) {
            Privilegio.ROOT -> rbRoot.isChecked = true
            Privilegio.SHIZUKU -> rbShizuku.isChecked = true
            else -> rbNormal.isChecked = true
        }
        grupoAcesso.setOnCheckedChangeListener { _, id ->
            mudarAcesso(when (id) {
                rbRoot.id -> Privilegio.ROOT
                rbShizuku.id -> Privilegio.SHIZUKU
                else -> ACESSO_NORMAL
            })
        }
        cAvancado.addView(linha(grupoAcesso, info(getString(R.string.ajuda_acesso_titulo)) { getString(R.string.ajuda_acesso) }))
        tvPasta = texto().apply { setTypeface(typeface, Typeface.BOLD) }.also { painelPasta.addView(it) }
        btnCriarPastas = botao(getString(R.string.btn_criar_opl)) { criarPastasOpl() }.also { painelPasta.addView(it) }
        btnTrocarPasta = botao(getString(R.string.btn_trocar_pasta)) {
            escolhaAberta = !escolhaAberta
            atualizarEscolha()
        }.also { painelPasta.addView(it) }
        painelEscolha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listaVolumes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        painelEscolha.addView(listaVolumes)
        btnOutraPasta = botao("") { outraPasta() }.also { painelEscolha.addView(it) }
        painelPasta.addView(painelEscolha)
        cJogos.addView(painelPasta)

        painelUsb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listaUsb = RadioGroup(this).also { painelUsb.addView(it) }
        listaUsb.setOnCheckedChangeListener { g, id ->
            g.findViewById<RadioButton>(id)?.tag?.let { prefs.edit().putString("usb", it as String).apply() }
        }
        tvUsbVazio = texto(getString(R.string.usb_nenhum)).also { painelUsb.addView(it) }
        painelUsb.addView(botao(getString(R.string.btn_procurar)) { atualizarUsb() })
        cJogos.addView(painelUsb)

        // 3 · PS2
        val cPs2 = cartao(raiz)
        cPs2.addView(titulo(getString(R.string.sec_ps2)))
        cPs2.addView(botao(getString(R.string.btn_config_ps2)) { mostrarConfigPs2() })

        // ⚙ Avançado (recolhido)
        val cAv = cartao(raiz)
        val cabecalho = titulo(getString(R.string.sec_avancado))
        cAv.addView(cabecalho)
        cAv.addView(cAvancado)
        cabecalho.setOnClickListener {
            val abrir = cAvancado.visibility != View.VISIBLE
            prefs.edit().putBoolean("avancado", abrir).apply()
            cAvancado.visibility = if (abrir) View.VISIBLE else View.GONE
            (cabecalho as TextView).text = getString(R.string.sec_avancado) + if (abrir) "  ▾" else "  ▸"
        }
        val abertoAv = prefs.getBoolean("avancado", false)
        cAvancado.visibility = if (abertoAv) View.VISIBLE else View.GONE
        (cabecalho as TextView).text = getString(R.string.sec_avancado) + if (abertoAv) "  ▾" else "  ▸"
        cAvancado.addView(texto(getString(R.string.servidores), 14f).apply { setPadding(0, dp(8), 0, 0) })
        cbSmb = caixaServidor(cAvancado, "smb", getString(R.string.srv_smb)) { ajudaSmb() }
        cbUdpfs = caixaServidor(cAvancado, "udpfs", getString(R.string.srv_udpfs)) { getString(R.string.ajuda_udpfs, ipPs2()) }
        cbUdpbd = caixaServidor(cAvancado, "udpbd", getString(R.string.srv_udpbd)) { getString(R.string.ajuda_udpbd, ipPs2()) }
        cbHttp = caixaServidor(cAvancado, "http", getString(R.string.srv_http)) { getString(R.string.ajuda_http, ipCelular()) }
        cbHttpAntigo = CheckBox(this).apply {
            text = getString(R.string.op_http_antigo)
            isChecked = !prefs.getBoolean(ServidorService.PREF_HTTP_RIPTOPL, true)
            setOnCheckedChangeListener { _, m -> prefs.edit().putBoolean(ServidorService.PREF_HTTP_RIPTOPL, !m).apply() }
        }
        cAvancado.addView(linha(cbHttpAntigo, info(getString(R.string.op_http_antigo)) { getString(R.string.ajuda_http_antigo) }).apply { setPadding(dp(24), 0, 0, 0) })
        cbDescompactar = CheckBox(this).apply {
            text = getString(R.string.op_descompactar)
            isChecked = prefs.getBoolean(ServidorService.PREF_DESCOMPACTAR, true)
            setOnCheckedChangeListener { _, m -> prefs.edit().putBoolean(ServidorService.PREF_DESCOMPACTAR, m).apply() }
        }
        cAvancado.addView(linha(cbDescompactar, info(getString(R.string.op_descompactar)) { getString(R.string.ajuda_descompactar) }))

        // Log
        val cLog = cartao(raiz)
        cLog.addView(linha(
            titulo(getString(R.string.sec_log)),
            botao(getString(R.string.btn_copiar)) { copiarLog() },
            botao(getString(R.string.btn_limpar)) { Estado.limpar() },
        ))
        tvLog = texto(tamanho = 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }.also { cLog.addView(it) }

        return raiz
    }

    /** Lista de volumes e "outra pasta": aberta quando não há pasta boa ou o usuário tocou em "Trocar". */
    private fun atualizarEscolha() {
        val ok = pastaAbriu == true
        painelEscolha.visibility = if (escolhaAberta || !ok) View.VISIBLE else View.GONE
        btnTrocarPasta.visibility = if (ok) View.VISIBLE else View.GONE
        btnTrocarPasta.text = getString(if (escolhaAberta) R.string.btn_fechar_escolha else R.string.btn_trocar_pasta)
    }

    // ------------------------------------------------------------------ aba Ferramentas

    private fun montarFerramentas(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(32))
        }

        // Tarefa em andamento
        cartaoTarefa = cartao(raiz)
        tvTarefa = texto(tamanho = 16f).apply { setTypeface(typeface, Typeface.BOLD) }.also { cartaoTarefa.addView(it) }
        barraTarefa = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        cartaoTarefa.addView(barraTarefa, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tvTarefaDetalhe = texto(tamanho = 13f).also { cartaoTarefa.addView(it) }
        cartaoTarefa.addView(botao(getString(R.string.cancelar)) { Ferramentas.cancelar() })

        val cPasta = cartao(raiz)
        cPasta.addView(titulo(getString(R.string.ft_sec_pasta), getString(R.string.ft_sec_pasta)) { getString(R.string.ft_ajuda_pasta) })
        tvFtPasta = texto(tamanho = 14f).also { cPasta.addView(it) }
        botoesFt += botao(getString(R.string.ft_btn_ver)) { verJogos() }.also { cPasta.addView(it) }
        botoesFt += botao(getString(R.string.ft_btn_organizar)) {
            confirmar(getString(R.string.ft_btn_organizar), getString(R.string.ft_conf_organizar)) {
                tarefa(getString(R.string.ft_btn_organizar)) { Ferramentas.organizar(this, it) }
            }
        }.also { cPasta.addView(it) }

        val cCapas = cartao(raiz)
        cCapas.addView(titulo(getString(R.string.ft_sec_capas), getString(R.string.ft_sec_capas)) { getString(R.string.ft_ajuda_capas) })
        val grupoTam = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPeq = RadioButton(this).apply { text = getString(R.string.ft_capa_opl); id = View.generateViewId() }
        val rbGrd = RadioButton(this).apply { text = getString(R.string.ft_capa_ript); id = View.generateViewId() }
        grupoTam.addView(rbPeq)
        grupoTam.addView(rbGrd)
        if (prefs.getBoolean("capa_grande", false)) rbGrd.isChecked = true else rbPeq.isChecked = true
        grupoTam.setOnCheckedChangeListener { _, id -> prefs.edit().putBoolean("capa_grande", id == rbGrd.id).apply() }
        cCapas.addView(grupoTam)
        botoesFt += botao(getString(R.string.ft_btn_baixar_capas)) {
            val grande = prefs.getBoolean("capa_grande", false)
            tarefa(getString(R.string.ft_btn_baixar_capas)) { Ferramentas.baixarCapas(this, it, if (grande) 280 else 140, if (grande) 400 else 200) }
        }.also { cCapas.addView(it) }
        botoesFt += botao(getString(R.string.ft_btn_corrigir)) {
            tarefa(getString(R.string.ft_btn_corrigir)) { Ferramentas.corrigirCapas(this, it) }
        }.also { cCapas.addView(it) }
        botoesFt += botao(getString(R.string.ft_btn_tar)) {
            tarefa(getString(R.string.ft_btn_tar)) { Ferramentas.empacotarArt(this, it) }
        }.also { cCapas.addView(linha(it, info(getString(R.string.ft_btn_tar)) { getString(R.string.ft_ajuda_tar) })) }

        val cComp = cartao(raiz)
        cComp.addView(titulo(getString(R.string.ft_sec_compactar), getString(R.string.ft_sec_compactar)) { getString(R.string.ft_ajuda_compactar) })
        botoesFt += botao(getString(R.string.ft_btn_compactar)) { escolherJogos(compactar = true) }.also { cComp.addView(it) }
        botoesFt += botao(getString(R.string.ft_btn_descompactar)) { escolherJogos(compactar = false) }.also { cComp.addView(it) }

        return raiz
    }

    private fun atualizarFerramentas() {
        if (!::tvFtPasta.isInitialized) return
        val pasta = pastaSalva()
        tvFtPasta.text = if (pasta == null || !rbPasta.isChecked) getString(R.string.ft_sem_pasta_escolhida) else Fonte.descricao(this, pasta)
        val ocupado = Ferramentas.ocupado
        cartaoTarefa.visibility = if (ocupado) View.VISIBLE else View.GONE
        if (ocupado) {
            tvTarefa.text = Ferramentas.titulo
            barraTarefa.isIndeterminate = Ferramentas.progresso < 0
            if (Ferramentas.progresso >= 0) barraTarefa.progress = Ferramentas.progresso
            tvTarefaDetalhe.text = Ferramentas.detalhe
        }
        val pode = !ocupado && pasta != null && rbPasta.isChecked
        for (b in botoesFt) b.isEnabled = pode
    }

    private fun confirmar(titulo: String, msg: String, acao: () -> Unit) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(msg)
            .setPositiveButton(R.string.ok) { _, _ -> acao() }
            .setNegativeButton(R.string.cancelar, null).show()
    }

    /** Roda uma ferramenta na pasta escolhida (a raiz é aberta já na thread da tarefa). */
    private fun tarefa(nome: String, corpo: (br.ps2ponte.core.fs.VFile) -> String) {
        val pasta = pastaSalva() ?: return aviso(getString(R.string.av_escolha_pasta))
        Ferramentas.rodar(this, nome) { corpo(Fonte.raiz(this, pasta)) }
    }

    /** Lista os jogos (com o código e se têm capa) numa thread e mostra num diálogo. */
    private fun verJogos() {
        val pasta = pastaSalva() ?: return
        aviso(getString(R.string.ft_lendo))
        fundo.execute {
            val texto = try {
                val raiz = Fonte.raiz(this, pasta)
                val art = raiz.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("ART", true) }
                    ?.listFiles()?.map { it.name.uppercase() }?.toHashSet() ?: hashSetOf()
                val lista = Ferramentas.jogos(raiz)
                if (lista.isEmpty()) getString(R.string.ft_nenhum_jogo)
                else lista.joinToString("\n") { j ->
                    val id = Ferramentas.lerId(j)
                    val capa = id != null && art.any { it.startsWith("${id}_COV.") }
                    val mb = j.arquivo.length() / (1024 * 1024)
                    "${if (capa) "🖼" else "▫"} ${j.pasta}/${j.nome}\n     ${id ?: getString(R.string.ft_sem_codigo)} · $mb MiB"
                } + "\n\n" + getString(R.string.ft_legenda)
            } catch (e: Exception) {
                getString(R.string.pasta_erro, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { if (!isFinishing) dialogo(getString(R.string.ft_btn_ver), texto) }
        }
    }

    /** Escolhe quais jogos compactar (ISO) ou descompactar (ZSO/CSO), e se apaga os originais. */
    private fun escolherJogos(compactar: Boolean) {
        val pasta = pastaSalva() ?: return
        fundo.execute {
            val lista = try {
                Ferramentas.jogos(Fonte.raiz(this, pasta)).filter { it.compactado != compactar }
            } catch (e: Exception) {
                runOnUiThread { aviso(getString(R.string.pasta_erro, e.message ?: "")) }
                return@execute
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (lista.isEmpty()) return@runOnUiThread aviso(getString(if (compactar) R.string.ft_nada_compactar else R.string.ft_nada_descompactar))
                val marcados = BooleanArray(lista.size)
                val nomes = lista.map { "${it.pasta}/${it.nome}  (${it.arquivo.length() / (1024 * 1024)} MiB)" }.toTypedArray()
                val titulo = getString(if (compactar) R.string.ft_btn_compactar else R.string.ft_btn_descompactar)
                AlertDialog.Builder(this).setTitle(titulo)
                    .setMultiChoiceItems(nomes, marcados) { _, i, m -> marcados[i] = m }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val escolhidos = lista.filterIndexed { i, _ -> marcados[i] }
                        if (escolhidos.isEmpty()) return@setPositiveButton
                        val fazer = { apagar: Boolean ->
                            tarefa(titulo) { _ ->
                                if (compactar) Ferramentas.compactar(this, escolhidos, apagar) else Ferramentas.descompactar(this, escolhidos, apagar)
                            }
                        }
                        AlertDialog.Builder(this).setTitle(titulo)
                            .setMessage(getString(if (compactar) R.string.ft_conf_compactar else R.string.ft_conf_descompactar, escolhidos.size))
                            .setPositiveButton(R.string.ft_manter) { _, _ -> fazer(false) }
                            .setNeutralButton(R.string.ft_apagar) { _, _ -> fazer(true) }
                            .setNegativeButton(R.string.cancelar, null).show()
                    }
                    .setNegativeButton(R.string.cancelar, null).show()
            }
        }
    }

    private fun caixaServidor(pai: LinearLayout, chave: String, rotulo: String, ajuda: () -> String): CheckBox {
        val cb = CheckBox(this).apply {
            text = rotulo
            isChecked = prefs.getBoolean("srv_$chave", true)
            setOnCheckedChangeListener { _, marcado -> prefs.edit().putBoolean("srv_$chave", marcado).apply() }
        }
        pai.addView(linha(cb, info(rotulo, ajuda)))
        return cb
    }

    // ------------------------------------------------------------------ textos de ajuda

    private fun ipCelular() = Rede.cabo()?.ip ?: "<IP>"
    private fun ipPs2() = Rede.cabo()?.let { Rede.sugestaoPs2(it.ip) } ?: "<IP PS2>"

    private fun ajudaSmb() = getString(R.string.ajuda_smb, ipCelular(), ipPs2(), ServidorService.PORTA_SMB)

    private fun mostrarConfigPs2() {
        val partes = mutableListOf<String>()
        if (cbSmb.isChecked && rbPasta.isChecked) partes += "— SMB1 —\n" + ajudaSmb()
        if (cbUdpfs.isChecked) partes += "— UDPFS —\n" + getString(R.string.ajuda_udpfs, ipPs2())
        if (cbUdpbd.isChecked) partes += "— UDPBD —\n" + getString(R.string.ajuda_udpbd, ipPs2())
        if (cbHttp.isChecked && rbPasta.isChecked) partes += "— HTTP —\n" + getString(R.string.ajuda_http, ipCelular())
        dialogo(getString(R.string.config_ps2_titulo), partes.joinToString("\n\n").ifEmpty { getString(R.string.av_marque_servidor) })
    }

    // ------------------------------------------------------------------ atualizações

    private fun atualizarRede() {
        val cabo = Rede.cabo()
        tvRede.text = if (cabo == null) getString(R.string.rede_nenhuma) else getString(R.string.rede_ok, cabo.ip, Rede.sugestaoPs2(cabo.ip))
        btnAncoragem.visibility = if (cabo == null) View.VISIBLE else View.GONE
    }

    private fun atualizarPermissoes() {
        val pasta = pastaSalva()
        val precisaArquivos = rbPasta.isChecked && acesso() == ACESSO_NORMAL && pasta != null && !Fonte.ehSaf(pasta) && !Fonte.ehPriv(pasta)
        btnArquivos.visibility = if (precisaArquivos && !temAcessoArquivos()) View.VISIBLE else View.GONE
        val bateria = Build.VERSION.SDK_INT < 23 || (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        btnBateria.visibility = if (bateria) View.GONE else View.VISIBLE
    }

    private fun acesso(): String = prefs.getString("acesso", ACESSO_NORMAL) ?: ACESSO_NORMAL

    private fun atualizarVolumes() {
        listaVolumes.removeAllViews()
        val modo = acesso()
        for (v in Compat.volumes(this)) {
            val rotulo = if (v.primario) getString(R.string.btn_vol, v.descricao) else getString(R.string.btn_vol_usb, v.descricao)
            listaVolumes.addView(botao(rotulo) { escolherVolume(v, modo) })
        }
        btnOutraPasta.text = getString(if (modo == ACESSO_NORMAL) R.string.btn_outra_pasta else R.string.btn_digitar)
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

    /** Pasta salva, corrigindo caminhos /mnt/media_rw/... gravados por versões anteriores (só root lê lá). */
    private fun pastaSalva(): String? = prefs.getString("pasta", null)?.let {
        if (it.startsWith("/mnt/media_rw/")) "/storage/" + it.removePrefix("/mnt/media_rw/") else it
    }

    /** Abre a pasta numa thread (no root/Shizuku pode esperar o "Permitir") e mostra o resultado. */
    private fun verificarPasta() {
        val pasta = pastaSalva()
        val minha = ++verificacao
        if (pasta == null) {
            pastaAbriu = false
            tvPasta.text = getString(R.string.pasta_nenhuma)
            btnCriarPastas.visibility = View.GONE
            atualizarEscolha()
            atualizarPermissoes()
            return
        }
        val descricao = Fonte.descricao(this, pasta)
        pastaAbriu = null
        tvPasta.text = "$descricao  ${getString(R.string.pasta_verificando)}"
        btnCriarPastas.visibility = View.GONE
        atualizarEscolha()
        atualizarPermissoes()

        if (Fonte.ehPriv(pasta) && Privilegio.modo(pasta) == Privilegio.SHIZUKU && !Privilegio.shizukuPermitido()) {
            if (!Privilegio.shizukuRodando()) {
                mostrarPasta(minha, descricao, null, getString(R.string.shizuku_parado))
            } else {
                Privilegio.pedirShizuku(REQ_SHIZUKU)
                mostrarPasta(minha, descricao, null, getString(R.string.shizuku_negado))
            }
            return
        }
        val saf = Fonte.ehSaf(pasta)
        val acesso = if (saf) Fonte.temPermissao(this, pasta) else Fonte.ehPriv(pasta) || temAcessoArquivos()
        if (!acesso) {
            mostrarPasta(minha, descricao, null, getString(if (saf) R.string.pasta_saf_de_novo else R.string.pasta_sem_perm))
            return
        }
        fundo.execute {
            var filhos: List<br.ps2ponte.core.fs.VFile>? = null
            var erro: String? = null
            try {
                filhos = Fonte.raiz(this, pasta).listFiles()
            } catch (e: Exception) {
                erro = getString(R.string.pasta_erro, e.message ?: e.javaClass.simpleName)
            }
            val temOpl = listOf("DVD", "CD").any { nome -> filhos?.any { it.isDirectory && it.name.equals(nome, true) } == true }
            runOnUiThread { mostrarPasta(minha, descricao, filhos?.let { temOpl }, erro) }
        }
    }

    /** [temOpl]: null = não abriu (com [erro]); senão se tem DVD/ ou CD/. */
    private fun mostrarPasta(minha: Int, descricao: String, temOpl: Boolean?, erro: String?) {
        if (minha != verificacao || isFinishing) return
        pastaAbriu = temOpl != null
        val estado = when {
            erro != null -> erro
            temOpl == null -> getString(R.string.pasta_nao_abriu)
            temOpl -> getString(R.string.pasta_ok)
            else -> getString(R.string.pasta_sem_dvd)
        }
        tvPasta.text = "$descricao  $estado"
        btnCriarPastas.visibility = if (temOpl == false) View.VISIBLE else View.GONE
        if (temOpl != null) escolhaAberta = false
        atualizarEscolha()
        atualizarPermissoes()
        atualizarEstado()
    }

    private fun atualizarUsb() {
        listaUsb.removeAllViews()
        val salvo = prefs.getString("usb", null)
        val devs = UsbMassStorage.list(usb)
        for (d in devs) {
            val rb = RadioButton(this).apply {
                text = UsbMassStorage.label(d)
                tag = d.deviceName
                id = View.generateViewId()
            }
            listaUsb.addView(rb)
            if (d.deviceName == salvo || devs.size == 1) rb.isChecked = true
        }
        tvUsbVazio.visibility = if (devs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun atualizarModo() {
        val pasta = rbPasta.isChecked
        painelPasta.visibility = if (pasta) View.VISIBLE else View.GONE
        painelUsb.visibility = if (pasta) View.GONE else View.VISIBLE
        // No modo bloco não há pasta para o SMB
        cbSmb.isEnabled = pasta
        cbHttp.isEnabled = pasta
        atualizarPermissoes()
    }

    private fun atualizarEstado() {
        val rodando = Estado.rodando
        btnIniciar.text = getString(if (rodando) R.string.btn_parar else R.string.btn_iniciar)
        val cabo = Rede.cabo()
        val (titulo, detalhe, cor) = when {
            rodando && Estado.ligadoEm == 0L -> Triple(getString(R.string.st_iniciando), "", COR_NEUTRA)
            rodando -> {
                val linhas = mutableListOf(Estado.resumo)
                Estado.stats.lastClient?.let { ip ->
                    val s = ((System.currentTimeMillis() - Estado.stats.lastRequestAt.get()) / 1000).coerceAtLeast(0)
                    linhas += getString(R.string.st_ultimo_pedido, ip, if (s < 60) "${s}s" else "${s / 60}min")
                }
                if (velocidade.isNotEmpty()) linhas += velocidade
                Estado.dica?.let { linhas.add(0, it) }
                Triple(getString(R.string.st_servindo), linhas.joinToString("\n"), if (Estado.dica != null) COR_ALERTA else COR_OK)
            }
            cabo == null -> Triple(getString(R.string.st_sem_cabo), getString(R.string.st_sem_cabo_dica), COR_ALERTA)
            rbPasta.isChecked && pastaAbriu != true -> Triple(getString(R.string.st_parado), getString(R.string.st_escolha_pasta), COR_NEUTRA)
            else -> Triple(getString(R.string.st_parado), getString(R.string.st_pronto), COR_NEUTRA)
        }
        tvStatusTitulo.text = titulo
        tvStatusDetalhe.text = detalhe
        tvStatusDetalhe.visibility = if (detalhe.isEmpty()) View.GONE else View.VISIBLE
        cartaoStatus.background = fundoCartao(cor)

        tvLog.text = Estado.texto()
        val livre = !rodando
        for (cb in listOf(cbUdpfs, cbUdpbd)) cb.isEnabled = livre
        cbSmb.isEnabled = livre && rbPasta.isChecked
        cbHttp.isEnabled = livre && rbPasta.isChecked
        cbHttpAntigo.isEnabled = livre && rbPasta.isChecked
        rbPasta.isEnabled = livre
        rbUsb.isEnabled = livre
        for (i in 0 until grupoAcesso.childCount) grupoAcesso.getChildAt(i).isEnabled = livre
        cbDescompactar.isEnabled = livre
        if (abaFerramentas.visibility == View.VISIBLE) atualizarFerramentas()
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

    // ------------------------------------------------------------------ ações

    private fun servidoresEscolhidos(): Set<String> = buildSet {
        if (cbSmb.isChecked && rbPasta.isChecked) add(ServidorService.SRV_SMB)
        if (cbUdpfs.isChecked) add(ServidorService.SRV_UDPFS)
        if (cbUdpbd.isChecked) add(ServidorService.SRV_UDPBD)
        if (cbHttp.isChecked && rbPasta.isChecked) add(ServidorService.SRV_HTTP)
    }

    private fun iniciarOuParar() {
        if (Estado.rodando) {
            ServidorService.parar(this)
            return
        }
        val servidores = servidoresEscolhidos()
        if (servidores.isEmpty()) return aviso(getString(R.string.av_marque_servidor))
        if (rbPasta.isChecked) {
            val pasta = pastaSalva() ?: return aviso(getString(R.string.av_escolha_pasta))
            if (Fonte.ehSaf(pasta)) {
                if (!Fonte.temPermissao(this, pasta)) return aviso(getString(R.string.av_saf_de_novo))
            } else if (!Fonte.ehPriv(pasta) && !temAcessoArquivos()) {
                aviso(getString(R.string.av_perm_arquivos))
                abrirPermissaoArquivos()
                return
            }
            when (pastaAbriu) {
                null -> return aviso(getString(R.string.av_aguarde_pasta))
                false -> return aviso(getString(R.string.av_pasta_nao_abriu, Fonte.descricao(this, pasta)))
                true -> {}
            }
            ServidorService.iniciar(this, ServidorService.MODO_PASTA, pasta, null, servidores)
        } else {
            val nome = listaUsb.findViewById<RadioButton>(listaUsb.checkedRadioButtonId)?.tag as? String
                ?: return aviso(getString(R.string.av_escolha_usb))
            val dev = usb.deviceList[nome] ?: return aviso(getString(R.string.av_usb_desconectado)).also { atualizarUsb() }
            if (usb.hasPermission(dev)) {
                ServidorService.iniciar(this, ServidorService.MODO_USB, null, nome, servidores)
            } else {
                val pi = PendingIntent.getBroadcast(this, 0, Intent(ACAO_PERMISSAO_USB).setPackage(packageName), Compat.MUTAVEL)
                usb.requestPermission(dev, pi)
            }
        }
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
        atualizarVolumes()
        verificarPasta()
    }

    private fun criarPastasOpl() {
        val pasta = pastaSalva() ?: return
        fundo.execute {
            try {
                val raiz = Fonte.raiz(this, pasta)
                for (nome in listOf("DVD", "CD", "ART", "CFG", "CHT", "LNG", "THM", "VMC", "POPS", "APPS")) raiz.child(nome).mkdirs()
                runOnUiThread { aviso(getString(R.string.opl_criadas)); verificarPasta() }
            } catch (e: Exception) {
                runOnUiThread { aviso(getString(R.string.pasta_erro, e.message ?: "")) }
            }
        }
    }

    private fun outraPasta() {
        val modo = acesso()
        if (modo == ACESSO_NORMAL) return escolherPasta()
        // Root/Shizuku: o caminho é digitado (o seletor do Android não sabe dos privilégios)
        val atual = pastaSalva()?.takeIf { Fonte.ehPriv(it) }?.let { Privilegio.caminho(it) } ?: Environment.getExternalStorageDirectory().path
        val campo = EditText(this).apply {
            setText(atual)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val moldura = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this)
            .setTitle(R.string.caminho_titulo)
            .setView(moldura)
            .setPositiveButton(R.string.ok) { _, _ ->
                val c = campo.text.toString().trim().trimEnd('/')
                if (c.startsWith("/")) definirPasta("$modo:$c")
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
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

    private fun copiarLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), Estado.texto()))
        aviso(getString(R.string.log_copiado))
    }

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PASTA = 10
        private const val REQ_SHIZUKU = 11
        private const val ACESSO_NORMAL = "normal"
        private const val ACAO_PERMISSAO_USB = "br.ps2ponte.PERMISSAO_USB"

        private const val COR_CARTAO = 0x1A808080
        private const val COR_NEUTRA = 0x26808080
        private const val COR_OK = 0x3322AA55
        private const val COR_ALERTA = 0x40E0A020
    }
}
