package br.ps2ponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.PowerManager
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.block.BlockDevice
import br.ps2ponte.core.block.Sector512Adapter
import br.ps2ponte.core.exfat.VirtualExFat
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.smb.SmbServer
import br.ps2ponte.core.udpbd.UdpbdServer
import br.ps2ponte.core.udpfs.UdpfsServer
import br.ps2ponte.usb.UsbMassStorage
import java.io.File

/** Serviço em primeiro plano que mantém os servidores rodando com a tela apagada. */
class ServidorService : Service() {

    private val servidores = mutableListOf<PonteServer>()
    private var dispositivo: BlockDevice? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val trava = Any()

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
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps2ponte:servidor").apply { acquire() }
                Estado.rodando = true
                Estado.resumo = "Iniciando..."
                Estado.avisar()
                val modo = intent.getStringExtra(EXTRA_MODO) ?: MODO_PASTA
                val pasta = intent.getStringExtra(EXTRA_PASTA).orEmpty()
                val usb = intent.getStringExtra(EXTRA_USB).orEmpty()
                Thread({ iniciar(modo, pasta, usb) }, "ps2ponte-inicio").start()
            }
        }
        return START_NOT_STICKY
    }

    private fun iniciar(modo: String, pasta: String, usbNome: String) {
        val log = Estado.eventLog
        val stats = Estado.stats
        try {
            val novos = mutableListOf<PonteServer>()
            if (modo == MODO_PASTA) {
                val raiz = File(pasta)
                if (!raiz.isDirectory) throw IllegalStateException("pasta não encontrada: $pasta")
                val tree = LocalTree(raiz)
                log.log("Pasta: $pasta")
                novos += SmbServer(tree, port = PORTA_SMB, shareName = "PS2SMB", log = log, stats = stats)
                log.log("Montando o disco exFAT virtual (para UDPBD)...")
                val exfat = VirtualExFat.build(raiz, log)
                synchronized(trava) { dispositivo = exfat }
                log.log("Disco virtual: ${exfat.describe()}")
                novos += UdpfsServer(tree, exfat, log = log, stats = stats)
                novos += UdpbdServer(exfat, log = log, stats = stats)
            } else {
                val usb = getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList[usbNome] ?: throw IllegalStateException("pendrive não encontrado (foi desconectado?)")
                val ms = UsbMassStorage.open(usb, dev)
                val bd: BlockDevice = if (ms.sectorSize == 512) ms else Sector512Adapter(ms)
                synchronized(trava) { dispositivo = bd }
                val gib = bd.sectorCount * 512 / (1024.0 * 1024 * 1024)
                log.log("Pendrive: ${ms.description}, %.1f GiB%s".format(gib, if (ms.readOnly) ", protegido contra gravação" else ""))
                novos += UdpbdServer(bd, log = log, stats = stats)
                novos += UdpfsServer(null, bd, log = log, stats = stats)
            }

            val ligados = mutableListOf<String>()
            for (s in novos) {
                if (!Estado.rodando) break
                try {
                    s.start()
                    synchronized(trava) { servidores += s }
                    ligados += descricao(s)
                    log.log("${descricao(s)} ligado")
                } catch (e: Exception) {
                    log.log("${s.name}: não consegui abrir a porta (${e.message})")
                }
            }
            if (ligados.isEmpty()) throw IllegalStateException("nenhum servidor ligou")
            Estado.resumo = "Servindo: " + ligados.joinToString(" · ")
            getSystemService(NotificationManager::class.java).notify(1, notificacao(Estado.resumo))
            Estado.avisar()
        } catch (e: Exception) {
            log.log("Erro: ${e.message}")
            encerrar()
            stopSelf()
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
            parados = servidores.toList()
            servidores.clear()
            dev = dispositivo
            dispositivo = null
        }
        for (s in parados) try { s.close() } catch (_: Exception) {}
        try { dev?.close() } catch (_: Exception) {}
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
