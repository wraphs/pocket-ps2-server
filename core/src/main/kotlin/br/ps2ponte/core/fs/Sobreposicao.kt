package br.ps2ponte.core.fs

import java.io.IOException

/**
 * Uma pasta só leitura ([baixo], ex.: pendrive em NTFS, que o Android monta sem gravação) com uma pasta
 * gravável por cima ([cima], na memória do celular). Quem lê vê as duas juntas (a de cima ganha); tudo o que
 * é gravado (capas, art.tar, CFG, VMC, games.bin) vai para cima. Arquivo do pendrive aberto para gravar é
 * copiado para cima primeiro (só os pequenos: VMC, CFG; ISO não).
 */
class Sobreposicao private constructor(
    private val baixo: VFile?,
    private val cima: VFile,
    private val pai: Sobreposicao?,
    private val raizBaixo: String,
    private val rel: String,
) : VFile {
    companion object {
        /** Maior arquivo do pendrive que vira cópia gravável no celular (um VMC de 64 MB cabe). */
        const val COPIA_MAXIMA = 128L * 1024 * 1024

        fun raiz(baixo: VFile, cima: VFile): Sobreposicao = Sobreposicao(baixo, cima, null, baixo.path, "")
    }

    private fun naCima() = cima.exists()
    private fun noBaixo() = baixo?.exists() == true
    private fun atual(): VFile? = if (naCima()) cima else baixo?.takeIf { it.exists() }

    override val name: String get() = if (rel.isEmpty()) (baixo?.name ?: cima.name) else rel.substringAfterLast('/')
    override val path: String get() = if (rel.isEmpty()) raizBaixo else "$raizBaixo/$rel"
    override val parentFile: VFile? get() = pai
    override val isDirectory get() = cima.isDirectory || baixo?.isDirectory == true
    override val isFile get() = atual()?.isFile == true
    override fun exists() = naCima() || noBaixo()
    override fun length(): Long = atual()?.let { if (it.isDirectory) 0L else it.length() } ?: 0L
    override fun lastModified(): Long = atual()?.lastModified() ?: 0L

    override fun listFiles(): List<VFile>? {
        if (!isDirectory) return null
        val nomes = LinkedHashMap<String, String>() // minúsculo -> nome (a de cima ganha)
        cima.takeIf { it.isDirectory }?.listFiles()?.forEach { nomes[it.name.lowercase()] = it.name }
        baixo?.takeIf { it.isDirectory }?.listFiles()?.forEach { nomes.putIfAbsent(it.name.lowercase(), it.name) }
        return nomes.values.map { child(it) }
    }

    override fun child(name: String): VFile {
        // FAT/exFAT/NTFS não diferenciam maiúsculas: casa o nome com o que já existe em cada camada
        // (pela listagem: em FAT/NTFS o exists() também ignora maiúsculas e daria o nome errado)
        fun achar(d: VFile?): VFile? {
            if (d == null) return null
            val lista = d.takeIf { it.isDirectory }?.listFiles() ?: return d.child(name)
            return lista.firstOrNull { it.name == name } ?: lista.firstOrNull { it.name.equals(name, true) } ?: d.child(name)
        }
        val b = achar(baixo)
        val c = achar(cima)!!
        val nome = c.takeIf { it.exists() }?.name ?: b?.takeIf { it.exists() }?.name ?: name
        return Sobreposicao(b, if (c.name == nome) c else cima.child(nome), this, raizBaixo, if (rel.isEmpty()) nome else "$rel/$nome")
    }

    override fun mkdir(): Boolean {
        if (exists() && isDirectory) return false
        return garantirPaiEmCima() && cima.mkdir()
    }

    /** Garante a pasta na camada de cima (mesmo que já exista no pendrive: é lá que se grava). */
    override fun mkdirs(): Boolean = cima.isDirectory || (garantirPaiEmCima() && cima.mkdir())

    /** Cria em cima as pastas que levam até aqui (espelhando o pendrive). */
    private fun garantirPaiEmCima(): Boolean {
        val p = pai ?: return true
        return p.cima.isDirectory || p.mkdirs()
    }

    override fun delete(): Boolean {
        if (noBaixo()) return false // o pendrive é só leitura
        return naCima() && cima.delete()
    }

    override fun renameTo(dest: VFile): Boolean {
        val d = dest as? Sobreposicao ?: return false
        if (noBaixo() || !naCima() || d.exists()) return false
        return d.garantirPaiEmCima() && cima.renameTo(d.cima)
    }

    override fun open(write: Boolean): VHandle {
        if (!write) return (atual() ?: throw java.io.FileNotFoundException(path)).open(false)
        if (!naCima()) {
            if (!garantirPaiEmCima()) throw IOException("não consegui criar a pasta de apoio para $rel")
            if (noBaixo()) copiarParaCima()
        }
        return cima.open(true)
    }

    private fun copiarParaCima() {
        val b = baixo!!
        if (b.length() > COPIA_MAXIMA) throw IOException("só leitura: $rel é grande demais para copiar para o celular")
        b.open(false).use { e ->
            cima.open(true).use { s ->
                s.setLength(0)
                val buf = ByteArray(256 * 1024)
                var pos = 0L
                val tam = e.length()
                while (pos < tam) {
                    val n = e.readFully(pos, buf, 0, minOf(buf.size.toLong(), tam - pos).toInt())
                    if (n <= 0) break
                    s.write(pos, buf, 0, n)
                    pos += n
                }
            }
        }
    }

    override fun totalSpace() = baixo?.totalSpace() ?: cima.totalSpace()
    override fun usableSpace() = cima.usableSpace()
    override fun equals(other: Any?) = other is Sobreposicao && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path
}
