package br.ps2ponte.core.udpfs

import br.ps2ponte.core.block.FileBlockDevice
import br.ps2ponte.core.fs.LocalTree
import java.io.File

/** Sobe o servidor UDPFS para testes com scripts/ps2_udpfs_cliente.py. Argumentos: <pasta> <imagem|-> [porta]. */
fun main(args: Array<String>) {
    val tree = LocalTree(File(args[0]))
    val dev = args.getOrNull(1)?.takeIf { it != "-" }?.let { FileBlockDevice(File(it)) }
    val port = args.getOrNull(2)?.let { Integer.decode(it) } ?: UdpfsServer.PORT
    UdpfsServer(tree, dev, port, log = { println(it) }).start()
    println("UDPFS pronto na porta $port")
    Thread.sleep(Long.MAX_VALUE)
}
