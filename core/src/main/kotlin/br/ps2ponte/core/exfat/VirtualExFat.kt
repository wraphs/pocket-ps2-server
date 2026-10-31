package br.ps2ponte.core.exfat

import br.ps2ponte.core.t
import br.ps2ponte.core.EventLog
import br.ps2ponte.core.NoLog
import br.ps2ponte.core.block.BlockDevice
import br.ps2ponte.core.exfat.ExFat.CLUSTER_BYTES
import br.ps2ponte.core.exfat.ExFat.ENTRY
import br.ps2ponte.core.exfat.ExFat.SECTOR
import br.ps2ponte.core.exfat.ExFat.SECTORS_PER_CLUSTER
import br.ps2ponte.core.fs.LocalFile
import br.ps2ponte.core.fs.VFile
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Disco exFAT virtual gerado na hora a partir de uma pasta (fotografia tirada em [build]).
 *
 * Disco: MBR com uma partição 0x07 no LBA [PART_START]; volume exFAT com clusters de 128 KiB:
 * bitmap, tabela up-case, raiz, todas as subpastas e depois todos os arquivos, cada um contíguo,
 * e no fim o espaço livre virtual. O FAT tem cadeias completas (NoFatChain = 0).
 *
 * Leituras: metadados gerados sob demanda; dados lidos do arquivo real.
 * Gravações: dentro do tamanho de um arquivo existente vão para o arquivo real; o resto fica numa
 * camada em memória (perdida ao fechar).
 */
