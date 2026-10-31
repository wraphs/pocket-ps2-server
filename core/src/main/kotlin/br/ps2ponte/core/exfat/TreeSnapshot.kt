package br.ps2ponte.core.exfat

import br.ps2ponte.core.t
import br.ps2ponte.core.EventLog
import br.ps2ponte.core.fs.VFile
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Um arquivo ou pasta da fotografia da árvore. */
internal class Node(
    val name: String,
    val file: VFile,
    val isDir: Boolean,
    val size: Long,
    /** Timestamp exFAT já convertido ([ExFat.timestamp]). */
    val time: Long,
) {
    var children: Array<Node> = NO_CHILDREN
    var firstCluster = 0
    var clusters = 0

    // Pastas: bytes das entradas geradas (o resto do cluster é zero).
    var dirUsed = 0
    var dirData: ByteArray = EMPTY

    // Arquivos: posição do conjunto de entradas dentro da pasta-mãe (para detectar mudanças do PS2).
    var parent: Node? = null
    var entryOffset = 0

    /** O PS2 apagou, truncou ou mudou este arquivo de lugar: gravações não vão mais para o arquivo real. */
    @Volatile var detached = false

    /** Gravação direta falhou (mídia só leitura etc.): usa a camada em memória. */
    @Volatile var noWriteThrough = false

    companion object {
        val NO_CHILDREN = emptyArray<Node>()
        val EMPTY = ByteArray(0)
    }
}

/**
 * Percorre a árvore uma vez (fotografia) só com [VFile.listFiles] e os atributos dos itens listados
 * (no SAF a listagem de uma pasta é uma consulta só, que já traz tipo, tamanho e data).
 * Vai por níveis: as pastas de um nível são listadas em paralelo e os atributos de todos os itens do
 * nível são lidos em paralelo (no [br.ps2ponte.core.fs.LocalFile] cada atributo é um stat).
 * Ordem determinística (nome sem diferenciar maiúsculas); ignora o que não dá para ler, nomes
 * inválidos para exFAT e nomes repetidos sem diferenciar maiúsculas. Sem informação de links no
 * VFile, laços de links simbólicos são contidos pelos limites de profundidade e de itens.
 */
internal class TreeSnapshot(rootDir: VFile, private val log: EventLog, private val tz: TimeZone) {
    val root: Node
    var files = 0; private set
    var dirs = 0; private set
    var totalBytes = 0L; private set
    var skipped = 0; private set
    private var logged = 0
    private var entries = 0

    /** Um item listado; [stat] lê os atributos (roda em outra thread). */
    private class Item(val file: VFile) {
        var name = ""
        var isDir = false
        var size = 0L
        var mtime = 0L
        var problem: String? = null

        fun stat() {
            try {
                name = file.name
                problem = badName(name)
                if (problem != null) return
                isDir = file.isDirectory
                if (!isDir) {
                    size = file.length()
                    // isFile só quando precisa (no LocalFile cada atributo é um stat): vazio, sumiu ou especial.
                    if (size <= 0 && !file.isFile) { problem = "não é arquivo comum"; return }
                }
                mtime = file.lastModified()
            } catch (e: Exception) {
                problem = "sem acesso"
            }
        }
    }

    init {
        val isDir = try { rootDir.isDirectory } catch (e: Exception) { false }
        if (!isDir) throw IOException(t("não é uma pasta: ${rootDir.path}", "not a folder: ${rootDir.path}"))
        root = Node("", rootDir, true, 0, ExFat.timestamp(rootDir.lastModified(), tz))
        // Trabalho de E/S (stat, consulta IPC do SAF): mais threads que núcleos ajuda.
        val threads = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(2, THREADS_MAX)
        val pool: ExecutorService? = if (threads < 2) null else
            Executors.newFixedThreadPool(threads) { r -> Thread(r, "exfat-arvore").apply { isDaemon = true } }
        try {
            var level = listOf(root)
            var depth = 0
            while (level.isNotEmpty()) {
                // 1) Lista as pastas do nível.
                val listings = parallelMap(pool, level) { d ->
                    try { d.file.listFiles() } catch (e: Exception) { null }
                }
                if (depth == 0 && listings[0] == null) throw IOException(t("não consegui ler a pasta ${rootDir.path}", "could not read folder ${rootDir.path}"))
                // 2) Atributos de todos os itens do nível.
                val items = ArrayList<Item>()
                val start = IntArray(level.size + 1)
                for (i in level.indices) {
                    start[i] = items.size
                    listings[i]?.forEach { items.add(Item(it)) }
                }
                start[level.size] = items.size
                parallelChunks(pool, threads, items)
                // 3) Monta os nós (sequencial).
                val next = ArrayList<Node>()
                for (i in level.indices) {
                    val d = level[i]
                    if (listings[i] == null) {
                        skip(d.file.path, "não consegui listar")
                        continue
                    }
                    addChildren(d, items.subList(start[i], start[i + 1]), depth, next)
                }
                level = next
                depth++
            }
        } finally {
            pool?.shutdownNow()
        }
        if (skipped > logged) log.log(t("exFAT virtual: ${skipped - logged} outros itens ignorados", "Virtual exFAT: ${skipped - logged} more items skipped"))
    }

