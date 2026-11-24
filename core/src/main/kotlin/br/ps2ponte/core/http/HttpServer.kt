package br.ps2ponte.core.http

import br.ps2ponte.core.EventLog
import br.ps2ponte.core.NoLog
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.ferramentas.IdJogo
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import br.ps2ponte.core.t
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Servidor HTTP para o OPL com rede HTTP (RiptOPL e o OPL-HTTP do Docmine17): lista em games.csv e jogos
 * lidos por "Range" (TCP: não perde pacote em hub barato como o UDP).
 *
 * Contrato do console (documentado pelo PS2-Servers, docs/HTTP.md):
 * - GET .../games.csv: 200 com Content-Length (sem chunked).
 * - Jogo: Range "bytes=a-b" respondido com 206 e corpo de exatamente b-a+1 bytes; cabeçalho < 512 bytes;
 *   fora do arquivo = 416 (o console tenta de novo, em vez de travar esperando bytes).
 *
 * [riptopl]: lista "path-aware" do RiptOPL ("ID","Título",MÍDIA,"DVD/arquivo.iso"), com o jogo pedido pelo
 * caminho; senão, a lista simples do OPL-HTTP (ID,arquivo.iso,MÍDIA) com o jogo pedido só pelo nome.
 * O cliente não se identifica, por isso a escolha é uma opção.
 */
