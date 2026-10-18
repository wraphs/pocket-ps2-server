package br.ps2ponte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var painelPerm: LinearLayout
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
    private lateinit var cbSmb: CheckBox
    private lateinit var cbUdpfs: CheckBox
    private lateinit var cbUdpbd: CheckBox
    private lateinit var btnIniciar: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

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
                ServidorService.iniciar(this@MainActivity, ServidorService.MODO_USB, null, dev.deviceName, servidoresEscolhidos())
            } else {
                aviso("Sem permissão para usar o pendrive")
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

    private fun texto(t: String = "") = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { acao() }
    }

    /** Botãozinho "ⓘ" que abre uma explicação. [conteudo] é calculado na hora (usa o IP atual). */
    private fun info(titulo: String, conteudo: () -> String) = TextView(this).apply {
        text = "ⓘ"
        textSize = 20f
        setPadding(dp(12), dp(4), dp(12), dp(4))
        contentDescription = "Ajuda: $titulo"
        setOnClickListener {
            AlertDialog.Builder(this@MainActivity).setTitle(titulo).setMessage(conteudo()).setPositiveButton("OK", null).show()
        }
    }

    /** Linha: [principal] seguido do ⓘ. */
    private fun linha(principal: View, ajuda: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(principal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ajuda)
    }

    private fun titulo(t: String, ajudaTitulo: String? = null, ajuda: (() -> String)? = null): View {
        val tv = TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val v = if (ajuda != null) linha(tv, info(ajudaTitulo ?: t, ajuda)) else tv
        v.setPadding(0, dp(16), 0, dp(2))
        return v
    }

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }

        raiz.addView(titulo("Rede", "Rede pelo cabo") { ajudaRede() })
        tvRede = texto()
        raiz.addView(linha(tvRede, botao("Ancoragem") { abrirAncoragem() }))

        painelPerm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnArquivos = botao("Permitir acesso aos arquivos") { abrirPermissaoArquivos() }.also { painelPerm.addView(it) }
        btnBateria = botao("Liberar bateria em segundo plano") { pedirBateria() }.also { painelPerm.addView(it) }
        raiz.addView(painelPerm)

        raiz.addView(titulo("Jogos", "De onde vêm os jogos") { ajudaFonte() })
        val grupo = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rbPasta = RadioButton(this).apply { text = "Pasta"; id = View.generateViewId() }
        rbUsb = RadioButton(this).apply { text = "Pendrive inteiro (bloco)"; id = View.generateViewId() }
        grupo.addView(rbPasta)
        grupo.addView(rbUsb)
        // Marca antes de pôr o ouvinte: o isChecked dispara o ouvinte, e o atualizarModo() mexeria em
        // painéis que ainda não existem. O onResume chama atualizarModo().
        if (prefs.getString("modo", ServidorService.MODO_PASTA) == ServidorService.MODO_USB) rbUsb.isChecked = true else rbPasta.isChecked = true
        grupo.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("modo", if (id == rbUsb.id) ServidorService.MODO_USB else ServidorService.MODO_PASTA).apply()
            atualizarModo()
        }
        raiz.addView(grupo)

        painelPasta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvPasta = texto().also { painelPasta.addView(it) }
        btnCriarPastas = botao("Criar pastas do OPL (DVD, CD, ART…)") { criarPastasOpl() }.also { painelPasta.addView(it) }
        listaVolumes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        painelPasta.addView(listaVolumes)
        painelPasta.addView(botao("Outra pasta…") { escolherPasta() })
        raiz.addView(painelPasta)

        painelUsb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listaUsb = RadioGroup(this).also { painelUsb.addView(it) }
        listaUsb.setOnCheckedChangeListener { g, id ->
            g.findViewById<RadioButton>(id)?.tag?.let { prefs.edit().putString("usb", it as String).apply() }
        }
        tvUsbVazio = texto("Nenhum pendrive/HD USB encontrado.").also { painelUsb.addView(it) }
        painelUsb.addView(botao("Procurar de novo") { atualizarUsb() })
        raiz.addView(painelUsb)

        raiz.addView(titulo("Servidores"))
        cbSmb = caixaServidor(raiz, "smb", "SMB1 · OPL", "SMB1 (OPL)") { ajudaSmb() }
        cbUdpfs = caixaServidor(raiz, "udpfs", "UDPFS · NHDDL, Neutrino, RiptOPL", "UDPFS") { ajudaUdpfs() }
        cbUdpbd = caixaServidor(raiz, "udpbd", "UDPBD · Neutrino, XEB+, RiptOPL", "UDPBD") { ajudaUdpbd() }

        btnIniciar = Button(this).apply {
            textSize = 20f
            isAllCaps = false
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { iniciarOuParar() }
        }
        raiz.addView(btnIniciar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        tvStatus = texto().apply { setTypeface(typeface, Typeface.BOLD) }.also { raiz.addView(it) }

        raiz.addView(titulo("Log"))
        tvLog = texto().apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }.also { raiz.addView(it) }

        return ScrollView(this).apply { addView(raiz) }
    }

    private fun caixaServidor(raiz: LinearLayout, chave: String, rotulo: String, ajudaTitulo: String, ajuda: () -> String): CheckBox {
        val cb = CheckBox(this).apply {
            text = rotulo
            isChecked = prefs.getBoolean("srv_$chave", true)
            setOnCheckedChangeListener { _, marcado -> prefs.edit().putBoolean("srv_$chave", marcado).apply() }
        }
        raiz.addView(linha(cb, info(ajudaTitulo, ajuda)))
        return cb
    }

    // ------------------------------------------------------------------ textos de ajuda

    private fun ipCelular() = Rede.cabo()?.ip ?: "<IP do celular>"
    private fun ipPs2() = Rede.cabo()?.let { Rede.sugestaoPs2(it.ip) } ?: "<IP do PS2>"

    private fun ajudaRede() = """
        Ligue o hub (com o cabo no PS2) e ative em Configurações › Conexões › Roteador Wi-Fi e ancoragem › Ancoragem Ethernet. O celular passa a dar IP pelo cabo.

        IP do celular: ${ipCelular()}
        No PS2: DHCP (OPL) ou IP ${ipPs2()}, máscara 255.255.255.0.
    """.trimIndent()

    private fun ajudaFonte() = """
        Pasta: uma pasta do celular ou a raiz do pendrive (exFAT, FAT32 ou NTFS) com DVD/, CD/, ART/… Liga SMB1, UDPFS e UDPBD (o UDPBD vê a pasta como um disco exFAT virtual). No pendrive, o Android pede para você tocar em "Usar esta pasta" uma vez.

        Pendrive inteiro: o app lê o pendrive direto pelo USB (precisa estar em exFAT ou FAT32). Só UDPBD e UDPFS em modo bloco. Ejete o pendrive em Configurações › Armazenamento antes de iniciar; depois de parar, desconecte e reconecte.
    """.trimIndent()

    private fun ajudaSmb() = """
        OPL / RiptOPL › Network Settings:
        • IP Address Type: DHCP (ou ${ipPs2()})
        • Address: ${ipCelular()}
        • Port: ${ServidorService.PORTA_SMB}
        • Share: PS2SMB · User: guest · Password: vazio
        • ETH Start Mode: Auto; salve e reinicie o OPL.
    """.trimIndent()

    private fun ajudaUdpfs() = """
        Mais rápido que o SMB. Serve a pasta (e o disco virtual).

        NHDDL 1.2.2: nhddl.yaml com
          mode: udpfs
          udpfs_ip: ${ipPs2()}
        Neutrino: config/bsd-udpfs.toml → args = ["ip=${ipPs2()}"]
        RiptOPL: use o mesmo IP do PS2 das configurações de rede.
    """.trimIndent()

    private fun ajudaUdpbd() = """
        Bloco: o PS2 vê um disco exFAT (a pasta como disco virtual, ou o pendrive inteiro).

        Neutrino: config/bsd-udpbd.toml → args = ["ip=${ipPs2()}"]
        RiptOPL / XEB+: dispositivo UDPBD, com o IP do PS2 ${ipPs2()}.
    """.trimIndent()

    // ------------------------------------------------------------------ atualizações

    private fun atualizarRede() {
        val cabo = Rede.cabo()
        tvRede.text = if (cabo == null) "⚠ Sem rede pelo cabo" else "✔ ${cabo.ip} · PS2: ${Rede.sugestaoPs2(cabo.ip)}"
    }

    private fun atualizarPermissoes() {
        val arquivos = Environment.isExternalStorageManager()
        val bateria = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val pastaInterna = pastaSalva()?.let { !Fonte.ehSaf(it) } == true
        btnArquivos.visibility = if (arquivos || !pastaInterna) View.GONE else View.VISIBLE
        btnBateria.visibility = if (bateria) View.GONE else View.VISIBLE
    }

    private fun atualizarVolumes() {
        listaVolumes.removeAllViews()
        val sm = getSystemService(StorageManager::class.java)
        for (v in sm.storageVolumes) {
            if (v.state != Environment.MEDIA_MOUNTED) continue
            if (v.isPrimary) {
                val dir = caminhoAcessivel(v) ?: continue
                listaVolumes.addView(botao("Raiz: ${v.getDescription(this)}") { definirPasta(dir) })
            } else {
                // Pendrive USB: o Android só libera pelo seletor, que abre já na raiz ("Usar esta pasta")
                listaVolumes.addView(botao("Raiz: ${v.getDescription(this)} (pendrive)") {
                    try {
                        startActivityForResult(v.createOpenDocumentTreeIntent(), REQ_PASTA)
                    } catch (e: Exception) {
                        escolherPasta()
                    }
                })
            }
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
            tvPasta.text = "Escolha a pasta dos jogos:"
            btnCriarPastas.visibility = View.GONE
            return
        }
        val saf = Fonte.ehSaf(pasta)
        val acesso = if (saf) Fonte.temPermissao(this, pasta) else Environment.isExternalStorageManager()
        val raiz = if (acesso) Fonte.raiz(this, pasta) else null
        val filhos = raiz?.listFiles()
        val temOpl = listOf("DVD", "CD").any { nome -> filhos?.any { it.isDirectory && it.name.equals(nome, true) } == true }
        val estado = when {
            !acesso && saf -> "⚠ toque de novo no pendrive abaixo"
            !acesso -> "⚠ falta a permissão de arquivos"
            filhos == null -> "⚠ não abriu; escolha de novo"
            temOpl -> "✔"
            else -> "⚠ sem DVD/ ou CD/"
        }
        tvPasta.text = "${Fonte.descricao(this, pasta)}  $estado"
        btnCriarPastas.visibility = if (filhos != null && !temOpl) View.VISIBLE else View.GONE
        atualizarPermissoes()
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
    }

    private fun atualizarEstado() {
        btnIniciar.text = if (Estado.rodando) "■  Parar" else "▶  Iniciar"
        tvStatus.text = if (Estado.rodando) "${Estado.resumo}\n$velocidade" else "Parado"
        tvLog.text = Estado.texto()
        val livre = !Estado.rodando
        for (cb in listOf(cbUdpfs, cbUdpbd)) cb.isEnabled = livre
        cbSmb.isEnabled = livre && rbPasta.isChecked
        rbPasta.isEnabled = livre
        rbUsb.isEnabled = livre
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

    private fun servidoresEscolhidos(): Set<String> = buildSet {
        if (cbSmb.isChecked && rbPasta.isChecked) add(ServidorService.SRV_SMB)
        if (cbUdpfs.isChecked) add(ServidorService.SRV_UDPFS)
        if (cbUdpbd.isChecked) add(ServidorService.SRV_UDPBD)
    }

    private fun iniciarOuParar() {
        if (Estado.rodando) {
            ServidorService.parar(this)
            return
        }
        val servidores = servidoresEscolhidos()
        if (servidores.isEmpty()) return aviso("Marque pelo menos um servidor")
        if (rbPasta.isChecked) {
            val pasta = pastaSalva() ?: return aviso("Escolha uma pasta")
            if (Fonte.ehSaf(pasta)) {
                if (!Fonte.temPermissao(this, pasta)) return aviso("Toque de novo no pendrive para liberar o acesso")
            } else if (!Environment.isExternalStorageManager()) {
                aviso("Permita o acesso aos arquivos primeiro")
                abrirPermissaoArquivos()
                return
            }
            if (Fonte.raiz(this, pasta).listFiles() == null) return aviso("Não consegui abrir a pasta: ${Fonte.descricao(this, pasta)}")
            ServidorService.iniciar(this, ServidorService.MODO_PASTA, pasta, null, servidores)
        } else {
            val nome = listaUsb.findViewById<RadioButton>(listaUsb.checkedRadioButtonId)?.tag as? String
                ?: return aviso("Escolha um pendrive/HD")
            val dev = usb.deviceList[nome] ?: return aviso("Pendrive desconectado").also { atualizarUsb() }
            if (usb.hasPermission(dev)) {
                ServidorService.iniciar(this, ServidorService.MODO_USB, null, nome, servidores)
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
        val raiz = Fonte.raiz(this, pasta)
        for (nome in listOf("DVD", "CD", "ART", "CFG", "CHT", "LNG", "THM", "VMC", "POPS", "APPS")) raiz.child(nome).mkdirs()
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
        if (uri.authority != "com.android.externalstorage.documents") return aviso("Essa pasta não é do armazenamento do celular/pendrive")
        val vol = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
        if (vol.equals("primary", true) || vol.equals("home", true)) {
            // Memória interna: por caminho (mais rápido; usa o "acesso a todos os arquivos")
            caminhoDaArvore(uri)?.let { definirPasta(it) }
        } else {
            // Pendrive: guarda a árvore do SAF com permissão permanente de leitura e gravação
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                return aviso("O Android não liberou o acesso permanente a essa pasta")
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
        val base = if (vol.equals("primary", true)) {
            Environment.getExternalStorageDirectory().path
        } else if (vol.equals("home", true)) {
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
