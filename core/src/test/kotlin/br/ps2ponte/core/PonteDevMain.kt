package br.ps2ponte.core

import br.ps2ponte.core.exfat.VirtualExFat
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.smb.SmbServer
import br.ps2ponte.core.udpbd.UdpbdServer
import br.ps2ponte.core.udpfs.UdpfsServer
import java.io.File

/** Sobe os três servidores sobre uma pasta, igual ao modo Pasta do app. Argumentos: <pasta> [MiB livres no exFAT]. */
fun main(args: Array<String>) {
    val log = EventLog { println(it) }
    val stats = TransferStats()
    val root = File(args[0])
    val tree = LocalTree(root)
    val exfat = VirtualExFat.build(root, log, freeBytes = (args.getOrNull(1)?.toLong() ?: 4096L) shl 20)
    log.log("Disco virtual: ${exfat.describe()}")
    for (s in listOf(
        SmbServer(tree, port = 1445, log = log, stats = stats),
        UdpfsServer(tree, exfat, log = log, stats = stats),
        UdpbdServer(exfat, log = log, stats = stats),
    )) {
        s.start()
        log.log("${s.name} ligado")
    }
    println("PONTE pronta")
    Thread.sleep(Long.MAX_VALUE)
}