class VirtualExFat private constructor(
    private val snap: TreeSnapshot,
    private val lay: Layout,
    private val log: EventLog,
    private val overlayLimitBytes: Long,
    serial: Int,
) : BlockDevice {

    override val sectorSize = SECTOR
    override val sectorCount: Long = lay.diskSectors
    override val readOnly = false

    // Objetos do heap em ordem de cluster: bitmap, up-case, raiz, pastas, arquivos (não vazios).
    private val objFirst: IntArray
    private val objLast: IntArray
    private val objKind: ByteArray
    private val objNode: Array<Node?>
    private val allocEnd: Int // primeiro cluster livre (todos antes dele estão ocupados)

    private val mbr = ByteArray(SECTOR)
    private val boot = ByteArray(12 * SECTOR)

    private val overlay = ConcurrentHashMap<Long, ByteArray>()
    private val writeLock = Any()
    private val handles = FileHandles(16)
    @Volatile private var closed = false

    init {
        val objs = ArrayList<Pair<Int, Node?>>()
        objs.add(K_BITMAP to null)
        objs.add(K_UPCASE to null)
        objs.add(K_DIR to snap.root)
        for (d in lay.dirs) objs.add(K_DIR to d)
        for (f in lay.files) if (f.clusters > 0) objs.add(K_FILE to f)
        val n = objs.size
        objFirst = IntArray(n)
        objLast = IntArray(n)
        objKind = ByteArray(n)
        objNode = arrayOfNulls(n)
        for (i in 0 until n) {
            val (k, node) = objs[i]
            objKind[i] = k.toByte()
            objNode[i] = node
            when (k) {
                K_BITMAP -> { objFirst[i] = 2; objLast[i] = 2 + lay.bitmapClusters - 1 }
                K_UPCASE -> { objFirst[i] = lay.upcaseFirst; objLast[i] = lay.upcaseFirst + lay.upcaseClusters - 1 }
                else -> { objFirst[i] = node!!.firstCluster; objLast[i] = node.firstCluster + node.clusters - 1 }
            }
        }
        allocEnd = objLast[n - 1] + 1
        buildMbr(serial)
        buildBoot(serial)
    }

    companion object {
        /** Início da partição (1 MiB). */
        const val PART_START = 2048L

        /** O UDPBD informa o tamanho do disco em 32 bits. */
        const val MAX_DISK_SECTORS = 0xFFFFFFFFL

        /** Início do FAT dentro do volume (1 MiB). */
        const val FAT_OFFSET = 2048L

        private const val K_BITMAP = 0
        private const val K_UPCASE = 1
        private const val K_DIR = 2
        private const val K_FILE = 3

        /**
         * Fotografa a árvore [root] (pasta local ou SAF) e monta o disco.
         * [freeBytes]: espaço livre virtual; [overlayLimitBytes]: limite da camada de gravação em memória.
         */
        fun build(
            root: VFile,
            log: EventLog = NoLog,
            freeBytes: Long = 4L shl 30,
            overlayLimitBytes: Long = 64L shl 20,
        ): VirtualExFat {
            val t0 = System.nanoTime()
            val tz = TimeZone.getDefault()
            val snap = TreeSnapshot(root, log, tz)
            val lay = Layout.build(snap, freeBytes, log)
            val crc = CRC32().apply { update(root.path.toByteArray(Charsets.UTF_8)) }
            val serial = crc.value.toInt().let { if (it == 0) 0x50533221 else it }
            val dev = VirtualExFat(snap, lay, log, overlayLimitBytes, serial)
            val ms = (System.nanoTime() - t0) / 1_000_000
            log.log(
                t("exFAT virtual: ${dev.describe()} (montado em $ms ms; disco de ${gib(dev.sectorCount * SECTOR)} GiB, " +
                    "${gib(lay.freeClusters.toLong() * CLUSTER_BYTES)} GiB livres)", "Virtual exFAT: ${dev.describe()} (built in $ms ms; ${gib(dev.sectorCount * SECTOR)} GiB disk, " +
                    "${gib(lay.freeClusters.toLong() * CLUSTER_BYTES)} GiB free)")
            )
            return dev
        }

        /** Pasta local (java.io.File). */
        fun build(
            root: File,
            log: EventLog = NoLog,
            freeBytes: Long = 4L shl 30,
            overlayLimitBytes: Long = 64L shl 20,
        ): VirtualExFat = build(LocalFile.root(root), log, freeBytes, overlayLimitBytes)

        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        internal fun gib(bytes: Long) = String.format(PT_BR, "%.1f", bytes / (1024.0 * 1024 * 1024))
        internal fun count(n: Int) = String.format(PT_BR, "%,d", n)
    }

    /** Ex.: "1.234 arquivos, 56 pastas, 412,3 GiB". */
    fun describe(): String {
        val f = snap.files
        val d = snap.dirs
        return "${count(f)} ${if (f == 1) "arquivo" else "arquivos"}, " +
            "${count(d)} ${if (d == 1) "pasta" else "pastas"}, ${gib(snap.totalBytes)} GiB"
    }

    // ---------------------------------------------------------------- leitura

    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) {
        check(lba, count)
        var s = 0
        while (s < count) {
            val sec = lba + s
            val o = off + s * SECTOR
            if (overlay.isNotEmpty()) {
                val ov = overlay[sec]
                if (ov != null) {
                    System.arraycopy(ov, 0, buf, o, SECTOR)
                    s++
                    continue
                }
            }
            var run = count - s
            if (overlay.isNotEmpty()) {
                var k = 1
                while (k < run && !overlay.containsKey(sec + k)) k++
                run = k
            }
            s += readBase(sec, run, buf, o)
        }
    }

    /** Conteúdo original (sem a camada de gravação) de até [max] setores; devolve quantos preencheu. */
    private fun readBase(sec: Long, max: Int, buf: ByteArray, off: Int): Int {
        if (sec == 0L) {
            System.arraycopy(mbr, 0, buf, off, SECTOR)
            return 1
        }
        if (sec < PART_START) return zeros(buf, off, minOf(max.toLong(), PART_START - sec))
        val v = sec - PART_START
        if (v < 24) {
            System.arraycopy(boot, (v % 12).toInt() * SECTOR, buf, off, SECTOR)
            return 1
        }
        if (v < lay.fatOffset) return zeros(buf, off, minOf(max.toLong(), lay.fatOffset - v))
        val fatEnd = lay.fatOffset + lay.fatLength
        if (v < fatEnd) {
            val n = minOf(max.toLong(), fatEnd - v).toInt()
            for (i in 0 until n) fatSector(v - lay.fatOffset + i, buf, off + i * SECTOR)
            return n
        }
        val rel = v - lay.heapOffset
        val cl = rel / SECTORS_PER_CLUSTER + 2
        val i = findObj(cl)
        if (i < 0) return zeros(buf, off, minOf(max.toLong(), lay.volumeSectors - v))
        val objStart = (objFirst[i] - 2).toLong() * SECTORS_PER_CLUSTER
        val objEnd = (objLast[i] - 1).toLong() * SECTORS_PER_CLUSTER
        val n = minOf(max.toLong(), objEnd - rel).toInt()
        val pos = (rel - objStart) * SECTOR
        val len = n * SECTOR
        when (objKind[i].toInt()) {
            K_BITMAP -> bitmapBytes(pos, buf, off, len)
            K_UPCASE -> copyBytes(UpCase.table, pos, buf, off, len)
            K_DIR -> copyBytes(objNode[i]!!.dirData, pos, buf, off, len)
            else -> readFile(objNode[i]!!, pos, buf, off, len)
        }
        return n
    }

    private fun zeros(buf: ByteArray, off: Int, sectors: Long): Int {
        val n = sectors.toInt()
        Arrays.fill(buf, off, off + n * SECTOR, 0)
        return n
    }

    /** Índice do objeto que contém o cluster, ou -1 (cluster livre). */
    private fun findObj(cl: Long): Int {
        if (cl >= allocEnd) return -1
        val c = cl.toInt()
        var lo = 0
        var hi = objLast.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (objLast[mid] < c) lo = mid + 1 else hi = mid
        }
        return if (objFirst[lo] <= c && c <= objLast[lo]) lo else -1
    }

    private fun fatSector(index: Long, buf: ByteArray, off: Int) {
        Arrays.fill(buf, off, off + SECTOR, 0)
        val e0 = index * (SECTOR / 4)
        val end = e0 + SECTOR / 4
        if (e0 == 0L) {
            ExFat.put32(buf, off, 0xFFFFFFF8.toInt())
            ExFat.put32(buf, off + 4, -1)
        }
        var e = maxOf(e0, 2L)
        val stop = minOf(end, allocEnd.toLong())
        if (e >= stop) return
        var idx = lowerBound(objLast, e.toInt())
        while (e < stop) {
            val last = objLast[idx].toLong()
            val upto = minOf(last, stop - 1)
            var x = e
            while (x <= upto) {
                ExFat.put32(buf, off + ((x - e0) * 4).toInt(), if (x == last) -1 else (x + 1).toInt())
                x++
            }
            e = upto + 1
            if (upto == last) idx++
        }
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Bitmap: um bit por cluster; ocupados são exatamente os clusters 2 até [allocEnd] - 1. */
    private fun bitmapBytes(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val used = (allocEnd - 2).toLong()
        val full = used / 8
        val rem = (used % 8).toInt()
        for (k in 0 until len) {
            val j = pos + k
            buf[off + k] = when {
                j < full -> 0xFF.toByte()
                j == full -> ((1 shl rem) - 1).toByte()
                else -> 0
            }
        }
    }

    private fun copyBytes(src: ByteArray, pos: Long, buf: ByteArray, off: Int, len: Int) {
        val avail = if (pos >= src.size) 0 else minOf(len.toLong(), src.size - pos).toInt()
        if (avail > 0) System.arraycopy(src, pos.toInt(), buf, off, avail)
        if (avail < len) Arrays.fill(buf, off + avail, off + len, 0)
    }

    private fun readFile(n: Node, pos: Long, buf: ByteArray, off: Int, len: Int) {
        val avail = if (pos >= n.size) 0 else minOf(len.toLong(), n.size - pos).toInt()
        val got = if (avail > 0) handles.read(n, pos, buf, off, avail) else 0
        if (got < len) Arrays.fill(buf, off + got, off + len, 0)
    }

    // ---------------------------------------------------------------- gravação

    private class Target(val node: Node, val pos: Long)

    /** Se o setor está nos dados de um arquivo real que ainda aceita gravação direta. */
    private fun fileTarget(sec: Long): Target? {
        val v = sec - PART_START - lay.heapOffset
        if (v < 0) return null
        val i = findObj(v / SECTORS_PER_CLUSTER + 2)
        if (i < 0 || objKind[i].toInt() != K_FILE) return null
        val n = objNode[i]!!
        if (n.detached || n.noWriteThrough) return null
        val pos = (v - (objFirst[i] - 2).toLong() * SECTORS_PER_CLUSTER) * SECTOR
        return if (pos < n.size) Target(n, pos) else null
    }

    /** Pasta e posição (bytes) dentro dela, se o setor é de uma pasta. */
    private fun dirAt(sec: Long): Pair<Node, Long>? {
        val v = sec - PART_START - lay.heapOffset
        if (v < 0) return null
        val i = findObj(v / SECTORS_PER_CLUSTER + 2)
        if (i < 0 || objKind[i].toInt() != K_DIR) return null
        return objNode[i]!! to (v - (objFirst[i] - 2).toLong() * SECTORS_PER_CLUSTER) * SECTOR
    }

    private fun isFileSector(sec: Long): Boolean {
        val v = sec - PART_START - lay.heapOffset
        if (v < 0) return false
        val i = findObj(v / SECTORS_PER_CLUSTER + 2)
        return i >= 0 && objKind[i].toInt() == K_FILE
    }

    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) {
        check(lba, count)
        synchronized(writeLock) {
            // 1) Classifica cada setor e confere o limite da camada antes de gravar qualquer coisa.
            val targets = arrayOfNulls<Target>(count)
            val keep = BooleanArray(count) // fica na camada em memória
            var added = 0
            val base = ByteArray(SECTOR)
            for (s in 0 until count) {
                val sec = lba + s
                val o = off + s * SECTOR
                val t = fileTarget(sec)
                targets[s] = t
                keep[s] = when {
                    t != null -> t.pos + SECTOR > t.node.size // cruza o fim do arquivo
                    isFileSector(sec) -> true
                    else -> {
                        readBase(sec, 1, base, 0)
                        !equalSector(base, 0, buf, o) // igual ao original: não precisa guardar
                    }
                }
                if (keep[s] && !overlay.containsKey(sec)) added++
            }
            if ((overlay.size.toLong() + added) * SECTOR > overlayLimitBytes) throw IOException(t("camada de escrita cheia", "write overlay full"))

            // 2) Grava: trechos seguidos do mesmo arquivo numa gravação só.
            var s = 0
            while (s < count) {
                val t = targets[s]
                if (t == null) {
                    store(lba + s, buf, off + s * SECTOR, keep[s])
                    s++
                    continue
                }
                var e = s + 1
                while (e < count) {
                    val u = targets[e] ?: break
                    if (u.node !== t.node || u.pos != t.pos + (e - s).toLong() * SECTOR) break
                    e++
                }
                val len = minOf((e - s).toLong() * SECTOR, t.node.size - t.pos).toInt()
                // (um setor de pasta anterior nesta mesma gravação pode ter soltado o arquivo)
                val direct = !t.node.detached && !t.node.noWriteThrough
                if (direct && writeThrough(t.node, t.pos, buf, off + s * SECTOR, len)) {
                    for (k in s until e) store(lba + k, buf, off + k * SECTOR, keep[k])
                } else {
                    for (k in s until e) {
                        if (!overlay.containsKey(lba + k) && (overlay.size.toLong() + 1) * SECTOR > overlayLimitBytes) {
                            throw IOException(t("camada de escrita cheia", "write overlay full"))
                        }
                        store(lba + k, buf, off + k * SECTOR, true)
                    }
                }
                s = e
            }
        }
    }

    private fun store(sec: Long, buf: ByteArray, off: Int, keep: Boolean) {
        if (!keep) {
            overlay.remove(sec)
            return
        }
        overlay[sec] = buf.copyOfRange(off, off + SECTOR)
        val d = dirAt(sec)
        if (d != null) checkDetach(d.first, d.second, buf, off)
    }

    private fun writeThrough(n: Node, pos: Long, buf: ByteArray, off: Int, len: Int): Boolean {
        // Até 3 tentativas em ~0,6 s (abaixo do tempo que o PS2 espera): um pendrive que oscilou por um
        // instante não pode mandar os saves desse arquivo para a memória até o fim da sessão
        var tentativa = 0
        while (true) {
            try {
                handles.write(n, pos, buf, off, len)
                return true
            } catch (e: Exception) {
                if (e is IOException && closed) throw e
                if (++tentativa < 3) {
                    Thread.sleep(300)
                    continue
                }
                return desistirDeGravar(n, e)
            }
        }
    }

    private fun desistirDeGravar(n: Node, e: Exception): Boolean {
        n.noWriteThrough = true
        log.log(t(
            "⚠ exFAT virtual: não consegui gravar em ${n.file.path} (${e.message}); as gravações dele ficam só na memória e se perdem ao parar",
            "⚠ Virtual exFAT: could not write to ${n.file.path} (${e.message}); its writes stay in memory only and are lost on stop",
        ))
        return false
    }

    /**
     * O PS2 regravou um setor de pasta. Se mudou o tipo da entrada, o primeiro cluster ou o tamanho
     * de um arquivo (apagou, truncou, renomeou), os clusters dele podem ir para outro arquivo:
     * a partir daí as gravações nesses clusters ficam só na memória.
     */
    private fun checkDetach(dir: Node, pos: Long, data: ByteArray, off: Int) {
        val kids = dir.children
        if (kids.isEmpty()) return
        val end = pos + SECTOR
        // Primeiro filho cujo conjunto de entradas termina depois de pos.
        var lo = 0
        var hi = kids.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val k = kids[mid]
            if (k.entryOffset + ExFat.entryCount(k.name) * ENTRY <= pos) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i < kids.size && kids[i].entryOffset < end) {
            val k = kids[i++]
            if (k.isDir || k.clusters == 0 || k.detached) continue
            val e = k.entryOffset.toLong()
            if (changed(dir, e, pos, data, off) || (0 until 12).any { changed(dir, e + ENTRY + 20 + it, pos, data, off) }) {
                k.detached = true
                log.log(t("exFAT virtual: o PS2 alterou ${k.name}; as próximas gravações nele ficam só na memória", "Virtual exFAT: PS2 changed ${k.name}; further writes to it stay in memory only"))
            }
        }
    }

    private fun changed(dir: Node, at: Long, pos: Long, data: ByteArray, off: Int): Boolean {
        if (at < pos || at >= pos + SECTOR) return false
        return data[off + (at - pos).toInt()] != dir.dirData[at.toInt()]
    }

    private fun equalSector(a: ByteArray, ao: Int, b: ByteArray, bo: Int): Boolean {
        for (i in 0 until SECTOR) if (a[ao + i] != b[bo + i]) return false
        return true
    }

    private fun check(lba: Long, count: Int) {
        if (closed) throw IOException(t("disco fechado", "disk closed"))
        if (lba < 0 || count < 0 || lba + count > sectorCount) throw IOException(t("setor fora do disco: $lba+$count", "sector outside the disk: $lba+$count"))
    }

    // flush(): nada a fazer; as gravações diretas já foram entregues ao VHandle (que não tem sync).

    override fun close() {
        closed = true
        handles.close()
        overlay.clear()
    }

    /** Setores guardados na camada em memória (para testes e para a tela). */
    val overlaySectors: Int get() = overlay.size

    // ---------------------------------------------------------------- estruturas fixas

    private fun buildMbr(serial: Int) {
        ExFat.put32(mbr, 440, serial)
        val e = 446
        mbr[e] = 0x00
        chs(PART_START, mbr, e + 1)
        mbr[e + 4] = 0x07
        chs(PART_START + lay.volumeSectors - 1, mbr, e + 5)
        ExFat.put32(mbr, e + 8, PART_START.toInt())
        ExFat.put32(mbr, e + 12, lay.volumeSectors.toInt())
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
    }

    private fun chs(lba: Long, b: ByteArray, o: Int) {
        if (lba >= 1024L * 255 * 63) {
            b[o] = 0xFE.toByte(); b[o + 1] = 0xFF.toByte(); b[o + 2] = 0xFF.toByte()
            return
        }
        val c = (lba / (255 * 63)).toInt()
        val h = ((lba / 63) % 255).toInt()
        val s = (lba % 63 + 1).toInt()
        b[o] = h.toByte()
        b[o + 1] = (s or ((c shr 2) and 0xC0)).toByte()
        b[o + 2] = c.toByte()
    }

    private fun buildBoot(serial: Int) {
        val b = ByteArray(11 * SECTOR)
        b[0] = 0xEB.toByte(); b[1] = 0x76; b[2] = 0x90.toByte()
        "EXFAT   ".forEachIndexed { i, c -> b[3 + i] = c.code.toByte() }
        ExFat.put64(b, 64, PART_START)
        ExFat.put64(b, 72, lay.volumeSectors)
        ExFat.put32(b, 80, lay.fatOffset.toInt())
        ExFat.put32(b, 84, lay.fatLength.toInt())
        ExFat.put32(b, 88, lay.heapOffset.toInt())
        ExFat.put32(b, 92, lay.clusterCount)
        ExFat.put32(b, 96, snap.root.firstCluster)
        ExFat.put32(b, 100, serial)
        ExFat.put16(b, 104, 0x0100)
        b[108] = ExFat.SECTOR_SHIFT.toByte()
        b[109] = ExFat.CLUSTER_SHIFT.toByte()
        b[110] = 1
        b[111] = 0x80.toByte()
        b[112] = ((allocEnd - 2).toLong() * 100 / lay.clusterCount).toInt().toByte()
        b[120] = 0xEB.toByte(); b[121] = 0xFE.toByte() // jmp $ (não dá boot)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        for (s in 1..8) {
            b[s * SECTOR + 510] = 0x55; b[s * SECTOR + 511] = 0xAA.toByte()
        }
        System.arraycopy(b, 0, boot, 0, b.size)
        val sum = ExFat.bootChecksum(b)
        for (i in 0 until SECTOR / 4) ExFat.put32(boot, 11 * SECTOR + i * 4, sum)
    }
}

