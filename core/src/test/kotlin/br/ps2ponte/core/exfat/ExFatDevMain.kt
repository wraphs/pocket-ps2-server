package br.ps2ponte.core.exfat

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.SPARSE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Random

/**
 * Ferramenta de desenvolvimento do exFAT virtual (validação com o FatFs do PS2, ver scripts/fatfs-harness).
 *
 *   .\g.ps1 :core:runMain "-PmainClass=br.ps2ponte.core.exfat.ExFatDevMainKt" "--args=<pasta> <saida.img> [MiB livres]"
 *       gera o disco virtual da pasta e grava todos os setores num .img (esparso: setores zerados não são gravados)
 *   ... "--args=criar-arvore <pasta>"
 *       cria uma árvore de teste parecida com a do OPL (inclui um .iso esparso de 5 GiB; sempre a mesma)
 *   ... "--args=servir <pasta> <porta> [MiB livres]"
 *       serve o disco ao vivo em 127.0.0.1 para o harness ("harness_ps2 tcp:<porta> <pasta>"):
 *       exercita a camada em memória e a gravação direta com as gravações reais do FatFs
 *   ... "--args=medir <pasta>"
 *       mede o tempo da fotografia e da montagem
 */
fun main(args: Array<String>) {
    when {
        args.size == 2 && args[0] == "criar-arvore" -> createTree(File(args[1]))
        args.size == 2 && args[0] == "medir" -> measure(File(args[1]))
        args.size >= 3 && args[0] == "servir" ->
            serve(File(args[1]), args[2].toInt(), (args.getOrNull(3)?.toLong() ?: 64L) shl 20)
        args.size >= 2 -> dump(File(args[0]), File(args[1]), (args.getOrNull(2)?.toLong() ?: 64L) shl 20)
        else -> println("uso: <pasta> <saida.img> [MiB livres]  |  criar-arvore <pasta>  |  medir <pasta>")
    }
}

/**
 * Serve o disco virtual ao vivo em 127.0.0.1:porta para o harness do FatFs ("tcp:porta"), uma conexão.
 * Protocolo: op(1) lba(8, LE) count(4, LE) [dados se 'W'] -> status(1) [dados se 'R' | setores(8) se 'S'].
 */
private fun serve(root: File, port: Int, free: Long) {
    VirtualExFat.build(root, { println(it) }, freeBytes = free).use { dev ->
        java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress()).use { ss ->
            println("servindo ${dev.sectorCount} setores em 127.0.0.1:$port")
            ss.accept().use { s ->
                s.tcpNoDelay = true
                val inp = java.io.DataInputStream(java.io.BufferedInputStream(s.getInputStream(), 1 shl 16))
                val out = java.io.BufferedOutputStream(s.getOutputStream(), 1 shl 16)
                val h = ByteArray(13)
                var buf = ByteArray(1 shl 20)
                var reads = 0L
                var writes = 0L
                loop@ while (true) {
                    inp.readFully(h)
                    val lba = ExFat.get64(h, 1)
                    val count = ExFat.get32(h, 9)
                    if (count * 512 > buf.size) buf = ByteArray(count * 512)
                    when (h[0].toInt().toChar()) {
                        'S' -> { out.write(0); out.write(ByteArray(8).also { ExFat.put64(it, 0, dev.sectorCount) }) }
                        'R' -> try {
                            dev.read(lba, count, buf, 0); reads++
                            out.write(0); out.write(buf, 0, count * 512)
                        } catch (e: java.io.IOException) { println("leitura $lba+$count: ${e.message}"); out.write(1) }
                        'W' -> {
                            inp.readFully(buf, 0, count * 512)
                            try { dev.write(lba, count, buf, 0); writes++; out.write(0) } catch (e: java.io.IOException) {
                                println("gravação $lba+$count: ${e.message}"); out.write(1)
                            }
                        }
                        'Q' -> { out.write(0); out.flush(); break@loop }
                        else -> break@loop
                    }
                    out.flush()
                }
                dev.flush()
                println("fim: $reads leituras, $writes gravações; camada em memória com ${dev.overlaySectors} setores")
            }
        }
    }
}

