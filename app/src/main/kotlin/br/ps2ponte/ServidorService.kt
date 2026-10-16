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
import java.io.File

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
                startForeground(1, notificacao("Iniciando..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps2ponte:servidor").apply { acquire(24 * 60 * 60 * 1000L) }
                Estado.rodando = true
                Estado.resumo = "Iniciando..."
                Estado.avisar()
                val modo = intent.getStringExtra(EXTRA_MODO) ?: MODO_PASTA
                val pasta = intent.getStringExtra(EXTRA_PASTA).orEmpty()
                val usb = intent.getStringExtra(EXTRA_USB).orEmpty()
                val minha = synchronized(trava) { ++sessao }
                if (modo == MODO_USB) vigiarDesconexao(usb)
                Thread({ iniciar(minha, modo, pasta, usb) }, "ps2ponte-inicio").start()
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

    private fun iniciar(minha: Int, modo: String, pasta: String, usbNome: String) {
        val log = Estado.eventLog
        val stats = Estado.stats
        try {
            fechando?.join(5000) // espera o Parar anterior soltar as portas e o pendrive
            val novos = mutableListOf<PonteServer>()
            if (modo == MODO_PASTA) {
                val raiz = File(pasta)
                if (!raiz.isDirectory) throw IllegalStateException("pasta não encontrada: $pasta")
                val tree = LocalTree(raiz)
                log.log("Pasta: $pasta")
                novos += SmbServer(tree, port = PORTA_SMB, shareName = "PS2SMB", log = log, stats = stats)
                log.log("Montando o disco exFAT virtual (para UDPBD)...")
                val exfat = VirtualExFat.build(raiz, log)
                if (!guardar(minha, exfat) { dispositivo = exfat }) return
                log.log("Disco virtual: ${exfat.describe()}")
                novos += UdpfsServer(tree, exfat, log = log, stats = stats)
                novos += UdpbdServer(exfat, log = log, stats = stats)
            } else {
                val usb = getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList[usbNome] ?: throw IllegalStateException("pendrive não encontrado (foi desconectado?)")
                val ms = UsbMassStorage.open(usb, dev)
                val bd: BlockDevice = if (ms.sectorSize == 512) ms else Sector512Adapter(ms)
                if (!guardar(minha, bd) { dispositivo = bd }) return
                val gib = bd.sectorCount * 512 / (1024.0 * 1024 * 1024)
                log.log("Pendrive: ${ms.description}, %.1f GiB%s".format(gib, if (ms.readOnly) ", protegido contra gravação" else ""))
                val sistemas = Particoes.sistemas(bd)
                if (sistemas.isNotEmpty()) log.log("Partições: ${sistemas.joinToString()}")
                when {
                    "NTFS" in sistemas -> log.log("⚠ Pendrive em NTFS: no modo bloco o PS2 só lê exFAT/FAT32. Use o modo Pasta (funciona com NTFS) ou formate em exFAT.")
                    sistemas.isEmpty() -> log.log("⚠ Não reconheci exFAT/FAT32 neste disco; o PS2 pode não enxergar os jogos no modo bloco.")
                }
                novos += UdpbdServer(bd, log = log, stats = stats)
                novos += UdpfsServer(null, bd, log = log, stats = stats)
            }

            val ligados = mutableListOf<String>()
            for (s in novos) {
                if (!atual(minha)) return
                try {
                    s.start()
                } catch (e: Exception) {
                    log.log("${s.name}: não consegui abrir a porta (${e.message})")
                    continue
                }
                if (!guardar(minha, s) { servidores += s }) return
                ligados += descricao(s)
                log.log("${descricao(s)} ligado")
            }
            if (ligados.isEmpty()) throw IllegalStateException("nenhum servidor ligou")
            synchronized(trava) {
                // Sob a trava: um Parar não cabe entre a conferência e o notify (a notificação voltaria depois dele)
                if (sessao != minha) return
                Estado.resumo = "Servindo: " + ligados.joinToString(" · ")
                getSystemService(NotificationManager::class.java).notify(1, notificacao(Estado.resumo))
            }
            Estado.avisar()
        } catch (e: Exception) {
            if (!atual(minha)) return // o Parar chegou no meio e já encerrou tudo
            log.log("Erro: ${e.message}")
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
        receptorDesconexao?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        receptorDesconexao = null
        if (parados.isNotEmpty() || dev != null) {
            // Fora da thread principal: fechar pode levar alguns segundos e travaria a tela
            fechando = Thread({
                for (s in parados) try { s.close() } catch (_: Exception) {}
                try { dev?.close() } catch (_: Exception) {}
            }, "ps2ponte-parar").apply { start() }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (Estado.rodando) Estado.log("Servidor parado")
        Estado.rodando = false
        Estado.resumo = ""
        Estado.avisar()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        encerrar()
        super.onDestroy()
    }

    /** No modo pendrive, para o servidor se o pendrive for desconectado (em vez de encher o log de erros). */
    private fun vigiarDesconexao(nome: String) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (d?.deviceName == nome && Estado.rodando) {
                    Estado.log("Pendrive desconectado")
                    encerrar()
                    stopSelf()
                }
            }
        }
        registerReceiver(r, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_EXPORTED)
        receptorDesconexao = r
    }

    private fun criarCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Servidor", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacao(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val parar = PendingIntent.getService(this, 1, Intent(this, ServidorService::class.java).setAction(ACAO_PARAR), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PS2 Ponte")
            .setContentText(texto)
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Parar", parar).build())
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
        const val PORTA_SMB = 1445
        private const val CANAL = "servidor"

        fun iniciar(ctx: Context, modo: String, pasta: String?, usb: String?) {
            val i = Intent(ctx, ServidorService::class.java).setAction(ACAO_INICIAR)
                .putExtra(EXTRA_MODO, modo).putExtra(EXTRA_PASTA, pasta).putExtra(EXTRA_USB, usb)
            ctx.startForegroundService(i)
        }

        fun parar(ctx: Context) {
            ctx.startService(Intent(ctx, ServidorService::class.java).setAction(ACAO_PARAR))
        }
    }
}
