package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.VHandle

/**
 * Lê o código do jogo ("SLUS_209.46") do SYSTEM.CNF de uma ISO (ISO 9660, setores de 2048).
 * É o nome que o OPL usa nas capas (ART/SLUS_209.46_COV.png), nos cheats e nas configurações.
 */
object IdJogo {
    private val BOOT = Regex("""BOOT2?\s*=\s*cdrom0?:\\?([A-Z]{4}[_-]\d{3}\.\d{2})""", RegexOption.IGNORE_CASE)

    fun ler(h: VHandle): String? {
        val pvd = ByteArray(2048)
        if (h.readFully(16L * 2048, pvd, 0, 2048) < 2048) return null
        if (pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
        // Registro da pasta raiz começa no byte 156 do descritor primário
        val raizLba = le32(pvd, 156 + 2)
        val raizTam = le32(pvd, 156 + 10).coerceIn(0, 64 * 1024)
        val dir = ByteArray(raizTam)
        if (h.readFully(raizLba.toLong() * 2048, dir, 0, raizTam) < raizTam) return null
        var i = 0
        while (i < raizTam) {
            val len = dir[i].toInt() and 0xFF
            if (len == 0) {
                // Registros não atravessam setores: pula para o próximo
                i = (i / 2048 + 1) * 2048
                continue
            }
            if (i + 33 > raizTam) break
            val nomeLen = dir[i + 32].toInt() and 0xFF
            val nome = String(dir, i + 33, minOf(nomeLen, raizTam - i - 33), Charsets.US_ASCII).substringBefore(';')
            if (nome.equals("SYSTEM.CNF", true)) {
                val lba = le32(dir, i + 2)
                val tam = le32(dir, i + 10).coerceIn(0, 4096)
                val cnf = ByteArray(tam)
                val n = h.readFully(lba.toLong() * 2048, cnf, 0, tam)
                return deCnf(String(cnf, 0, n, Charsets.ISO_8859_1))
            }
            i += len
        }
        return null
    }

    /** "BOOT2 = cdrom0:\SLUS_209.46;1" -> "SLUS_209.46" */
    fun deCnf(cnf: String): String? = BOOT.find(cnf)?.groupValues?.get(1)?.uppercase()?.replace('-', '_')

    /** "SLUS_209.46" -> "SLUS-20946" (como os bancos de capas nomeiam). */
    fun serial(id: String) = id.replace('_', '-').replace(".", "")

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)
}
