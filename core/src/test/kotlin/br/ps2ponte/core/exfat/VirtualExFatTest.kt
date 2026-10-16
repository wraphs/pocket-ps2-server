package br.ps2ponte.core.exfat

import br.ps2ponte.core.EventLog
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VirtualExFatTest {
    @TempDir
    lateinit var tmp: File

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val log = EventLog { logs.add(it) }

    private fun bytes(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    /** Árvore pequena parecida com a do OPL. */
    private fun tree(): File {
        val root = File(tmp, "ps2").apply { mkdirs() }
        File(root, "DVD").mkdirs()
        File(root, "DVD/SLUS_123.45.Jogo de Teste Comprido.iso").writeBytes(bytes(300_000, 1))
        File(root, "DVD/pequeno.iso").writeBytes(bytes(1000, 2))
        File(root, "CD").mkdirs()
        File(root, "ART").mkdirs()
        for (i in 0 until 40) File(root, "ART/SLUS_%03d.45_COV.png".format(i)).writeBytes(bytes(100 + i * 37, 10L + i))
        File(root, "CFG").mkdirs()
        File(root, "CFG/conf_opl.cfg").writeText("hdd_mode=0\n")
        File(root, "VMC").mkdirs()
        File(root, "VMC/slot0.bin").writeBytes(bytes(8 * 1024 * 1024 + 4096, 3))
        File(root, "Coleção é ç/a/b/c").mkdirs()
        File(root, "Coleção é ç/a/b/c/fundo.txt").writeText("fundo")
        File(root, "vazio.txt").writeBytes(ByteArray(0))
        File(root, "pasta vazia").mkdirs()
        return root
    }

    private fun walk(dir: File, rel: String = ""): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        for (f in dir.listFiles()!!) {
            val r = "$rel/${f.name}"
            out[r] = f
            if (f.isDirectory) out.putAll(walk(f, r))
        }
        return out
    }

    private fun readAll(dev: VirtualExFat, rd: ExFatTestReader, e: ExFatTestReader.Entry): ByteArray =
        if (e.dataLength == 0L) ByteArray(0) else {
            val n = ((e.dataLength + 511) / 512).toInt()
            val b = ByteArray(n * 512)
            dev.read(rd.clusterLba(e.firstCluster), n, b, 0)
            b.copyOf(e.dataLength.toInt())
        }

    @Test
    fun mbrEBoot() {
        VirtualExFat.build(tree(), log, freeBytes = 64L shl 20).use { dev ->
            val rd = ExFatTestReader(dev)
            val m = rd.mbr
            assertEquals(0x55, m[510].toInt() and 0xFF)
            assertEquals(0xAA, m[511].toInt() and 0xFF)
            assertEquals(0x07, m[450].toInt())
            assertEquals(2048L, rd.partStart)
            assertEquals(dev.sectorCount - 2048, ExFatTestReader.u32(m, 458))
            for (i in 1..3) assertEquals(0, m[446 + i * 16 + 4].toInt(), "só uma partição")
            assertTrue(dev.sectorCount <= 0xFFFFFFFFL)
            assertArrayEquals(ByteArray(512), rd.sector(1))
            assertArrayEquals(ByteArray(512), rd.sector(2047))

            val v = rd.vbr
            assertEquals("EXFAT   ", String(v, 3, 8, Charsets.US_ASCII))
            assertEquals(0xEB, v[0].toInt() and 0xFF)
            for (i in 11 until 64) assertEquals(0, v[i].toInt(), "MustBeZero $i")
            assertEquals(2048L, ExFatTestReader.u64(v, 64))
            assertEquals(dev.sectorCount - 2048, rd.volumeLength)
            assertEquals(0x0100, ExFat.get16(v, 104))
            assertEquals(9, v[108].toInt())
            assertEquals(8, v[109].toInt())
            assertEquals(1, v[110].toInt())
            assertEquals(0x80, v[111].toInt() and 0xFF)
            assertTrue(rd.fatOffset >= 24)
            assertTrue(rd.fatLength * 128 >= rd.clusterCount + 2L)
            assertEquals(rd.fatOffset + rd.fatLength, rd.heapOffset)
            assertEquals(0L, (2048 + rd.heapOffset) % 256, "heap alinhado ao cluster")
            assertEquals(rd.volumeLength, rd.heapOffset + rd.clusterCount.toLong() * 256)

            // Região de boot: assinaturas, checksum repetido no setor 11, cópia nos setores 12..23.
            val main = ByteArray(12 * 512).also { dev.read(2048, 12, it, 0) }
            val backup = ByteArray(12 * 512).also { dev.read(2048 + 12, 12, it, 0) }
            assertArrayEquals(main, backup)
            for (s in 1..8) assertEquals(0xAA550000.toInt(), ExFat.get32(main, s * 512 + 508))
            val sum = ExFat.bootChecksum(main)
            for (i in 0 until 128) assertEquals(sum, ExFat.get32(main, 11 * 512 + i * 4))
        }
    }

    @Test
    fun entradasConferemComAPasta() {
        val root = tree()
        VirtualExFat.build(root, log, freeBytes = 64L shl 20).use { dev ->
            val rd = ExFatTestReader(dev)
            val found = LinkedHashMap<String, ExFatTestReader.Entry>()
            fun visit(first: Int, rel: String) {
                val list = rd.dir(first)
                val names = list.map { it.name }
                assertEquals(names.sortedWith(compareBy({ ExFat.upperName(it) }, { it })), names, "ordem em $rel")
                for (e in list) {
                    val r = "$rel/${e.name}"
                    found[r] = e
                    assertTrue(e.setChecksumOk, "SetChecksum de $r")
                    assertEquals(ExFat.nameHash(e.name), e.nameHash, "NameHash de $r")
                    assertEquals(0x01, e.flags, "AllocationPossible=1, NoFatChain=0 em $r")
                    assertEquals(e.dataLength, e.validLength)
                    if (e.isDir) {
                        assertEquals(0x10, e.attr)
                        assertEquals(0L, e.dataLength % rd.clusterBytes)
                        visit(e.firstCluster, r)
                    } else {
                        assertEquals(0x20, e.attr)
                    }
                }
            }
            visit(rd.rootCluster, "")
            val src = walk(root)
            assertEquals(src.keys.sorted(), found.keys.sorted())
            for ((r, f) in src) {
                val e = found.getValue(r)
                assertEquals(f.isDirectory, e.isDir, r)
                if (!f.isDirectory) {
                    assertEquals(f.length(), e.dataLength, r)
                    if (f.length() == 0L) assertEquals(0, e.firstCluster, "vazio sem cluster")
                    assertArrayEquals(f.readBytes(), readAll(dev, rd, e), "conteúdo de $r")
                }
            }
            // Folga: toda pasta tem pelo menos um cluster livre depois das entradas.
            val art = rd.dir(found.getValue("/ART").firstCluster)
            val used = art.sumOf { ExFat.entryCount(it.name) * 32 }
            assertTrue(found.getValue("/ART").dataLength >= used + rd.clusterBytes)

            val rootRaw = rd.cluster(rd.rootCluster)
            assertEquals(ExFat.ET_LABEL, rootRaw[0].toInt() and 0xFF)
            assertEquals(3, rootRaw[1].toInt())
            assertEquals("PS2", String(CharArray(3) { ExFat.get16(rootRaw, 2 + it * 2).toChar() }))
            assertEquals(ExFat.ET_BITMAP, rootRaw[32].toInt() and 0xFF)
            assertEquals(ExFat.ET_UPCASE, rootRaw[64].toInt() and 0xFF)
            val sp = rd.rootSpecial()
            assertEquals(UpCase.tableChecksum, sp.getValue(ExFat.ET_UPCASE).third)
            assertEquals(UpCase.table.size.toLong(), sp.getValue(ExFat.ET_UPCASE).second)
            val up = sp.getValue(ExFat.ET_UPCASE)
            assertArrayEquals(UpCase.table, rd.bytes(up.first, up.second))
        }
    }

    @Test
    fun fatEBitmapConsistentes() {
        VirtualExFat.build(tree(), log, freeBytes = 64L shl 20).use { dev ->
            val rd = ExFatTestReader(dev)
            assertEquals(0xFFFFFFF8.toInt(), rd.fat(0))
            assertEquals(-1, rd.fat(1))
            val owner = IntArray(rd.clusterCount + 2)
            var objs = 0
            fun take(first: Int, length: Long, what: String) {
                val ch = rd.chain(first)
                assertEquals((length + rd.clusterBytes - 1) / rd.clusterBytes, ch.size.toLong(), "clusters de $what")
                for (k in ch.indices) {
                    assertEquals(first + k, ch[k], "$what contíguo")
                    assertEquals(0, owner[ch[k]], "$what sobrepõe outro objeto")
                    owner[ch[k]] = ++objs
                }
            }
            val sp = rd.rootSpecial()
            val bm = sp.getValue(ExFat.ET_BITMAP)
            assertEquals((rd.clusterCount + 7L) / 8, bm.second)
            take(bm.first, bm.second, "bitmap")
            val up = sp.getValue(ExFat.ET_UPCASE)
            take(up.first, up.second, "up-case")
            val rootLen = rd.chain(rd.rootCluster).size.toLong() * rd.clusterBytes
            take(rd.rootCluster, rootLen, "raiz")
            fun visit(first: Int) {
                for (e in rd.dir(first)) {
                    if (e.dataLength > 0) take(e.firstCluster, e.dataLength, e.name)
                    if (e.isDir) visit(e.firstCluster)
                }
            }
            visit(rd.rootCluster)

            val bitmap = rd.bytes(bm.first, bm.second)
            var used = 0
            for (c in 2 until rd.clusterCount + 2) {
                val bit = (bitmap[(c - 2) / 8].toInt() shr ((c - 2) % 8)) and 1
                assertEquals(if (owner[c] != 0) 1 else 0, bit, "bitmap do cluster $c")
                if (owner[c] == 0) assertEquals(0, rd.fat(c), "FAT do cluster livre $c")
                used += bit
            }
            val free = rd.clusterCount - used
            assertEquals(512, free, "64 MiB livres = 512 clusters")
            val perc = rd.vbr[112].toInt()
            assertEquals(used * 100 / rd.clusterCount, perc)
        }
    }

    @Test
    fun gravacaoDentroDoArquivoVaiParaOArquivoReal() {
        val root = tree()
        VirtualExFat.build(root, log, freeBytes = 64L shl 20).use { dev ->
            val rd = ExFatTestReader(dev)
            val vmc = rd.dir(rd.dir(rd.rootCluster).first { it.name == "VMC" }.firstCluster).first()
            val lba = rd.clusterLba(vmc.firstCluster) + 300 // dentro do 2º cluster
            val data = bytes(4 * 512, 99)
            dev.write(lba, 4, data, 0)
            assertEquals(0, dev.overlaySectors)
            dev.flush()
            val real = File(root, "VMC/slot0.bin").readBytes()
            assertArrayEquals(data, real.copyOfRange(300 * 512, 304 * 512))
            assertEquals(8L * 1024 * 1024 + 4096, real.size.toLong())
            val back = ByteArray(4 * 512).also { dev.read(lba, 4, it, 0) }
            assertArrayEquals(data, back)
        }
    }

    @Test
    fun gravacaoQueCruzaOFimDoArquivo() {
        val root = tree()
        val f = File(root, "DVD/pequeno.iso") // 1000 bytes
        val orig = f.readBytes()
        VirtualExFat.build(root, log, freeBytes = 64L shl 20).use { dev ->
            val rd = ExFatTestReader(dev)
            val e = rd.dir(rd.dir(rd.rootCluster).first { it.name == "DVD" }.firstCluster).first { it.name == "pequeno.iso" }
            val lba = rd.clusterLba(e.firstCluster)
            val data = bytes(3 * 512, 7) // setores 0 (dentro), 1 (cruza em 1000) e 2 (depois do fim)
            dev.write(lba, 3, data, 0)
            assertEquals(2, dev.overlaySectors)
            val real = f.readBytes()
            assertEquals(1000, real.size)
            assertArrayEquals(data.copyOf(1000), real)
            assertFalse(orig.contentEquals(real))
            val back = ByteArray(3 * 512).also { dev.read(lba, 3, it, 0) }
            assertArrayEquals(data, back)
        }
    }

    @Test
    fun gravacaoForaDosArquivosFicaNaMemoria() {
        val root = tree()
        val before = walk(root).filterValues { it.isFile }.mapValues { it.value.readBytes() }
        VirtualExFat.build(root, log, freeBytes = 64L shl 20, overlayLimitBytes = 8 * 512).use { dev ->
            val last = dev.sectorCount - 10
            val data = bytes(2 * 512, 5)
            dev.write(last, 2, data, 0) // espaço livre
            dev.write(2048 + 1, 1, data, 0) // setor de boot estendido
            assertEquals(3, dev.overlaySectors)
            val back = ByteArray(2 * 512).also { dev.read(last, 2, it, 0) }
            assertArrayEquals(data, back)
            // Zeros num lugar que já é zero não ocupam a camada (o PS2 zera clusters novos).
            dev.write(last - 100, 1, ByteArray(512), 0)
            dev.write(last, 1, ByteArray(512), 0) // volta ao original: sai da camada
            assertEquals(2, dev.overlaySectors)
            // Limite: 8 setores no total.
            val ex = assertThrows<IOException> { dev.write(last - 50, 7, ByteArray(7 * 512) { 1 }, 0) }
            assertEquals("camada de escrita cheia", ex.message)
            assertEquals(2, dev.overlaySectors, "nada gravado quando estoura")
            dev.write(last - 50, 6, ByteArray(6 * 512) { 1 }, 0)
            assertEquals(8, dev.overlaySectors)
        }
        for ((r, b) in before) assertArrayEquals(b, File(root, r).readBytes(), r)
    }

    @Test
    fun psApagaOuTruncaArquivoDesligaGravacaoDireta() {
        val root = tree()
        val cfg = File(root, "CFG/conf_opl.cfg")
        val orig = cfg.readBytes()
        VirtualExFat.build(root, log, freeBytes = 64L shl 20).use { dev ->
            val rd = ExFatTestReader(dev)
            val dirCl = rd.dir(rd.rootCluster).first { it.name == "CFG" }.firstCluster
            val e = rd.dir(dirCl).first { it.name == "conf_opl.cfg" }
            // O PS2 (f_open com FA_CREATE_ALWAYS) zera tamanho e primeiro cluster na entrada.
            val dirLba = rd.clusterLba(dirCl) + e.offset / 512
            val sec = rd.sector(dirLba)
            val o = e.offset % 512 + 32
            for (k in 8 until 32) if (k < 16 || k >= 20) sec[o + k] = 0
            dev.write(dirLba, 1, sec, 0)
            // Agora o cluster antigo pode virar de outro arquivo: nada vai para o arquivo real.
            dev.write(rd.clusterLba(e.firstCluster), 1, ByteArray(512) { 0x41 }, 0)
            assertArrayEquals(orig, cfg.readBytes())
            assertEquals(2, dev.overlaySectors)
            assertTrue(logs.any { it.contains("conf_opl.cfg") })
        }
    }

    @Test
    fun arquivoSomenteLeituraCaiNaMemoria() {
        val root = tree()
        val cfg = File(root, "CFG/conf_opl.cfg")
        val orig = cfg.readBytes()
        assumeTrue(cfg.setReadOnly() && !cfg.canWrite())
        try {
            VirtualExFat.build(root, log, freeBytes = 64L shl 20).use { dev ->
                val rd = ExFatTestReader(dev)
                val e = rd.dir(rd.dir(rd.rootCluster).first { it.name == "CFG" }.firstCluster).first { it.name == "conf_opl.cfg" }
                val lba = rd.clusterLba(e.firstCluster)
                dev.write(lba, 1, ByteArray(512) { 0x42 }, 0)
                dev.write(lba, 1, ByteArray(512) { 0x43 }, 0)
                assertEquals(1, dev.overlaySectors)
                assertEquals(0x43, rd.sector(lba)[0].toInt())
                assertEquals(1, logs.count { it.contains("não consegui gravar") }, "avisa uma vez")
            }
            assertArrayEquals(orig, cfg.readBytes())
        } finally {
            cfg.setWritable(true)
        }
    }

    @Test
    fun closeFechaOsArquivos() {
        val root = tree()
        val dev = VirtualExFat.build(root, log, freeBytes = 64L shl 20)
        val rd = ExFatTestReader(dev)
        val dvd = rd.dir(rd.dir(rd.rootCluster).first { it.name == "DVD" }.firstCluster)
        for (e in dvd) readAll(dev, rd, e)
        val vmc = rd.dir(rd.dir(rd.rootCluster).first { it.name == "VMC" }.firstCluster).first()
        dev.write(rd.clusterLba(vmc.firstCluster), 1, ByteArray(512), 0)
        dev.close()
        // No Windows um arquivo aberto não pode ser apagado.
        assertTrue(File(root, "DVD/pequeno.iso").delete())
        assertTrue(File(root, "VMC/slot0.bin").delete())
        assertThrows<IOException> { dev.read(0, 1, ByteArray(512), 0) }
    }

    @Test
    fun leiturasConcorrentes() {
        val root = tree()
        VirtualExFat.build(root, log, freeBytes = 64L shl 20).use { dev ->
            val total = minOf(dev.sectorCount, 2048L + 200_000)
            val rnd = Random(1)
            val reqs = List(400) { (rnd.nextLong() and Long.MAX_VALUE) % (total - 64) to 1 + rnd.nextInt(64) }
            val ref = reqs.map { (lba, n) -> ByteArray(n * 512).also { dev.read(lba, n, it, 0) } }
            val pool = Executors.newFixedThreadPool(8)
            val errors = Collections.synchronizedList(ArrayList<String>())
            repeat(8) { t ->
                pool.execute {
                    for (i in reqs.indices.shuffled(Random(t.toLong()))) {
                        val (lba, n) = reqs[i]
                        val b = ByteArray(n * 512)
                        dev.read(lba, n, b, 0)
                        if (!b.contentEquals(ref[i])) errors.add("$lba+$n")
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), errors)
        }
    }

    @Test
    fun foraDoDisco() {
        VirtualExFat.build(tree(), log, freeBytes = 0).use { dev ->
            assertThrows<IOException> { dev.read(dev.sectorCount - 1, 2, ByteArray(1024), 0) }
            assertThrows<IOException> { dev.write(-1, 1, ByteArray(512), 0) }
            dev.read(dev.sectorCount - 1, 1, ByteArray(512), 0)
        }
    }

    @Test
    fun describeEmPortugues() {
        val root = File(tmp, "um").apply { mkdirs() }
        File(root, "a.iso").writeBytes(ByteArray(10))
        File(root, "DVD").mkdirs()
        VirtualExFat.build(root, log).use { assertEquals("1 arquivo, 1 pasta, 0,0 GiB", it.describe()) }
        VirtualExFat.build(tree(), log).use { assertEquals("46 arquivos, 10 pastas, 0,0 GiB", it.describe()) }
        assertEquals("1.234", VirtualExFat.count(1234))
        assertEquals("412,3", VirtualExFat.gib(4427128883L * 100))
        assertTrue(logs.any { it.startsWith("exFAT virtual: 1 arquivo, 1 pasta") })
    }

    @Test
    fun limiteDeDoisTebibytes() {
        // Cabe com o espaço livre pedido.
        assertEquals(32768L, Layout.fitFree(1000, 32768))
        // Conteúdo cabe, mas o livre é reduzido para o disco ficar com no máximo 2^32 - 1 setores.
        val data = 16_000_000L
        val free = Layout.fitFree(data, 1_000_000)
        assertTrue(free in 1 until 1_000_000)
        assertTrue(Layout.sizes(data, free).diskSectors <= 0xFFFFFFFFL)
        assertTrue(Layout.sizes(data, free + 1).diskSectors > 0xFFFFFFFFL)
        // Nem o conteúdo cabe.
        assertEquals(-1L, Layout.fitFree(17_000_000L, 0))
    }

    @Test
    fun mesmaPastaMesmoDisco() {
        val root = tree()
        fun meta(dev: VirtualExFat): ByteArray {
            val rd = ExFatTestReader(dev)
            val n = (rd.heapOffset + 2048 + 6L * 256).toInt()
            return ByteArray(n * 512).also { dev.read(0, n, it, 0) }
        }
        val a = VirtualExFat.build(root, log).use { meta(it) }
        val b = VirtualExFat.build(root, log).use { meta(it) }
        assertArrayEquals(a, b)
    }

    @Test
    fun vinteMilArquivos() {
        val root = File(tmp, "muitos").apply { mkdirs() }
        for (d in 0 until 100) {
            val dir = File(root, "pasta %03d com nome comprido".format(d)).apply { mkdirs() }
            for (i in 0 until 200) File(dir, "SLUS_%05d.45_COV arquivo de capa.png".format(d * 200 + i)).createNewFile()
        }
        val t0 = System.nanoTime()
        VirtualExFat.build(root, log).use { dev ->
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("exFAT virtual com 20.000 arquivos montado em $ms ms")
            assertEquals("20.000 arquivos, 100 pastas, 0,0 GiB", dev.describe())
            assertTrue(ms < 5000, "demorou $ms ms")
            val rd = ExFatTestReader(dev)
            val d = rd.dir(rd.rootCluster)
            assertEquals(100, d.size)
            assertEquals(200, rd.dir(d[57].firstCluster).size)
        }
    }
}
