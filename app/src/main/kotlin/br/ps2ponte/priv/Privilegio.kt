package br.ps2ponte.priv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import br.ps2ponte.core.t
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Acesso aos arquivos com privilégio: sobe o [Ajudante] como root (`su`) ou como shell (Shizuku) e
 * conversa com ele por pipe. A pasta escolhida nesses modos fica salva como "root:/caminho" ou
 * "shizuku:/caminho".
 */
object Privilegio {
    const val ROOT = "root"
    const val SHIZUKU = "shizuku"

    @Volatile private var canal: Canal? = null

    fun ehPriv(pasta: String) = pasta.startsWith("$ROOT:") || pasta.startsWith("$SHIZUKU:")
    fun modo(pasta: String) = pasta.substringBefore(':')
    fun caminho(pasta: String) = pasta.substringAfter(':')

    /** Canal já aberto no [modo] (sem bloquear); null se ainda não subiu ou caiu. */
    fun pronto(modo: String): Canal? = canal?.takeIf { it.modo == modo && it.vivo() }

    /**
     * Abre (ou reaproveita) o canal no [modo]. Bloqueia: o `su` pode esperar o usuário tocar em "Permitir".
     * Não chame na thread principal.
     */
    @Synchronized
    fun abrir(ctx: Context, modo: String): Canal {
        pronto(modo)?.let { return it }
        canal?.fechar()
        canal = null
        val apk = ctx.applicationInfo.sourceDir
        val processo = when (modo) {
            ROOT -> try {
                Runtime.getRuntime().exec(arrayOf("su"))
            } catch (e: IOException) {
                throw IOException(t("su não encontrado: o celular não tem root (Magisk/KernelSU)", "su not found: the phone isn't rooted (Magisk/KernelSU)"))
            }
            SHIZUKU -> {
                if (!shizukuRodando()) throw IOException(t("o Shizuku não está rodando: abra o app Shizuku e inicie o serviço", "Shizuku isn't running: open the Shizuku app and start it"))
                if (!shizukuPermitido()) throw IOException(t("o Shizuku ainda não liberou este app", "Shizuku hasn't allowed this app yet"))
                novoProcessoShizuku()
            }
            else -> throw IllegalArgumentException(modo)
        }
        val c = Canal(modo, processo)
        // O shell (do su ou do Shizuku) vira o Ajudante; dali em diante o stdin/stdout são o canal
        c.comando("CLASSPATH='$apk' exec /system/bin/app_process /system/bin ${Ajudante::class.java.name}\n")
        c.esperarMagico(if (modo == ROOT) 60_000 else 15_000)
        canal = c
        return c
    }

    fun fechar() {
        canal?.fechar()
        canal = null
    }

    /** Raiz servida para a pasta "root:/..." ou "shizuku:/...". */
    fun raiz(ctx: Context, pasta: String): VFile {
        val c = abrir(ctx, modo(pasta))
        val dir = caminho(pasta).trimEnd('/').ifEmpty { "/" }
        return ArquivoPriv(c, dir, dir)
    }

    // ------------------------------------------------------------------ Shizuku

    /** Shizuku existe a partir do Android 6. */
    fun shizukuRodando(): Boolean = Build.VERSION.SDK_INT >= 23 && try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun shizukuPermitido(): Boolean = shizukuRodando() &&
        try { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }

    fun pedirShizuku(codigo: Int) {
        if (shizukuRodando()) try { Shizuku.requestPermission(codigo) } catch (_: Throwable) {}
    }

    /** Shizuku.newProcess ficou privado na API 13, mas segue lá (é o caminho que os apps de arquivos usam). */
    private fun novoProcessoShizuku(): Process {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh"), null, null) as Process
    }

    // ------------------------------------------------------------------ canal

    class Canal(val modo: String, private val processo: Process) {
        private val ent = DataInputStream(BufferedInputStream(processo.inputStream, 1 shl 17))
        private val sai = DataOutputStream(BufferedOutputStream(processo.outputStream, 1 shl 17))
        private val erros = StringBuilder()
        @Volatile private var morto: String? = null