class HttpServer(
    private val raiz: VFile,
    val port: Int = 1100,
    val riptopl: Boolean = true,
    private val log: EventLog = NoLog,
    private val stats: TransferStats = TransferStats(),
) : PonteServer {
    override val name = "HTTP"

    @Volatile private var listener: ServerSocket? = null
    private val closed = AtomicBoolean(false)
    private val clientes = ConcurrentHashMap<Socket, Thread>()
    @Volatile private var indice: List<Item> = emptyList()
    private val ids = ConcurrentHashMap<String, Pair<Long, String?>>()
    @Volatile private var avisouLista = false

    val localPort: Int get() = listener?.localPort ?: port

    class Item(val arquivo: VFile, val relativo: String, val midia: String, val id: String, val titulo: String)

    override fun start() {
        check(listener == null) { "HTTP já iniciado" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 16)
        } catch (e: IOException) {
            ss.close()
            throw e
        }
        listener = ss
        thread(isDaemon = true, name = "http-accept") {
            while (!closed.get()) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                s.tcpNoDelay = true
                s.soTimeout = 120_000
                s.sendBufferSize = 256 * 1024
                val th = thread(isDaemon = true, name = "http-${s.inetAddress.hostAddress}", start = false) { atender(s) }
                th.priority = Thread.MAX_PRIORITY
                clientes[s] = th
                th.start()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener?.close() } catch (_: IOException) {}
        for (s in clientes.keys) try { s.close() } catch (_: IOException) {}
        clientes.clear()
    }

    // ------------------------------------------------------------------ índice

    /** Jogos da raiz, de DVD/ e de CD/ (um nível; o cliente simples só sabe pedir pelo nome). */
    fun montarIndice(): List<Item> {
        val itens = mutableListOf<Item>()
        val pastas = raiz.listFiles().orEmpty()
        fun add(f: VFile, pasta: String?) {
            if (!f.isFile) return
            val n = f.name
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext != "iso" && !(ext == "zso" && !riptopl)) return
            val midia = when {
                pasta.equals("CD", true) -> "CD"
                pasta.equals("DVD", true) -> "DVD"
                else -> if (f.length() <= 700L * 1024 * 1024) "CD" else "DVD"
            }
            val m = NOME_OPL.find(n)
            val id = m?.groupValues?.get(1)?.uppercase() ?: lerId(f) ?: n.substringBeforeLast('.').take(11)
            val titulo = (if (m != null) n.substring(m.range.last + 1) else n).substringBeforeLast('.')
            itens += Item(f, if (pasta == null) n else "$pasta/$n", midia, id, titulo)
        }
        for (f in pastas) {
            if (f.isDirectory && (f.name.equals("DVD", true) || f.name.equals("CD", true))) f.listFiles()?.forEach { add(it, f.name) }
            else add(f, null)
        }
        return itens.sortedBy { it.titulo.lowercase() }.also { indice = it }
    }

    private fun lerId(f: VFile): String? {
        val marca = f.length() xor f.lastModified()
        ids[f.path]?.let { if (it.first == marca) return it.second }
        val id = try { f.open(false).use { IdJogo.ler(it) } } catch (e: IOException) { null }
        ids[f.path] = marca to id
        return id
    }

    fun gamesCsv(): ByteArray {
        val itens = montarIndice()
        val sb = StringBuilder()
        val limite = if (riptopl) 262_144 else 8192
        var fora = 0
        var linhas = 0
        for (it in itens) {
            val linha = if (riptopl) {
                "${q(it.id)},${q(it.titulo)},${it.midia},${q(it.relativo)}\n"
            } else {
                val nome = it.arquivo.name
                // Sem escape no cliente simples: nomes com vírgula/aspas quebrariam a linha
                if (nome.any { c -> c == ',' || c == '"' || c == '\n' || c == '\r' } || nome.length >= 160) { fora++; continue }
                "${it.id.take(12)},$nome,${it.midia}\n"
            }
            if (sb.length + linha.toByteArray().size > limite || (riptopl && linhas >= 2048)) { fora++; continue }
            sb.append(linha)
            linhas++
        }
        if (fora > 0 && !avisouLista) {
            avisouLista = true
            log.log(t("HTTP: $fora jogo(s) ficaram fora da lista (limite do console ou nome com vírgula)",
                "HTTP: $fora game(s) left out of the list (console limit or name with a comma)"))
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    /** Acha o jogo pedido: caminho relativo (ignorando pastas da frente, como "/PS2/DVD/x.iso") ou só o nome. */
    fun procurar(caminho: String): Item? {
        val itens = indice.ifEmpty { montarIndice() }
        var p = caminho.trimStart('/')
        while (true) {
            itens.firstOrNull { it.relativo.equals(p, true) }?.let { return it }
            val barra = p.indexOf('/')
            if (barra < 0) break
            p = p.substring(barra + 1)
        }
        return itens.firstOrNull { it.arquivo.name.equals(p, true) }
    }

    // ------------------------------------------------------------------ conexão

    private fun atender(s: Socket) {
        val ip = s.inetAddress.hostAddress
        var aberto: Pair<Item, VHandle>? = null
        try {
            val ent = BufferedInputStream(s.getInputStream(), 4096)
            val sai = s.getOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!closed.get()) {
                val linhas = lerCabecalho(ent) ?: break
                stats.request(ip)
                val partes = linhas[0].split(' ')
                if (partes.size < 2) { responder(sai, 400, "Bad Request"); break }
                val metodo = partes[0]
                val alvo = try { URLDecoder.decode(partes[1].substringBefore('?').replace("+", "%2B"), "UTF-8") } catch (e: Exception) { partes[1] }
                val cab = linhas.drop(1).associate { l -> l.substringBefore(':').trim().lowercase() to l.substringAfter(':', "").trim() }
                if (metodo != "GET" && metodo != "HEAD") { responder(sai, 405, "Method Not Allowed"); continue }

                if (alvo.endsWith("/games.csv", true) || alvo.equals("games.csv", true)) {
                    val corpo = gamesCsv()
                    sai.write(("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nContent-Length: ${corpo.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray())
                    if (metodo == "GET") sai.write(corpo)
                    sai.flush()
                    if (!avisouLista) log.log(t("HTTP: $ip leu a lista de jogos (${indice.size})", "HTTP: $ip read the game list (${indice.size})"))
                    continue
                }

                val item = procurar(alvo)
                if (item == null) {
                    log.log(t("HTTP: $ip pediu um arquivo que não está na lista: $alvo", "HTTP: $ip asked for a file not in the list: $alvo"))
                    responder(sai, 404, "Not Found")
                    continue
                }
                var h = aberto?.takeIf { it.first === item }?.second
                if (h == null) {
                    aberto?.second?.let { try { it.close() } catch (_: IOException) {} }
                    aberto = null
                    h = try { item.arquivo.open(false) } catch (e: IOException) {
                        log.log(t("HTTP: erro ao abrir ${item.relativo}: ${e.message}", "HTTP: error opening ${item.relativo}: ${e.message}"))
                        null
                    }
                    if (h == null) {
                        responder(sai, 500, "Internal Server Error")
                        continue
                    }
                    log.log(t("HTTP: $ip abriu ${item.relativo}", "HTTP: $ip opened ${item.relativo}"))
                    aberto = item to h
                }
                val tam = h.length()
                val range = cab["range"]
                val (ini, fim, parcial) = if (range != null && range.startsWith("bytes=")) {
                    val a = range.removePrefix("bytes=").substringBefore(',')
                    val i = a.substringBefore('-').trim()
                    val f = a.substringAfter('-', "").trim()
                    when {
                        i.isEmpty() && f.isNotEmpty() -> Triple(maxOf(0L, tam - (f.toLongOrNull() ?: tam + 1)), tam - 1, true) // sufixo
                        else -> Triple(i.toLongOrNull() ?: -1L, if (f.isEmpty()) tam - 1 else f.toLongOrNull() ?: -1L, true)
                    }
                } else Triple(0L, tam - 1, false)
                if (ini < 0 || fim < ini || fim >= tam) {
                    sai.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$tam\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                    sai.flush()
                    continue
                }
                val n = fim - ini + 1
                val status = if (parcial) "206 Partial Content" else "200 OK"
                val extra = if (parcial) "Content-Range: bytes $ini-$fim/$tam\r\n" else ""
                sai.write("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nContent-Length: $n\r\n${extra}Accept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                if (metodo == "GET") {
                    var pos = ini
                    while (pos <= fim) {
                        val q = minOf(buf.size.toLong(), fim - pos + 1).toInt()
                        val r = lerComRetentativa(h, pos, buf, q)
                        if (r <= 0) throw IOException(t("leitura curta em ${item.relativo}", "short read on ${item.relativo}"))
                        sai.write(buf, 0, r)
                        pos += r
                        stats.bytesRead.addAndGet(r.toLong())
                    }
                }
                sai.flush()
            }
        } catch (e: IOException) {
            // Conexão fechada pelo PS2 (normal ao trocar de jogo) ou erro de leitura
            if (e.message?.startsWith("leitura curta") == true || e.message?.startsWith("short read") == true) log.log("HTTP: ${e.message}")
        } finally {
            aberto?.second?.let { try { it.close() } catch (_: IOException) {} }
            clientes.remove(s)
            try { s.close() } catch (_: IOException) {}
        }
    }

    /** Uma falha momentânea (pendrive oscilando) ganha mais uma chance antes de derrubar a conexão. */
    private fun lerComRetentativa(h: VHandle, pos: Long, buf: ByteArray, n: Int): Int = try {
        h.readFully(pos, buf, 0, n)
    } catch (e: IOException) {
        Thread.sleep(150)
        h.readFully(pos, buf, 0, n)
    }

    private fun responder(sai: OutputStream, cod: Int, msg: String) {
        sai.write("HTTP/1.1 $cod $msg\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray())
        sai.flush()
    }

    /** Linhas do cabeçalho até a linha vazia; null se a conexão fechou. */
    private fun lerCabecalho(ent: InputStream): List<String>? {
        val linhas = mutableListOf<String>()
        // Bytes, não caracteres: o cliente simples manda o nome do arquivo em UTF-8 cru (só espaço vira %20)
        val sb = java.io.ByteArrayOutputStream(256)
        var total = 0
        while (true) {
            val c = ent.read()
            if (c < 0) return null
            if (++total > 16384) throw IOException("cabeçalho grande demais")
            if (c == '\n'.code) {
                val l = String(sb.toByteArray(), Charsets.UTF_8).trimEnd('\r')
                sb.reset()
                if (l.isEmpty()) {
                    if (linhas.isEmpty()) continue
                    return linhas
                }
                linhas += l
            } else sb.write(c)
        }
    }

    companion object {
        const val PORT = 1100
        /** "SLUS_201.74.Rumble Racing.iso" (convenção antiga do OPL). */
        private val NOME_OPL = Regex("""^([A-Z]{4}[_-]\d{3}\.\d{2})\.""", RegexOption.IGNORE_CASE)
    }
}
