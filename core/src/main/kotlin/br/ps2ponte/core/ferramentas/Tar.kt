package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.VHandle

/**
 * Escreve um .tar (ustar) sem compactação, como o ART/art.tar que o RiptOPL lê
 * (mesmo formato dos pacotes de arte do wOPL/sOPL). Os nomes ficam na raiz do .tar.
 */
class Tar(private val saida: VHandle) {
    private var pos = 0L

    init {
        saida.setLength(0)
    }

    fun adicionar(nome: String, dados: ByteArray, mtimeMs: Long = System.currentTimeMillis()) {
        val n = nome.toByteArray(Charsets.UTF_8)
        require(n.size <= 100) { "nome longo demais para o tar: $nome" }
        val h = ByteArray(512)
        System.arraycopy(n, 0, h, 0, n.size)
        octal(h, 100, 8, 420)              // modo 0644
        octal(h, 108, 8, 0)                // uid
        octal(h, 116, 8, 0)                // gid
        octal(h, 124, 12, dados.size.toLong())
        octal(h, 136, 12, mtimeMs / 1000)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
        var soma = 0
        for (b in h) soma += b.toInt() and 0xFF
        octal(h, 148, 7, soma.toLong())
        h[155] = ' '.code.toByte()
        escrever(h)
        escrever(dados)
        val resto = (512 - dados.size % 512) % 512
        if (resto > 0) escrever(ByteArray(resto))
    }

    /** Fecha o arquivo com os dois blocos vazios do fim. */
    fun terminar() {
        escrever(ByteArray(1024))
    }

    private fun escrever(b: ByteArray) {
        saida.write(pos, b, 0, b.size)
        pos += b.size
    }

    private fun octal(h: ByteArray, off: Int, len: Int, v: Long) {
        val s = java.lang.Long.toOctalString(v).padStart(len - 1, '0')
        s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
        h[off + len - 1] = 0
    }
}