        /** Muda a cada gravação; os VFile descartam o que guardaram de tamanho/tipo quando ela muda. */
        val geracao = AtomicLong()

        init {
            // Esvazia o stderr (senão o processo trava quando o buffer enche) e guarda o fim para as mensagens
            Thread({
                try {
                    processo.errorStream.bufferedReader().forEachLine { l ->
                        synchronized(erros) {
                            erros.append(l).append('\n')
                            if (erros.length > 2000) erros.delete(0, erros.length - 2000)
                        }
                    }
                } catch (_: Exception) {
                }
            }, "priv-stderr").apply { isDaemon = true; start() }
        }

        fun vivo() = morto == null && try { processo.exitValue(); false } catch (e: IllegalThreadStateException) { true }

        private fun stderr() = synchronized(erros) { erros.toString().trim().lines().lastOrNull().orEmpty() }

        internal fun comando(s: String) {
            sai.write(s.toByteArray())
            sai.flush()
        }

        internal fun esperarMagico(timeoutMs: Long) {
            var erro: Exception? = null
            var ok = false
            val t = Thread {
                try {
                    // O shell pode imprimir algo antes do exec: procura o número mágico byte a byte
                    var v = 0
                    while (true) {
                        v = (v shl 8) or ent.readUnsignedByte()
                        if (v == Ajudante.MAGICO) break
                    }
                    if (ent.readInt() != Ajudante.VERSAO) throw IOException(t("versão do auxiliar diferente; reinstale o app", "helper version mismatch; reinstall the app"))
                    ok = true
                } catch (e: Exception) {
                    erro = e
                }
            }
            t.isDaemon = true
            t.start()
            t.join(timeoutMs)
            if (!ok) {
                fechar()
                val motivo = stderr().ifEmpty { erro?.message ?: t("sem resposta em ${timeoutMs / 1000} s", "no answer in ${timeoutMs / 1000} s") }
                throw IOException(
                    if (modo == ROOT) t("o root não liberou o acesso ($motivo). Permita o app no Magisk/KernelSU.", "root access was not granted ($motivo). Allow the app in Magisk/KernelSU.")
                    else t("o Shizuku não iniciou o auxiliar ($motivo)", "Shizuku could not start the helper ($motivo)")
                )
            }
        }

        /** Um pedido por vez (o canal é um pipe só). [pedido] escreve os argumentos; [resposta] lê o resultado. */
        @Synchronized
        fun <T> chamar(op: Int, pedido: (DataOutputStream) -> Unit, resposta: (DataInputStream) -> T): T {
            morto?.let { throw IOException(it) }
            try {
                sai.writeByte(op)
                pedido(sai)
                sai.flush()
                return when (ent.readUnsignedByte()) {
                    0 -> resposta(ent)
                    else -> throw IOException(ent.readUTF())
                }
            } catch (e: IOException) {
                if (!vivo() || e is java.io.EOFException) {
                    val m = t("o acesso $modo caiu (${stderr().ifEmpty { e.message }})", "$modo access stopped (${stderr().ifEmpty { e.message }})")
                    morto = m
                    throw IOException(m)
                }
                throw e
            }
        }

        fun fechar() {
            morto = morto ?: t("canal fechado", "channel closed")
            try { sai.close() } catch (_: Exception) {}
            processo.destroy()
        }
    }
}

