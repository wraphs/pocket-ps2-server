package br.ps2ponte.core.smb

import br.ps2ponte.core.t
import br.ps2ponte.core.EventLog
import br.ps2ponte.core.NoLog
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.fs.LocalTree
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Servidor SMB1 (dialeto "NT LM 0.12") para o OPL: menu (smbman do ps2sdk) e jogo (cdvdman/smb.c).
 *
 * - TCP direto (cabeçalho de 4 bytes); também aceita o pedido de sessão NetBIOS (0x81).
 * - Qualquer usuário/senha; qualquer compartilhamento (menos IPC$) é a pasta [tree]. O IPC$ só lista
 *   um compartilhamento de disco chamado [shareName] (RAP NetShareEnum, usado quando o OPL não tem nome).
 * - [unicode]: oferece CAP_UNICODE (como Windows/Samba). Sem ele, os nomes vão em UTF-8 "OEM".
 */
class SmbServer(
    private val tree: LocalTree,
    val port: Int = 1445,
    val shareName: String = "PS2SMB",
    private val log: EventLog = NoLog,
    private val stats: TransferStats = TransferStats(),
    val unicode: Boolean = true,
) : PonteServer {
    override val name = "SMB1"

    internal val paths = SmbPaths(tree)
    internal val statsRef: TransferStats get() = stats

    @Volatile
    private var listener: ServerSocket? = null
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<Socket, Thread>()

    /** Porta realmente aberta (útil com port = 0). */
    val localPort: Int get() = listener?.localPort ?: port

    override fun start() {
        check(listener == null) { "SMB já iniciado" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.receiveBufferSize = 256 * 1024
            ss.bind(InetSocketAddress(port), 16)
        } catch (e: IOException) {
            ss.close()
            throw e
        }
        listener = ss
        thread(isDaemon = true, name = "smb-accept") { acceptLoop(ss) }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed.get()) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            if (closed.get()) {
                closeQuietly(s)
                break
            }
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.sendBufferSize = 512 * 1024
                s.receiveBufferSize = 256 * 1024
            } catch (e: IOException) {
            }
            val ip = s.inetAddress?.hostAddress ?: "?"
            log(t("SMB: $ip conectou", "SMB: $ip connected"))
            val t = thread(start = false, isDaemon = true, name = "smb-$ip", priority = Thread.MAX_PRIORITY) {
                try {
                    SmbConnection(this, s, ip).run()
                } finally {
                    clients.remove(s)
                    closeQuietly(s)
                }
            }
            clients[s] = t
            t.start()
            if (closed.get()) closeQuietly(s)
        }
    }

    internal fun log(msg: String) = log.log(msg)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener?.let { closeQuietly(it) }
        // ArrayList(...) usa toArray(), seguro com remoções concorrentes; toList() lê o tamanho e depois
        // o 1º item, e estoura se a última conexão terminar entre as duas leituras.
        val threads = ArrayList(clients.values)
        for (s in clients.keys) closeQuietly(s)
        // As threads fecham os arquivos abertos ao sair; espera um pouco por elas.
        val deadline = System.currentTimeMillis() + 2000
        for (t in threads) {
            val left = deadline - System.currentTimeMillis()
            if (left > 0 && t !== Thread.currentThread()) t.join(left)
        }
    }

    private fun closeQuietly(c: AutoCloseable) {
        try {
            c.close()
        } catch (e: Exception) {
        }
    }
}