/** Posições de tudo no volume (setores relativos ao início do volume, clusters absolutos). */
internal class Layout(
    val dirs: List<Node>,
    val files: List<Node>,
    val bitmapClusters: Int,
    val upcaseFirst: Int,
    val upcaseClusters: Int,
    val clusterCount: Int,
    val freeClusters: Int,
    val fatOffset: Long,
    val fatLength: Long,
    val heapOffset: Long,
    val volumeSectors: Long,
    val diskSectors: Long,
) {
    /** Só os números (para calcular sem a árvore). */
    internal class Sizes(
        val bitmapClusters: Long, val clusterCount: Long, val fatLength: Long,
        val heapOffset: Long, val volumeSectors: Long, val diskSectors: Long,
    )

    companion object {
        /** Pasta exFAT: no máximo 256 MiB de entradas. */
        private const val MAX_DIR_BYTES = 256L shl 20

        private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b

        /** Tamanhos para [data] clusters ocupados (sem o bitmap) e [free] clusters livres. */
        internal fun sizes(data: Long, free: Long): Sizes {
            var b = 1L
            while (true) {
                val nb = ceilDiv(ceilDiv(b + data + free, 8), CLUSTER_BYTES.toLong())
                if (nb <= b) break
                b = nb
            }
            val count = b + data + free
            val spc = SECTORS_PER_CLUSTER.toLong()
            val fatLen = ceilDiv(ceilDiv((count + 2) * 4, SECTOR.toLong()), spc) * spc
            val heap = VirtualExFat.FAT_OFFSET + fatLen
            val vol = heap + count * spc
            return Sizes(b, count, fatLen, heap, vol, VirtualExFat.PART_START + vol)
        }

        /** Maior espaço livre (em clusters, até [want]) que ainda cabe no disco; -1 se nem o conteúdo cabe. */
        internal fun fitFree(data: Long, want: Long): Long {
            if (sizes(data, want).diskSectors <= VirtualExFat.MAX_DISK_SECTORS) return want
            if (sizes(data, 0).diskSectors > VirtualExFat.MAX_DISK_SECTORS) return -1
            var lo = 0L
            var hi = want
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (sizes(data, mid).diskSectors <= VirtualExFat.MAX_DISK_SECTORS) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun build(snap: TreeSnapshot, freeBytes: Long, log: EventLog): Layout {
            val dirs = ArrayList<Node>()
            val files = ArrayList<Node>()
            collect(snap.root, dirs, files)
            val cb = CLUSTER_BYTES.toLong()

            // Tamanho de cada pasta (+ 1 cluster de folga para o PS2 criar arquivos sem estender a pasta).
            var data = 0L
            for (d in listOf(snap.root) + dirs) {
                var used = if (d === snap.root) 3 * ENTRY else 0
                for (c in d.children) {
                    c.entryOffset = used
                    used += ExFat.entryCount(c.name) * ENTRY
                    if (used > MAX_DIR_BYTES - cb) throw IOException(t("pasta com itens demais para o exFAT: ${d.file.path}", "folder has too many items for exFAT: ${d.file.path}"))
                }
                d.dirUsed = used
                d.clusters = (ceilDiv(used.toLong(), cb) + 1).toInt()
                data += d.clusters
            }
            val upcaseClusters = ceilDiv(UpCase.table.size.toLong(), cb)
            data += upcaseClusters
            for (f in files) data += ceilDiv(f.size, cb)

            val wantFree = maxOf(0L, ceilDiv(freeBytes, cb))
            val free = fitFree(data, wantFree)
            if (free < 0) {
                val total = data * cb
                throw IOException(
                    "pasta grande demais para o exFAT virtual: ${VirtualExFat.gib(total)} GiB " +
                        "(o UDPBD aceita discos de até 2 TiB)"
                )
            }
            if (free < wantFree) {
                log.log(t("exFAT virtual: espaço livre virtual reduzido para ${VirtualExFat.gib(free * cb)} GiB (limite de 2 TiB do disco)", "Virtual exFAT: virtual free space reduced to ${VirtualExFat.gib(free * cb)} GiB (2 TiB disk limit)"))
            }
            val sz = sizes(data, free)

            // Clusters: bitmap, up-case, raiz, pastas, arquivos.
            var c = 2L + sz.bitmapClusters
            val upFirst = c
            c += upcaseClusters
            snap.root.firstCluster = c.toInt(); c += snap.root.clusters
            for (d in dirs) {
                d.firstCluster = c.toInt(); c += d.clusters
            }
            for (f in files) {
                val n = ceilDiv(f.size, cb)
                f.clusters = n.toInt()
                f.firstCluster = if (n > 0) c.toInt() else 0
                c += n
            }

            val lay = Layout(
                dirs, files, sz.bitmapClusters.toInt(), upFirst.toInt(), upcaseClusters.toInt(),
                sz.clusterCount.toInt(), free.toInt(), VirtualExFat.FAT_OFFSET, sz.fatLength,
                sz.heapOffset, sz.volumeSectors, sz.diskSectors,
            )
            for (d in listOf(snap.root) + dirs) lay.fillDir(d, snap.root)
            return lay
        }

        /** Pastas em pré-ordem e arquivos na mesma ordem de visita. */
        private fun collect(n: Node, dirs: MutableList<Node>, files: MutableList<Node>) {
            for (c in n.children) if (!c.isDir) files.add(c)
            for (c in n.children) if (c.isDir) {
                dirs.add(c)
                collect(c, dirs, files)
            }
        }
    }

    private fun fillDir(d: Node, root: Node) {
        val b = ByteArray(d.dirUsed)
        if (d === root) {
            // Rótulo "PS2"
            b[0] = ExFat.ET_LABEL.toByte()
            b[1] = 3
            "PS2".forEachIndexed { i, ch -> ExFat.put16(b, 2 + i * 2, ch.code) }
            // Bitmap de alocação
            b[32] = ExFat.ET_BITMAP.toByte()
            ExFat.put32(b, 32 + 20, 2)
            ExFat.put64(b, 32 + 24, (clusterCount.toLong() + 7) / 8)
            // Tabela up-case
            b[64] = ExFat.ET_UPCASE.toByte()
            ExFat.put32(b, 64 + 4, UpCase.tableChecksum)
            ExFat.put32(b, 64 + 20, upcaseFirst)
            ExFat.put64(b, 64 + 24, UpCase.table.size.toLong())
        }
        for (c in d.children) {
            val len = if (c.isDir) c.clusters.toLong() * CLUSTER_BYTES else c.size
            ExFat.writeEntrySet(b, c.entryOffset, c.name, c.isDir, c.firstCluster, len, c.time)
        }
        d.dirData = b
    }
}