/** Arquivo/pasta lido pelo [Privilegio.Canal]. Guarda o stat até a próxima gravação no canal. */
class ArquivoPriv internal constructor(
    private val canal: Privilegio.Canal,
    override val path: String,
    private val raizPath: String,
) : VFile {
    private class Stat(val existe: Boolean, val dir: Boolean, val tam: Long, val mod: Long, val geracao: Long)

    @Volatile private var stat: Stat? = null

    private constructor(canal: Privilegio.Canal, path: String, raiz: String, s: Stat) : this(canal, path, raiz) { stat = s }

    private fun lerStat(e: DataInputStream) = Stat(e.readBoolean(), e.readBoolean(), e.readLong(), e.readLong(), canal.geracao.get())

    private fun st(): Stat {
        stat?.let { if (it.geracao == canal.geracao.get()) return it }
        val s = try {
            canal.chamar(Ajudante.OP_STAT, { it.writeUTF(path) }) { lerStat(it) }
        } catch (e: IOException) {
            Stat(false, false, 0, 0, -1)
        }
        stat = s
        return s
    }

    private fun mudou() = canal.geracao.incrementAndGet()

    override val name: String get() = path.substringAfterLast('/')
    override val parentFile: VFile? get() = if (path == raizPath) null else ArquivoPriv(canal, path.substringBeforeLast('/').ifEmpty { "/" }, raizPath)
    override val isDirectory get() = st().dir
    override val isFile get() = st().let { it.existe && !it.dir }
    override fun exists() = st().existe
    override fun length() = st().tam
    override fun lastModified() = st().mod

    override fun listFiles(): List<VFile>? = try {
        canal.chamar(Ajudante.OP_LIST, { it.writeUTF(path) }) { e ->
            val n = e.readInt()
            if (n < 0) null else List(n) {
                val nome = e.readUTF()
                ArquivoPriv(canal, filho(nome), raizPath, lerStat(e))
            }
        }
    } catch (e: IOException) {
        null
    }

    private fun filho(nome: String) = if (path == "/") "/$nome" else "$path/$nome"

    override fun child(name: String): VFile = ArquivoPriv(canal, filho(name), raizPath)

    private fun bool(op: Int, vararg args: String): Boolean = try {
        canal.chamar(op, { o -> args.forEach { o.writeUTF(it) } }) { it.readBoolean() }.also { mudou() }
    } catch (e: IOException) {
        false
    }

    override fun mkdir() = bool(Ajudante.OP_MKDIR, path)
    override fun delete() = bool(Ajudante.OP_DELETE, path)
    override fun renameTo(dest: VFile) = dest is ArquivoPriv && bool(Ajudante.OP_RENAME, path, dest.path)

    override fun open(write: Boolean): VHandle {
        val id = canal.chamar(Ajudante.OP_OPEN, { it.writeUTF(path); it.writeBoolean(write) }) { it.readInt() }
        if (write) mudou()
        return HandlePriv(canal, id, write) { mudou() }
    }

    private fun espaco(): Pair<Long, Long> = try {
        canal.chamar(Ajudante.OP_SPACE, { it.writeUTF(path) }) { it.readLong() to it.readLong() }
    } catch (e: IOException) {
        0L to 0L
    }

    override fun totalSpace() = espaco().first
    override fun usableSpace() = espaco().second

    override fun equals(other: Any?) = other is ArquivoPriv && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path
}

private class HandlePriv(
    private val canal: Privilegio.Canal,
    private val id: Int,
    private val gravavel: Boolean,
    private val mudou: () -> Unit,
) : VHandle {
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var total = 0
        // Em pedaços de até 1 MiB (limite do auxiliar)
        while (total < len) {
            val pedir = minOf(len - total, Ajudante.MAX_BLOCO)
            val n = canal.chamar(Ajudante.OP_READ, { it.writeInt(id); it.writeLong(pos + total); it.writeInt(pedir) }) { e ->
                val n = e.readInt()
                if (n > 0) e.readFully(buf, off + total, n)
                n
            }
            if (n <= 0) return if (total == 0) -1 else total
            total += n
            if (n < pedir) break
        }
        return total
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        var feito = 0
        while (feito < len) {
            val k = minOf(len - feito, Ajudante.MAX_BLOCO)
            canal.chamar(Ajudante.OP_WRITE, { it.writeInt(id); it.writeLong(pos + feito); it.writeInt(k); it.write(buf, off + feito, k) }) { }
            feito += k
        }
        mudou()
    }

    override fun length(): Long = canal.chamar(Ajudante.OP_LENGTH, { it.writeInt(id) }) { it.readLong() }

    override fun setLength(n: Long) {
        canal.chamar(Ajudante.OP_SETLEN, { it.writeInt(id); it.writeLong(n) }) { }
        mudou()
    }

    override fun close() {
        try { canal.chamar(Ajudante.OP_CLOSE, { it.writeInt(id) }) { } } catch (_: IOException) {}
        if (gravavel) mudou()
    }
}
