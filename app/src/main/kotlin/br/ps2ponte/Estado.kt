package br.ps2ponte

import android.content.Context
import br.ps2ponte.core.EventLog
import br.ps2ponte.core.TransferStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Estado do servidor compartilhado entre o serviço e a tela (o log é chamado de várias threads). */
object Estado {
    @Volatile var rodando = false
    @Volatile var resumo = ""
    /** Problema atual apontado pelo diagnóstico (mostrado em destaque na tela); null = tudo certo. */
    @Volatile var dica: String? = null
    /** Hora em que os servidores terminaram de ligar (0 = ainda não). */
    @Volatile var ligadoEm = 0L

    val stats = TransferStats()

    private val linhas = ArrayDeque<String>()
    private val ouvintes = CopyOnWriteArrayList<() -> Unit>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var ctx: Context

    /** Mensagens do núcleo: depois de algumas, o log ganha uma linha "↳" explicando a causa provável. */
    val eventLog = EventLog { msg ->
        log(msg)
        if (::ctx.isInitialized) Dicas.depois(ctx, msg)?.let { log(it) }
    }

    fun iniciar(c: Context) {
        ctx = c.applicationContext
    }

    /** Log da aba Jogos (ferramentas), separado do log do servidor. */
    private val linhasJogos = ArrayDeque<String>()

    fun log(msg: String) = acrescentar(linhas, msg)

    fun logJogos(msg: String) = acrescentar(linhasJogos, msg)

    private fun acrescentar(l: ArrayDeque<String>, msg: String) {
        synchronized(l) {
            l.addLast("${hora.format(Date())}  $msg")
            while (l.size > 500) l.removeFirst()
        }
        avisar()
    }

    fun limpar() {
        synchronized(linhas) { linhas.clear() }
        avisar()
    }

    fun limparJogos() {
        synchronized(linhasJogos) { linhasJogos.clear() }
        avisar()
    }

    fun texto(): String = synchronized(linhas) { linhas.joinToString("\n") }

    fun textoJogos(): String = synchronized(linhasJogos) { linhasJogos.joinToString("\n") }

    fun avisar() = ouvintes.forEach { it() }
    fun ouvir(f: () -> Unit) = ouvintes.add(f)
    fun esquecer(f: () -> Unit) = ouvintes.remove(f)
}
