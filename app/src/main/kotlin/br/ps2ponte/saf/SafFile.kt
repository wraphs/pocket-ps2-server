package br.ps2ponte.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Árvore do Storage Access Framework (pasta escolhida no seletor do Android).
 * É o único jeito de um app acessar pendrive USB: o Android não deixa abrir /storage/<uuid> por caminho.
 *
 * Metadados e listagens ficam em cache até a próxima alteração feita por este app ([geracao]):
 * cada consulta ao SAF é uma chamada entre processos (~1 ms).
 */
class SafRaiz(context: Context, val treeUri: Uri) {
    val resolver: ContentResolver = context.contentResolver
    val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val geracao = AtomicLong()

    fun gen() = geracao.get()
    fun mudou() { geracao.incrementAndGet() }

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    fun childrenUri(docId: String): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

    @Volatile private var espaco: Pair<Long, Long>? = null
    @Volatile private var espacoQuando = 0L

    /** (capacidade, livre) do volume, pela lista de raízes do provedor. */
    fun espaco(): Pair<Long, Long> {
        val agora = System.currentTimeMillis()
        espaco?.let { if (agora - espacoQuando < 5000) return it }
        var r = 0L to 0L
        try {
            val rootId = rootDocId.substringBefore(':')
            resolver.query(DocumentsContract.buildRootsUri(treeUri.authority), null, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                val iLivre = c.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                val iTotal = c.getColumnIndex(DocumentsContract.Root.COLUMN_CAPACITY_BYTES)
                while (c.moveToNext()) {
                    if (iId < 0 || c.getString(iId) != rootId) continue
                    val livre = if (iLivre >= 0 && !c.isNull(iLivre)) c.getLong(iLivre) else 0L
                    val total = if (iTotal >= 0 && !c.isNull(iTotal)) c.getLong(iTotal) else livre
                    r = total to livre
                }
            }
        } catch (_: Exception) {
        }
        espaco = r
        espacoQuando = agora
        return r
    }

    fun raiz(): SafFile = SafFile(this, null, "", rootDocId)

    companion object {
        /** Pasta salva como URI de árvore (content://...). */
        fun ehSaf(pasta: String) = pasta.startsWith("content://")
    }
}

