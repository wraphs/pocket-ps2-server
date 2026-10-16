package br.ps2ponte.core.smb

import br.ps2ponte.core.EventLog
import br.ps2ponte.core.fs.LocalTree
import java.io.File

/**
 * Sobe o servidor SMB1 numa pasta, para testar com clientes de PC (scripts/teste_smb_impacket.py).
 * .\g.ps1 :core:runMain "-PmainClass=br.ps2ponte.core.smb.SmbDevMainKt" "--args=C:\pasta 1445"
 * Argumentos: <pasta> <porta> [ro] [--tempo=segundos] [--sem-unicode]
 */
fun main(args: Array<String>) {
    val pos = args.filter { !it.startsWith("--") && it != "ro" }
    val dir = File(pos.getOrElse(0) { "." })
    val port = pos.getOrElse(1) { "1445" }.toInt()
    val readOnly = "ro" in args
    val seconds = args.firstOrNull { it.startsWith("--tempo=") }?.substringAfter('=')?.toLong()
    val unicode = "--sem-unicode" !in args
    val server = SmbServer(LocalTree(dir, readOnly), port, log = EventLog { println(it) }, unicode = unicode)
    server.start()
    println("SMB1 servindo ${dir.absolutePath} na porta ${server.localPort}" + (if (readOnly) " (somente leitura)" else ""))
    if (seconds != null) {
        Thread.sleep(seconds * 1000)
        server.close()
        println("SMB1 parado")
    } else {
        Thread.sleep(Long.MAX_VALUE)
    }
}
