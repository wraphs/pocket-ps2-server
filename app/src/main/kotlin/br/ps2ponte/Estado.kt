package br.ps2ponte

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

    val stats = TransferStats()

    private val linhas = ArrayDeque<String>()
    private val ouvintes = CopyOnWriteArrayList<() -> Unit>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val eventLog = EventLog { log(it) }

    fun log(msg: String) {
        synchronized(linhas) {
            linhas.addLast("${hora.format(Date())}  $msg")
            while (linhas.size > 300) linhas.removeFirst()
        }
        avisar()
    }

    fun texto(): String = synchronized(linhas) { linhas.joinToString("\n") }

    fun avisar() = ouvintes.forEach { it() }
    fun ouvir(f: () -> Unit) = ouvintes.add(f)
    fun esquecer(f: () -> Unit) = ouvintes.remove(f)
}
