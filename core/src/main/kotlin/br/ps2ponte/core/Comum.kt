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

/** Contadores de tráfego compartilhados por todos os servidores (para a velocidade na tela). */
class TransferStats {
    val bytesRead = AtomicLong()
    val bytesWritten = AtomicLong()
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