/** Tempo da fotografia e da montagem do disco (5 vezes, para ver o efeito do JIT). */
private fun measure(root: File) {
    repeat(5) {
        val t0 = System.nanoTime()
        val snap = TreeSnapshot(br.ps2ponte.core.fs.LocalFile.root(root), { }, java.util.TimeZone.getDefault())
        val t1 = System.nanoTime()
        VirtualExFat.build(root).use { dev ->
            val t2 = System.nanoTime()
            println("fotografia ${(t1 - t0) / 1_000_000} ms; build completo ${(t2 - t1) / 1_000_000} ms; ${dev.describe()} (${snap.files})")
        }
    }
}

private fun dump(root: File, out: File, free: Long) {
    VirtualExFat.build(root, { println(it) }, freeBytes = free).use { dev ->
        val total = dev.sectorCount
        println("disco: $total setores (${VirtualExFat.gib(total * 512)} GiB)")
        out.delete()
        val t0 = System.nanoTime()
        var written = 0L
        FileChannel.open(out.toPath(), CREATE_NEW, WRITE, SPARSE).use { ch ->
            val chunk = 2048
            val buf = ByteArray(chunk * 512)
            var lba = 0L
            while (lba < total) {
                val n = minOf(chunk.toLong(), total - lba).toInt()
                dev.read(lba, n, buf, 0)
                val len = n * 512
                if (!allZero(buf, len)) {
                    val bb = ByteBuffer.wrap(buf, 0, len)
                    var p = lba * 512
                    while (bb.hasRemaining()) p += ch.write(bb, p)
                    written += len
                }
                lba += n
            }
            if (ch.size() < total * 512) ch.write(ByteBuffer.wrap(ByteArray(512)), (total - 1) * 512)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("imagem: ${out.path} (${VirtualExFat.gib(written)} GiB com dados), lida pelo dispositivo em $ms ms")
    }
}

private fun allZero(b: ByteArray, len: Int): Boolean {
    for (i in 0 until len) if (b[i].toInt() != 0) return false
    return true
}

private fun createTree(root: File) {
    require(!root.exists() || root.list()!!.isEmpty()) { "a pasta precisa estar vazia: $root" }
    val rnd = Random(2026)
    fun data(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun file(rel: String, n: Int) = File(root, rel).apply { parentFile.mkdirs(); writeBytes(data(n)) }

    // .iso de 5 GiB esparso, com dados no começo, perto de 1 GiB, cruzando 4 GiB e no fim.
    val big = File(root, "DVD/SLUS_200.62.Jogo de Teste Muito Comprido (USA).iso")
    big.parentFile.mkdirs()
    val bigSize = (5L shl 30) + 12_345
    FileChannel.open(big.toPath(), CREATE_NEW, WRITE, SPARSE).use { ch ->
        for (off in listOf(0L, 1L shl 30, (4L shl 30) - 524_288, (4L shl 30) + 123_457, bigSize - 70_000)) {
            val n = minOf(1L shl 20, bigSize - off).toInt()
            val bb = ByteBuffer.wrap(data(n))
            var p = off
            while (bb.hasRemaining()) p += ch.write(bb, p)
        }
    }
    file("DVD/Jogo Pequeno.iso", (37 shl 20) + 777)
    file("CD/SCUS_942.43.Crash Ação.iso", (3 shl 20) + 5)
    file("CD/disco 2.bin", 128 * 1024) // exatamente 1 cluster
    for (i in 0 until 3000) file("ART/SLUS_%05d.45_COV.png".format(i), 1 + rnd.nextInt(5000))
    File(root, "CFG").mkdirs()
    File(root, "CFG/conf_opl.cfg").writeText("hdd_mode=0\nbdm_cache=16\nremember_last=1\n")
    File(root, "CFG/SLUS_200.62.cfg").writeText("\$Compatibility=0\n")
    file("VMC/slot0.bin", 8 shl 20)
    file("Coleção é ç/Pokémon Edição Especial.txt", 3333)
    file("Coleção é ç/nível 1/nível 2/nível 3/nível 4/nível 5/fundo.txt", 77)
    file("Um nome de arquivo bem comprido que ocupa várias entradas de nome no exFAT.txt", 4096)
    File(root, "vazio.txt").writeBytes(ByteArray(0))
    File(root, "pasta vazia").mkdirs()
    println("árvore de teste criada em $root (o .iso grande ocupa ${if (big.length() == bigSize) "5 GiB lógicos" else "?"})")
}
