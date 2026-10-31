package br.ps2ponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.block.BlockDevice
import br.ps2ponte.core.block.Particoes
import br.ps2ponte.core.block.Sector512Adapter
import br.ps2ponte.core.exfat.VirtualExFat
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.smb.SmbServer
import br.ps2ponte.core.udpbd.UdpbdServer
import br.ps2ponte.core.udpfs.UdpfsServer
import br.ps2ponte.usb.UsbMassStorage
import java.io.Closeable
import java.net.BindException

/** Serviço em primeiro plano que mantém os servidores rodando com a tela apagada. */
class ServidorService : Service() {

    private val servidores = mutableListOf<PonteServer>()
    private var dispositivo: BlockDevice? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val trava = Any()
    private val principal = Handler(Looper.getMainLooper())

    /**
     * Muda a cada Iniciar e a cada Parar (sob [trava]). A thread de início de uma sessão que já foi
     * encerrada (Parar no meio do "Iniciando...") desiste e fecha o que abriu, em vez de ligar
     * servidores órfãos ou derrubar a sessão nova.
     */
    private var sessao = 0

    /** Thread que fecha os servidores do último Parar (o SMB espera até 2 s; o USB pode esperar timeouts). */
    @Volatile private var fechando: Thread? = null
    private var receptorDesconexao: BroadcastReceiver? = null

    /** Diagnóstico da sessão (cabo, PS2 que não aparece...), a cada segundo na thread principal. */
    private var vigia: Dicas.Vigia? = null
    private val tickVigia = object : Runnable {
        override fun run() {
            vigia?.passo()
            principal.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_PARAR -> {
                encerrar()
                stopSelf()
            }
            ACAO_INICIAR -> {
                if (Estado.rodando) return START_NOT_STICKY
                criarCanal()
                val n = notificacao(getString(R.string.st_iniciando))
                if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else startForeground(1, n)
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketps2:servidor").apply { acquire(24 * 60 * 60 * 1000L) }
                Estado.rodando = true
                Estado.resumo = getString(R.string.st_iniciando)
                Estado.dica = null
                Estado.ligadoEm = 0
                Estado.stats.reset()
                Estado.avisar()
                val modo = intent.getStringExtra(EXTRA_MODO) ?: MODO_PASTA
                val pasta = intent.getStringExtra(EXTRA_PASTA).orEmpty()
                val usb = intent.getStringExtra(EXTRA_USB).orEmpty()
                val srv = intent.getStringArrayExtra(EXTRA_SERVIDORES)?.toSet() ?: setOf(SRV_SMB, SRV_UDPFS, SRV_UDPBD)
                val minha = synchronized(trava) { ++sessao }
                if (modo == MODO_USB) vigiarDesconexao(usb)
                Thread({ iniciar(minha, modo, pasta, usb, srv) }, "pocketps2-inicio").start()
            }
        }
        return START_NOT_STICKY
    }

    private fun atual(minha: Int) = synchronized(trava) { sessao == minha }

    /** Guarda [recurso] na sessão [minha]; se ela já foi encerrada, fecha o recurso e devolve false. */
    private fun guardar(minha: Int, recurso: Closeable, guarda: () -> Unit): Boolean {
        val ok = synchronized(trava) { (sessao == minha).also { if (it) guarda() } }
        if (!ok) try { recurso.close() } catch (_: Exception) {}
        return ok
    }

