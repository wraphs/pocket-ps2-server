package br.ps2ponte

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.ps2ponte.usb.UsbMassStorage
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var usb: UsbManager

    private lateinit var tvRede: TextView
    private lateinit var tvPerm: TextView
    private lateinit var btnArquivos: Button
    private lateinit var btnBateria: Button
    private lateinit var rbPasta: RadioButton
    private lateinit var rbUsb: RadioButton
    private lateinit var painelPasta: LinearLayout
    private lateinit var tvPasta: TextView
    private lateinit var btnCriarPastas: Button
    private lateinit var listaVolumes: LinearLayout
    private lateinit var painelUsb: LinearLayout
    private lateinit var listaUsb: RadioGroup
    private lateinit var tvUsbVazio: TextView
    private lateinit var btnIniciar: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvAjuda: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var ultimoBytes = 0L
    private var ultimoTempo = 0L
    private var velocidade = ""

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
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                ServidorService.iniciar(this@MainActivity, ServidorService.MODO_USB, null, dev.deviceName)
            } else {
                aviso("Sem permissão para usar o pendrive")
            }
        }
    }

    /** Pendrive conectado/desconectado (aviso do sistema). */
    private val receptorUsb = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = atualizarUsb()
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ps2ponte", MODE_PRIVATE)
        usb = getSystemService(UsbManager::class.java)
        setContentView(montarTela())
        title = getString(R.string.app_name)

        // RECEIVER_* são constantes: em Android < 13 o sistema simplesmente ignora a flag
        registerReceiver(receptorPermissao, IntentFilter(ACAO_PERMISSAO_USB), Context.RECEIVER_NOT_EXPORTED)
        val filtro = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(receptorUsb, filtro, Context.RECEIVER_EXPORTED)

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
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tela

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun texto(t: String = "") = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, dp(2), 0, dp(2))
        setTextIsSelectable(true)
    }

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { acao() }
    }

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        raiz.addView(texto("Servidor de jogos para o PS2 pelo cabo de rede (SMB1, UDPFS e UDPBD)."))

        raiz.addView(titulo("1. Rede"))
        tvRede = texto().also { raiz.addView(it) }
        raiz.addView(botao("Abrir Ancoragem (ligar \"Ancoragem Ethernet\")") { abrirAncoragem() })

        raiz.addView(titulo("2. Permissões"))
        tvPerm = texto().also { raiz.addView(it) }
        btnArquivos = botao("Permitir acesso a todos os arquivos") { abrirPermissaoArquivos() }.also { raiz.addView(it) }
        btnBateria = botao("Não deixar a bateria parar o servidor") { pedirBateria() }.also { raiz.addView(it) }

        raiz.addView(titulo("3. O que servir"))
        val grupo = RadioGroup(this)
        rbPasta = RadioButton(this).apply { text = "Uma pasta do celular ou do pendrive (OPL, NHDDL e Neutrino) — recomendado"; id = View.generateViewId() }
        rbUsb = RadioButton(this).apply { text = "Pendrive/HD inteiro em modo bloco (só Neutrino/XEB+ via UDPBD)"; id = View.generateViewId() }
        grupo.addView(rbPasta)
        grupo.addView(rbUsb)
        // Marca antes de pôr o ouvinte: o isChecked dispara o ouvinte do RadioGroup, e o atualizarModo()
        // mexeria em painelPasta/painelUsb, que ainda não existem (o app fechava ao abrir). O onResume
        // chama atualizarModo().
        if (prefs.getString("modo", ServidorService.MODO_PASTA) == ServidorService.MODO_USB) rbUsb.isChecked = true else rbPasta.isChecked = true
        grupo.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("modo", if (id == rbUsb.id) ServidorService.MODO_USB else ServidorService.MODO_PASTA).apply()
            atualizarModo()
        }
        raiz.addView(grupo)

        painelPasta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvPasta = texto().also { painelPasta.addView(it) }
        btnCriarPastas = botao("Criar as pastas do OPL aqui (DVD, CD, ART, CFG, VMC...)") { criarPastasOpl() }.also { painelPasta.addView(it) }
        listaVolumes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        painelPasta.addView(listaVolumes)
        painelPasta.addView(botao("Escolher outra pasta…") { escolherPasta() })
        raiz.addView(painelPasta)

        painelUsb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        painelUsb.addView(texto("O pendrive precisa estar no mesmo hub. Antes de iniciar, ejete-o em Configurações › Manutenção › Armazenamento (ele continua aparecendo aqui). Enquanto o servidor roda, o Android não o enxerga; depois de parar, desconecte e reconecte."))
        listaUsb = RadioGroup(this).also { painelUsb.addView(it) }
        listaUsb.setOnCheckedChangeListener { g, id ->
            g.findViewById<RadioButton>(id)?.tag?.let { prefs.edit().putString("usb", it as String).apply() }
        }
        tvUsbVazio = texto("Nenhum pendrive/HD USB encontrado.").also { painelUsb.addView(it) }
        painelUsb.addView(botao("Procurar de novo") { atualizarUsb() })
        raiz.addView(painelUsb)

        btnIniciar = Button(this).apply {
            textSize = 20f
            isAllCaps = false
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { iniciarOuParar() }
        }
        raiz.addView(btnIniciar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        tvStatus = texto().apply { setTypeface(typeface, Typeface.BOLD) }.also { raiz.addView(it) }

        raiz.addView(titulo("Como configurar o PS2"))
        tvAjuda = texto().also { raiz.addView(it) }

        raiz.addView(titulo("Log"))
        tvLog = texto().apply { typeface = Typeface.MONOSPACE; textSize = 12f }.also { raiz.addView(it) }

        return ScrollView(this).apply { addView(raiz) }
    }

    // ------------------------------------------------------------------ atualizações

    private fun atualizarRede() {
        val ips = Rede.enderecos()
        val cabo = ips.firstOrNull { it.cabo }
        val sb = StringBuilder()
        if (cabo == null) {
            sb.append("⚠ Nenhuma rede por cabo.\nConecte o hub (com o cabo no PS2) e ligue em Configurações › Conexões › Roteador Wi-Fi e ancoragem › Ancoragem Ethernet.")
        } else {
            sb.append("✔ Cabo (${cabo.nome}): IP do celular ${cabo.ip}\n")
            sb.append("No PS2 use: IP ${Rede.sugestaoPs2(cabo.ip)}, máscara ${Rede.mascara(cabo.prefixo)} (ou DHCP no OPL)")
        }
        val outros = ips.filter { !it.cabo }
        if (outros.isNotEmpty()) sb.append("\nOutras redes: " + outros.joinToString { "${it.nome} ${it.ip}" })
        tvRede.text = sb.toString()
        atualizarAjuda(cabo)
    }

    private fun atualizarAjuda(cabo: Rede.Ip?) {
        val ipCel = cabo?.ip ?: "<IP do celular>"
        val ipPs2 = cabo?.let { Rede.sugestaoPs2(it.ip) } ?: "<IP do PS2>"
        tvAjuda.text = """
            OPL (qualquer versão) – modo Pasta, via SMB:
            • Configurações de rede › Tipo de IP: DHCP (ou IP $ipPs2, máscara 255.255.255.0)
            • IP do PC (servidor): $ipCel   • Porta: ${ServidorService.PORTA_SMB}
            • Compartilhamento: PS2SMB   • Usuário: guest   • Senha: vazia
            • Dispositivo ETH: Automático; salve e reinicie o OPL

            NHDDL 1.2.2 + Neutrino 1.8 (mais rápido) – modo Pasta, via UDPFS:
            • nhddl.yaml:  mode: udpfs   e   udpfs_ip: $ipPs2
            • neutrino/config/bsd-udpfs.toml:  args = ["ip=$ipPs2"]

            Neutrino/XEB+ com UDPBD (pasta ou pendrive inteiro):
            • neutrino/config/bsd-udpbd.toml:  args = ["ip=$ipPs2"]
        """.trimIndent()
    }

    private fun atualizarPermissoes() {
        val arquivos = Environment.isExternalStorageManager()
        val bateria = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        btnArquivos.visibility = if (arquivos) View.GONE else View.VISIBLE
        btnBateria.visibility = if (bateria) View.GONE else View.VISIBLE
        tvPerm.text = buildString {
            append(if (arquivos) "✔ Acesso a todos os arquivos" else "⚠ Falta o acesso a todos os arquivos (necessário para o modo Pasta)")
            append("\n")
            append(if (bateria) "✔ Bateria sem restrição" else "⚠ A economia de bateria pode derrubar o servidor com a tela apagada")
        }
    }

    private fun atualizarVolumes() {
        listaVolumes.removeAllViews()
        val sm = getSystemService(StorageManager::class.java)
        for (v in sm.storageVolumes) {
            if (v.state != Environment.MEDIA_MOUNTED) continue
            val dir = caminhoAcessivel(v) ?: continue
            listaVolumes.addView(botao("Usar a raiz de: ${v.getDescription(this)}\n$dir") { definirPasta(dir) })
        }
        mostrarPasta()
    }

    /**
     * Caminho do volume que o app consegue abrir. Em alguns Samsung o Android informa o caminho interno
     * /mnt/media_rw/<uuid> (só o sistema acessa); para os apps o pendrive fica em /storage/<uuid>.
     */
    private fun caminhoAcessivel(v: StorageVolume): String? {
        val dir = v.directory?.path
        val uuid = v.uuid ?: return dir
        return if (dir == null || dir.startsWith("/mnt/") || !File(dir).canRead()) "/storage/$uuid" else dir
    }

    /** Pasta salva, corrigindo caminhos /mnt/media_rw/... gravados por versões anteriores. */
    private fun pastaSalva(): String? = prefs.getString("pasta", null)?.let {
        if (it.startsWith("/mnt/media_rw/")) "/storage/" + it.removePrefix("/mnt/media_rw/") else it
    }

    private fun mostrarPasta() {
        val pasta = pastaSalva()
        if (pasta == null) {
            tvPasta.text = "Nenhuma pasta escolhida."
            btnCriarPastas.visibility = View.GONE
            return
        }
        val f = File(pasta)
        val temOpl = listOf("DVD", "CD").any { nome -> f.listFiles()?.any { it.isDirectory && it.name.equals(nome, true) } == true }
        tvPasta.text = "Pasta: $pasta\n" + when {
            !Environment.isExternalStorageManager() -> "⚠ dê a permissão de arquivos para o app enxergar a pasta"
            !f.isDirectory -> "⚠ pasta não encontrada (o pendrive foi desconectado?)"
            temOpl -> "✔ tem as pastas do OPL (DVD/CD)"
            else -> "⚠ não achei as pastas DVD/ ou CD/ aqui"
        }
        btnCriarPastas.visibility = if (f.isDirectory && !temOpl && Environment.isExternalStorageManager()) View.VISIBLE else View.GONE
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
    }

    private fun atualizarEstado() {
        btnIniciar.text = if (Estado.rodando) "■  Parar servidor" else "▶  Iniciar servidor"
        tvStatus.text = if (Estado.rodando) "${Estado.resumo}\n$velocidade" else "Parado"
        tvLog.text = Estado.texto()
    }

    private fun medirVelocidade() {
        val agora = System.currentTimeMillis()
        val bytes = Estado.stats.bytesRead.get() + Estado.stats.bytesWritten.get()
        if (ultimoTempo > 0 && agora > ultimoTempo) {
            val mbs = (bytes - ultimoBytes) / 1048576.0 / ((agora - ultimoTempo) / 1000.0)
            velocidade = String.format(Locale("pt", "BR"), "%.2f MB/s · %.1f MB transferidos", mbs, bytes / 1048576.0)
        }
        ultimoBytes = bytes
        ultimoTempo = agora
    }

    // ------------------------------------------------------------------ ações

    private fun iniciarOuParar() {
        if (Estado.rodando) {
            ServidorService.parar(this)
            return
        }
        if (rbPasta.isChecked) {
            if (!Environment.isExternalStorageManager()) {
                aviso("Permita o acesso a todos os arquivos primeiro")
                abrirPermissaoArquivos()
                return
            }
            val pasta = pastaSalva() ?: return aviso("Escolha uma pasta")
            if (!File(pasta).isDirectory) return aviso("Pasta não encontrada: $pasta")
            ServidorService.iniciar(this, ServidorService.MODO_PASTA, pasta, null)
        } else {
            val nome = listaUsb.findViewById<RadioButton>(listaUsb.checkedRadioButtonId)?.tag as? String
                ?: return aviso("Escolha um pendrive/HD")
            val dev = usb.deviceList[nome] ?: return aviso("Pendrive desconectado").also { atualizarUsb() }
            if (usb.hasPermission(dev)) {
                ServidorService.iniciar(this, ServidorService.MODO_USB, null, nome)
            } else {
                val pi = PendingIntent.getBroadcast(this, 0, Intent(ACAO_PERMISSAO_USB).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
                usb.requestPermission(dev, pi)
            }
        }
    }

    private fun definirPasta(caminho: String) {
        prefs.edit().putString("pasta", caminho).apply()
        mostrarPasta()
    }

    private fun criarPastasOpl() {
        val pasta = pastaSalva() ?: return
        for (nome in listOf("DVD", "CD", "ART", "CFG", "CHT", "LNG", "THM", "VMC", "POPS", "APPS")) File(pasta, nome).mkdirs()
        mostrarPasta()
        aviso("Pastas criadas. Coloque os .iso em DVD (ou CD)")
    }

    private fun escolherPasta() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PASTA)
        } catch (e: Exception) {
            aviso("Não consegui abrir o seletor de pastas")
        }
    }

    @Deprecated("API da plataforma; sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PASTA || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val caminho = caminhoDaArvore(uri) ?: return aviso("Essa pasta não é do armazenamento do celular/pendrive")
        definirPasta(caminho)
    }

    /** "primary:PS2" -> /storage/emulated/0/PS2 ; "1234-ABCD:PS2" -> /storage/1234-ABCD/PS2 ; "home:PS2" -> .../Documents/PS2 */
    private fun caminhoDaArvore(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(uri)
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (vol.equals("primary", true)) {
            Environment.getExternalStorageDirectory().path
        } else if (vol.equals("home", true)) {
            // Raiz "Documentos" do seletor (ExternalStorageProvider)
            File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS).path
        } else {
            getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.uuid.equals(vol, true) }?.let { caminhoAcessivel(it) } ?: "/storage/$vol"
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

    private fun abrirPermissaoArquivos() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) { aviso("Abra Configurações › Apps › PS2 Ponte › Permissões") }
        }
    }

    private fun pedirBateria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { aviso("Abra Configurações › Apps › PS2 Ponte › Bateria › Sem restrições") }
        }
    }

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PASTA = 10
        private const val ACAO_PERMISSAO_USB = "br.ps2ponte.PERMISSAO_USB"
    }
}