    private fun addChildren(node: Node, items: List<Item>, depth: Int, next: MutableList<Node>) {
        val list = ArrayList<Pair<String, Node>>(items.size)
        for (it in items) {
            val p = it.problem
            if (p != null) { skip(it.file.path, p); continue }
            if (it.isDir && depth + 1 > MAX_DEPTH) { skip(it.file.path, t("profundidade", "too deep")); continue }
            if (++entries > MAX_ENTRIES) { skip(it.file.path, t("itens demais", "too many items")); continue }
            val n = Node(it.name, it.file, it.isDir, it.size, ExFat.timestamp(it.mtime, tz))
            list.add(ExFat.upperName(it.name) to n)
        }
        list.sortWith { x, y ->
            val c = x.first.compareTo(y.first)
            if (c != 0) c else x.second.name.compareTo(y.second.name)
        }
        val kids = ArrayList<Node>(list.size)
        var prev: String? = null
        for ((key, n) in list) {
            if (key == prev) { skip(n.file.path, t("nome repetido sem diferenciar maiúsculas", "same name ignoring case")); continue }
            prev = key
            n.parent = node
            kids.add(n)
            if (n.isDir) { dirs++; next.add(n) } else { files++; totalBytes += n.size }
        }
        node.children = kids.toTypedArray()
    }

    private fun skip(path: String, why: String) {
        skipped++
        if (logged < 20) {
            logged++
            log.log(t("exFAT virtual: ignorado ($why): $path", "Virtual exFAT: skipped ($why): $path"))
        }
    }

    private fun <T, R> parallelMap(pool: ExecutorService?, list: List<T>, f: (T) -> R): List<R> {
        if (pool == null || list.size < 2) return list.map(f)
        return pool.invokeAll(list.map { Callable { f(it) } }).map { it.get() }
    }

    private fun parallelChunks(pool: ExecutorService?, threads: Int, items: List<Item>) {
        if (pool == null || items.size < PARALLEL_MIN) {
            for (it in items) it.stat()
            return
        }
        val chunk = (items.size + threads - 1) / threads
        pool.invokeAll(items.chunked(chunk).map { part -> Callable { for (it in part) it.stat() } }).forEach { it.get() }
    }

    companion object {
        /** Profundidade máxima de pastas (o PS2 não usa caminhos fundos; também segura laços de links). */
        const val MAX_DEPTH = 32

        /** Máximo de itens na árvore (segura laços de links; ~45 MB de memória no pior caso). */
        const val MAX_ENTRIES = 300_000
        private const val PARALLEL_MIN = 64
        private const val THREADS_MAX = 16

        /** Motivo para recusar o nome, ou null se o exFAT/FatFs aceita. */
        fun badName(name: String): String? {
            if (name.isEmpty() || name == "." || name == "..") return "nome inválido"
            if (name.length > ExFat.MAX_NAME) return "nome com mais de 255 caracteres"
            for (c in name) {
                if (c < ' ' || c == '"' || c == '*' || c == '/' || c == ':' || c == '<' || c == '>' ||
                    c == '?' || c == '\\' || c == '|' || c == '\u007F' || c == '�'
                ) return "caractere inválido no nome"
            }
            // O FatFs corta pontos e espaços do fim do nome pedido; um nome assim não abriria.
            val last = name[name.length - 1]
            if (last == '.' || last == ' ') return "nome termina com ponto ou espaço"
            return null
        }
    }
}