class SafFile internal constructor(
    private val raiz: SafRaiz,
    override val parentFile: SafFile?,
    override val name: String,
    @Volatile private var docId: String?,
) : VFile {

    private class Meta(val dir: Boolean, val size: Long, val mtime: Long, val gen: Long)

    @Volatile private var meta: Meta? = null
    @Volatile private var filhos: List<SafFile>? = null
    @Volatile private var filhosGen = -1L

    override val path: String = if (parentFile == null) "saf:${raiz.rootDocId}" else "${parentFile.path}/$name"

    // ---------------------------------------------------------------- metadados

    /** Atualiza docId/meta se o cache estiver velho; devolve null se o item não existe. */
    private fun info(): Meta? {
        val g = raiz.gen()
        meta?.let { if (it.gen == g && docId != null) return it }
        val id = docId
        if (id != null) {
            val m = consultar(id, g)
            if (m != null) { meta = m; return m }
            docId = null
        }
        if (parentFile == null) return null
        // Procura pelo nome na listagem do pai (que já traz os metadados)
        val achado = parentFile.listar()?.firstOrNull { it.name == name } ?: return null
        docId = achado.docId
        meta = achado.meta
        return meta
    }

    private fun consultar(id: String, g: Long): Meta? = try {
        raiz.resolver.query(raiz.docUri(id), PROJ, null, null, null)?.use { c -> if (c.moveToFirst()) meta(c, g) else null }
    } catch (e: Exception) {
        null
    }

    private fun meta(c: android.database.Cursor, g: Long): Meta {
        val iMime = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
        val iSize = c.getColumnIndex(Document.COLUMN_SIZE)
        val iTime = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        val dir = iMime >= 0 && c.getString(iMime) == Document.MIME_TYPE_DIR
        val size = if (!dir && iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L
        val mtime = if (iTime >= 0 && !c.isNull(iTime)) c.getLong(iTime) else 0L
        return Meta(dir, size, mtime, g)
    }

    /** Filhos com metadados (uma consulta ao SAF), em cache até a próxima alteração. */
    private fun listar(): List<SafFile>? {
        val g = raiz.gen()
        filhos?.let { if (filhosGen == g) return it }
        val id = if (parentFile == null) docId else (info()?.takeIf { it.dir }?.let { docId } ?: return null)
        id ?: return null
        val out = ArrayList<SafFile>()
        try {
            raiz.resolver.query(raiz.childrenUri(id), PROJ, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
                val iNome = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val f = SafFile(raiz, this, c.getString(iNome) ?: continue, c.getString(iId))
                    f.meta = meta(c, g)
                    out += f
                }
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        filhos = out
        filhosGen = g
        return out
    }

    override val isDirectory: Boolean get() = info()?.dir == true
    override val isFile: Boolean get() = info()?.let { !it.dir } == true
    override fun exists() = info() != null
    override fun length() = info()?.size ?: 0L
    override fun lastModified() = info()?.mtime ?: 0L
    override fun listFiles(): List<VFile>? = listar()
    override fun child(name: String): VFile =
        filhos?.takeIf { filhosGen == raiz.gen() }?.firstOrNull { it.name == name } ?: SafFile(raiz, this, name, null)

    override fun totalSpace() = raiz.espaco().first
    override fun usableSpace() = raiz.espaco().second

    // ---------------------------------------------------------------- alterações

    private fun idDoPai(): String? = parentFile?.let { p -> if (p.exists() && p.isDirectory) p.docId else null }

    override fun mkdir(): Boolean {
        if (exists()) return false
        val pai = idDoPai() ?: return false
        return try {
            val uri = DocumentsContract.createDocument(raiz.resolver, raiz.docUri(pai), Document.MIME_TYPE_DIR, name) ?: return false
            docId = DocumentsContract.getDocumentId(uri)
            true
        } catch (e: Exception) {
            false
        } finally {
            raiz.mudou()
        }
    }

    override fun delete(): Boolean {
        val m = info() ?: return false
        if (parentFile == null) return false
        // O SAF apaga pastas com tudo dentro; java.io.File só apaga pasta vazia
        if (m.dir && listar()?.isNotEmpty() == true) return false
        val id = docId ?: return false
        return try {
            DocumentsContract.deleteDocument(raiz.resolver, raiz.docUri(id)).also { if (it) docId = null }
        } catch (e: Exception) {
            false
        } finally {
            raiz.mudou()
        }
    }

    override fun renameTo(dest: VFile): Boolean {
        if (dest !is SafFile || parentFile == null || dest.parentFile == null) return false
        if (dest.exists() || !exists()) return false
        var id = docId ?: return false
        return try {
            if (dest.parentFile.path != parentFile.path) {
                if (android.os.Build.VERSION.SDK_INT < 24) return false // mover entre pastas no SAF só existe no Android 7+
                val destPai = dest.idDoPai() ?: return false
                val origemPai = idDoPai() ?: return false
                val movido = DocumentsContract.moveDocument(raiz.resolver, raiz.docUri(id), raiz.docUri(origemPai), raiz.docUri(destPai)) ?: return false
                id = DocumentsContract.getDocumentId(movido)
            }
            if (dest.name != name) {
                val renomeado = DocumentsContract.renameDocument(raiz.resolver, raiz.docUri(id), dest.name) ?: return false
                id = DocumentsContract.getDocumentId(renomeado)
            }
            dest.docId = id
            docId = null
            true
        } catch (e: Exception) {
            false
        } finally {
            raiz.mudou()
        }
    }

    override fun open(write: Boolean): VHandle {
        if (!exists()) {
            if (!write) throw FileNotFoundException(path)
            val pai = idDoPai() ?: throw FileNotFoundException("pasta não existe: ${parentFile?.path}")
            try {
                val uri = DocumentsContract.createDocument(raiz.resolver, raiz.docUri(pai), "application/octet-stream", name)
                    ?: throw IOException("não consegui criar $path")
                docId = DocumentsContract.getDocumentId(uri)
            } finally {
                raiz.mudou()
            }
        }
        if (isDirectory) throw IOException("é uma pasta: $path")
        val modo = if (write) "rw" else "r"
        val id = docId ?: throw FileNotFoundException(path)
        val pfd = raiz.resolver.openFileDescriptor(raiz.docUri(id), modo) ?: throw IOException("não consegui abrir $path")
        // Reabertura depois de o pendrive cair e voltar (esbarrão no hub): procura o arquivo de novo
        val reabrir = {
            raiz.mudou()
            val novo = docId.takeIf { exists() } ?: throw FileNotFoundException(path)
            raiz.resolver.openFileDescriptor(raiz.docUri(novo), modo) ?: throw IOException("não consegui reabrir $path")
        }
        return SafHandle(pfd, reabrir, if (write) raiz::mudou else null)
    }

    override fun equals(other: Any?) = other is SafFile && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path

    companion object {
        private val PROJ = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Arquivo aberto pelo SAF: E/S posicional (pread/pwrite) direto no descritor, segura entre threads. */
private class SafHandle(
    @Volatile private var pfd: ParcelFileDescriptor,
    private val reabrir: () -> ParcelFileDescriptor,
    private val aoMudar: (() -> Unit)?,
) : VHandle {
    private val trava = Any()
    @Volatile private var fechado = false

    /**
     * Executa a operação no descritor atual. Se ele morrer (o pendrive caiu num esbarrão no hub),
     * espera o pendrive voltar e reabre o arquivo, por até [ESPERA_MS], e tenta de novo: o PS2 só
     * aguenta alguns segundos sem resposta, então voltar rápido é o que evita o jogo travar.
     */
    private fun <T> io(f: (java.io.FileDescriptor) -> T): T {
        var reaberto = false
        while (true) {
            val atual = pfd
            try {
                return f(atual.fileDescriptor)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                if (reaberto || fechado) throw IOException(e.message, e)
                reabrirEsperando(atual, e)
                reaberto = true
            }
        }
    }

    private fun reabrirEsperando(morto: ParcelFileDescriptor, causa: ErrnoException) = synchronized(trava) {
        if (pfd !== morto) return // outra thread já reabriu
        val limite = System.currentTimeMillis() + ESPERA_MS
        while (!fechado) {
            try {
                val novo = reabrir()
                try { morto.close() } catch (_: Exception) {}
                pfd = novo
                return
            } catch (e: Exception) {
                if (System.currentTimeMillis() > limite) throw IOException("pendrive não voltou: ${causa.message}", causa)
                Thread.sleep(250)
            }
        }
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = io { fd -> Os.pread(fd, buf, off, len, pos) }
        return if (n == 0) -1 else n
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        var feito = 0
        while (feito < len) feito += io { fd -> Os.pwrite(fd, buf, off + feito, len - feito, pos + feito) }
        aoMudar?.invoke()
    }

    override fun length(): Long = io { fd -> Os.fstat(fd).st_size }

    override fun setLength(n: Long) {
        io { fd -> Os.ftruncate(fd, n) }
        aoMudar?.invoke()
    }

    override fun close() {
        fechado = true
        pfd.close()
    }

    companion object {
        private const val ESPERA_MS = 8000L
    }
}
