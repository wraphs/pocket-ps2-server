package br.ps2ponte.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Destino das mensagens de log mostradas na tela do app (thread-safe). */
fun interface EventLog {
    fun log(msg: String)
}

object NoLog : EventLog {
    override fun log(msg: String) {}
}

/** Idioma das mensagens do log; o app liga o inglês quando o celular não está em português. */
object Idioma {
    @Volatile var ingles = false
}

/** Mensagem do log no idioma escolhido em [Idioma]. */
fun t(pt: String, en: String) = if (Idioma.ingles) en else pt

/** Contadores de tráfego compartilhados por todos os servidores (para a velocidade e o diagnóstico na tela). */
class TransferStats {
    val bytesRead = AtomicLong()
    val bytesWritten = AtomicLong()

    /** Hora (ms) do último pacote/pedido vindo do PS2; 0 = nenhum desde o início. */
    val lastRequestAt = AtomicLong()
    /** IP de quem mandou o último pedido. */
    @Volatile var lastClient: String? = null

    fun request(ip: String?) {
        lastRequestAt.set(System.currentTimeMillis())
        if (ip != null) lastClient = ip
    }

    fun reset() {
        lastRequestAt.set(0)
        lastClient = null
    }
}

/**
 * Um servidor de protocolo (SMB1, UDPFS, UDPBD).
 * [start] abre os sockets e cria as próprias threads; lança exceção se não conseguir abrir a porta.
 * [close] para tudo e libera as portas; pode ser chamado mais de uma vez.
 */
interface PonteServer : Closeable {
    val name: String
    fun start()
}
