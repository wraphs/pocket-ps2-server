package br.ps2ponte.core.exfat

import br.ps2ponte.core.EventLog
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Um arquivo ou pasta da fotografia da árvore. */
internal class Node(
    val name: String,
    val file: File,
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
 * Percorre a pasta uma vez (fotografia). Ordem determinística (nome sem diferenciar maiúsculas),
 * ignora o que não dá para ler, nomes inválidos para exFAT, nomes repetidos sem diferenciar
 * maiúsculas e laços de links simbólicos.
 */
internal class TreeSnapshot(rootDir: File, private val log: EventLog, private val tz: java.util.TimeZone) {
    val root: Node
    var files = 0; private set
    var dirs = 0; private set
    var totalBytes = 0L; private set
    var skipped = 0; private set
    private var logged = 0

    /** Lê os atributos em paralelo nas pastas grandes (cada stat no armazenamento do Android passa pelo FUSE). */
    private val pool: ExecutorService? =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8).let { n ->
            if (n < 2) null else Executors.newFixedThreadPool(n) { r -> Thread(r, "exfat-stat").apply { isDaemon = true } }
        }
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    init {
        try {
            val rootPath = rootDir.toPath()
            val attrs = try {
                Files.readAttributes(rootPath, BasicFileAttributes::class.java)
            } catch (e: IOException) {
                throw IOException("não consegui ler a pasta ${rootDir.path}: ${e.message}", e)
            }
            if (!attrs.isDirectory) throw IOException("não é uma pasta: ${rootDir.path}")
            root = Node("", rootDir, true, 0, ExFat.timestamp(attrs.lastModifiedTime().toMillis(), tz))
            val real = try { rootPath.toRealPath().toString() } catch (e: IOException) { rootDir.absolutePath }
            scan(rootPath, root, real, ArrayList(), 0)
        } finally {
            pool?.shutdownNow()
        }
        if (skipped > logged) log.log("exFAT virtual: ${skipped - logged} outros itens ignorados")
    }

    private fun skip(path: Path, why: String) {
        skipped++
        if (logged < 20) {
            logged++
            log.log("exFAT virtual: ignorado ($why): $path")
        }
    }

    /** Um item listado; [stat] preenche os atributos (pode rodar em outra thread). */
    private class Item(val path: Path, val name: String) {
        var attrs: BasicFileAttributes? = null
        var real: String? = null // destino de link simbólico para pasta
        var problem: String? = null

        fun stat() {
            problem = badName(name)
            if (problem != null) return
            var a = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: Exception) { problem = "sem acesso"; return }
            if (a.isSymbolicLink) {
                a = try { Files.readAttributes(path, BasicFileAttributes::class.java) } catch (e: Exception) {
                    problem = "link quebrado"; return
                }
                if (a.isDirectory) {
                    real = try { path.toRealPath().toString() } catch (e: Exception) { problem = "link quebrado"; return }
                }
            }
            attrs = a
        }
    }

    private fun statAll(items: List<Item>) {
        val p = pool
        if (p == null || items.size < PARALLEL_MIN) {
            for (it in items) it.stat()
            return
        }
        val chunk = (items.size + threads - 1) / threads
        val tasks = items.chunked(chunk).map { part -> Callable { for (it in part) it.stat() } }
        for (f in p.invokeAll(tasks)) {
            try { f.get() } catch (e: ExecutionException) { /* cada item já trata os próprios erros */ }
        }
    }

    private fun scan(dir: Path, node: Node, real: String, ancestors: ArrayList<String>, depth: Int) {
        ancestors.add(real)
        val list = ArrayList<Pair<String, Node>>()
        val subdirs = ArrayList<Triple<Path, Node, String>>()
        val items = ArrayList<Item>()
        try {
            val ds: DirectoryStream<Path> = Files.newDirectoryStream(dir)
            ds.use { for (p in it) items.add(Item(p, p.fileName?.toString() ?: continue)) }
        } catch (e: Exception) {
            skip(dir, "não consegui listar")
        }
        statAll(items)
        val sep = File.separator
        for (it in items) {
            val p = it.path
            val name = it.name
            val a = it.attrs
            if (a == null) { skip(p, it.problem ?: "sem acesso"); continue }
            val childReal = it.real
            if (childReal != null && ancestors.any { anc -> childReal == anc || childReal.startsWith(if (anc.endsWith(sep)) anc else anc + sep) }) {
                skip(p, "laço de link"); continue
            }
            val time = ExFat.timestamp(a.lastModifiedTime().toMillis(), tz)
            if (a.isDirectory) {
                if (depth >= MAX_DEPTH) { skip(p, "profundidade"); continue }
                val n = Node(name, p.toFile(), true, 0, time)
                list.add(ExFat.upperName(name) to n)
                subdirs.add(Triple(p, n, childReal ?: (real + sep + name)))
            } else if (a.isRegularFile) {
                val n = Node(name, p.toFile(), false, a.size(), time)
                list.add(ExFat.upperName(name) to n)
            } else {
                skip(p, "não é arquivo comum")
            }
        }
        list.sortWith { x, y ->
            val c = x.first.compareTo(y.first)
            if (c != 0) c else x.second.name.compareTo(y.second.name)
        }
        val kids = ArrayList<Node>(list.size)
        var prev: String? = null
        for ((key, n) in list) {
            if (key == prev) { skip(n.file.toPath(), "nome repetido sem diferenciar maiúsculas"); continue }
            prev = key
            n.parent = node
            kids.add(n)
        }
        node.children = kids.toTypedArray()
        val kept = kids.toHashSet()
        for (n in kids) {
            if (n.isDir) dirs++ else { files++; totalBytes += n.size }
        }
        for ((p, n, r) in subdirs) {
            if (n in kept) scan(p, n, r, ancestors, depth + 1)
        }
        ancestors.removeAt(ancestors.size - 1)
    }

    companion object {
        const val MAX_DEPTH = 64
        private const val PARALLEL_MIN = 64

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