    private fun iniciar(minha: Int, modo: String, pasta: String, usbNome: String, srv: Set<String>) {
        val log = Estado.eventLog
        val stats = Estado.stats
        try {
            fechando?.join(5000) // espera o Parar anterior soltar as portas e o pendrive
            val novos = mutableListOf<PonteServer>()
            if (modo == MODO_PASTA) {
                val nome = Fonte.descricao(this, pasta)
                val raiz = Fonte.raiz(this, pasta)
                if (!raiz.isDirectory) throw IllegalStateException(getString(R.string.err_pasta_sumiu, nome))
                val tree = LocalTree(raiz)
                Estado.log(getString(R.string.log_pasta, nome))
                if (SRV_SMB in srv) novos += SmbServer(tree, port = PORTA_SMB, shareName = "PS2SMB", log = log, stats = stats)
                // O disco virtual atende o UDPBD e o modo bloco do UDPFS
                var exfat: VirtualExFat? = null
                if (SRV_UDPBD in srv || SRV_UDPFS in srv) {
                    Estado.log(getString(R.string.log_montando))
                    val d = VirtualExFat.build(raiz, log)
                    if (!guardar(minha, d) { dispositivo = d }) return
                    exfat = d
                }
                if (SRV_UDPFS in srv) novos += UdpfsServer(tree, exfat, log = log, stats = stats)
                if (SRV_UDPBD in srv) novos += UdpbdServer(exfat!!, log = log, stats = stats)
            } else {
                val usb = getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList[usbNome] ?: throw IllegalStateException(getString(R.string.err_usb_sumiu))
                val ms = UsbMassStorage.open(usb, dev)
                val bd: BlockDevice = if (ms.sectorSize == 512) ms else Sector512Adapter(ms)
                if (!guardar(minha, bd) { dispositivo = bd }) return
                val gib = bd.sectorCount * 512 / (1024.0 * 1024 * 1024)
                Estado.log(getString(R.string.log_pendrive, ms.description, gib, if (ms.readOnly) getString(R.string.log_protegido) else ""))
                val sistemas = Particoes.sistemas(bd)
                if (sistemas.isNotEmpty()) Estado.log(getString(R.string.log_particoes, sistemas.joinToString()))
                when {
                    "NTFS" in sistemas -> Estado.log(getString(R.string.log_ntfs))
                    sistemas.isEmpty() -> Estado.log(getString(R.string.log_fs_desconhecido))
                }
                if (SRV_UDPBD in srv) novos += UdpbdServer(bd, log = log, stats = stats)
                if (SRV_UDPFS in srv) novos += UdpfsServer(null, bd, log = log, stats = stats)
            }

            val ligados = mutableListOf<String>()
            for (s in novos) {
                if (!atual(minha)) return
                try {
                    s.start()
                } catch (e: Exception) {
                    Estado.log(getString(R.string.log_porta_falhou, descricao(s), e.message))
                    if (e is BindException || e.message?.contains("EADDRINUSE") == true) Estado.log(getString(R.string.dica_porta))
                    continue
                }
                if (!guardar(minha, s) { servidores += s }) return
                ligados += descricao(s)
                Estado.log(getString(R.string.log_ligado, descricao(s)))
            }
            if (ligados.isEmpty()) throw IllegalStateException(getString(R.string.log_nenhum))
            synchronized(trava) {
                // Sob a trava: um Parar não cabe entre a conferência e o notify (a notificação voltaria depois dele)
                if (sessao != minha) return
                Estado.resumo = getString(R.string.servindo, ligados.joinToString(" · "))
                Estado.ligadoEm = System.currentTimeMillis()
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notificacao(Estado.resumo))
            }
            principal.post {
                if (atual(minha)) {
                    vigia = Dicas.Vigia(this, stats)
                    principal.removeCallbacks(tickVigia)
                    principal.post(tickVigia)
                }
            }
            Estado.avisar()
        } catch (e: Exception) {
            if (!atual(minha)) return // o Parar chegou no meio e já encerrou tudo
            Estado.log(getString(R.string.log_erro, e.message ?: e.javaClass.simpleName))
            // Encerra na thread principal, como o Parar: não corre com um Iniciar novo nem com o wake lock
            principal.post {
                if (atual(minha)) {
                    encerrar()
                    stopSelf()
                }
            }
        }
    }

    private fun descricao(s: PonteServer) = when (s) {
        is SmbServer -> "SMB1 (TCP $PORTA_SMB)"
        is UdpfsServer -> "UDPFS (UDP ${UdpfsServer.PORT})"
        is UdpbdServer -> "UDPBD (UDP ${UdpbdServer.PORT})"
        else -> s.name
    }

    private fun encerrar() {
        val parados: List<PonteServer>
        val dev: BlockDevice?
        synchronized(trava) {
            sessao++
            parados = servidores.toList()
            servidores.clear()
            dev = dispositivo
            dispositivo = null
        }
        principal.removeCallbacks(tickVigia)
        vigia = null
        receptorDesconexao?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        receptorDesconexao = null
        if (parados.isNotEmpty() || dev != null) {
            // Fora da thread principal: fechar pode levar alguns segundos e travaria a tela
            fechando = Thread({
                for (s in parados) try { s.close() } catch (_: Exception) {}
                try { dev?.close() } catch (_: Exception) {}
            }, "pocketps2-parar").apply { start() }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (Estado.rodando) Estado.log(getString(R.string.log_parado))
        Estado.rodando = false
        Estado.resumo = ""
        Estado.dica = null
        Estado.ligadoEm = 0
        Estado.avisar()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        encerrar()
        super.onDestroy()
    }

    /** No modo pendrive, para o servidor se o pendrive for desconectado (em vez de encher o log de erros). */
    private fun vigiarDesconexao(nome: String) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (d?.deviceName == nome && Estado.rodando) {
                    Estado.log(getString(R.string.log_usb_saiu))
                    encerrar()
                    stopSelf()
                }
            }
        }
        Compat.registrar(this, r, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), exportado = true)
        receptorDesconexao = r
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.canal), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacao(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), Compat.IMUTAVEL)
        val parar = PendingIntent.getService(this, 1, Intent(this, ServidorService::class.java).setAction(ACAO_PARAR), Compat.IMUTAVEL)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else Notification.Builder(this)
        @Suppress("DEPRECATION")
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(0, getString(R.string.notif_parar), parar).build())
            .build()
    }

    companion object {
        const val ACAO_INICIAR = "br.ps2ponte.INICIAR"
        const val ACAO_PARAR = "br.ps2ponte.PARAR"
        const val EXTRA_MODO = "modo"
        const val EXTRA_PASTA = "pasta"
        const val EXTRA_USB = "usb"
        const val MODO_PASTA = "pasta"
        const val MODO_USB = "usb"
        const val EXTRA_SERVIDORES = "servidores"
        const val SRV_SMB = "smb"
        const val SRV_UDPFS = "udpfs"
        const val SRV_UDPBD = "udpbd"
        const val PORTA_SMB = 1445
        private const val CANAL = "servidor"

        fun iniciar(ctx: Context, modo: String, pasta: String?, usb: String?, servidores: Set<String>) {
            val i = Intent(ctx, ServidorService::class.java).setAction(ACAO_INICIAR)
                .putExtra(EXTRA_MODO, modo).putExtra(EXTRA_PASTA, pasta).putExtra(EXTRA_USB, usb)
                .putExtra(EXTRA_SERVIDORES, servidores.toTypedArray())
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun parar(ctx: Context) {
            ctx.startService(Intent(ctx, ServidorService::class.java).setAction(ACAO_PARAR))
        }
    }
}
