package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.VFile
import java.io.IOException

/**
 * Confere os arquivos que os loaders precisam na pasta servida (POPStarter, Ember, Neutrino, OPL).
 * Só olha presença, tamanho e assinatura ELF: os arquivos da Sony (POPS, BIOS) não são distribuídos, o usuário
 * traz os seus.
 */
object Verificador {
    enum class Estado { OK, FALTA, ERRADO, OPCIONAL }

    class Item(val grupo: String, val caminho: String, val estado: Estado, val nota: String = "")

    private class Regra(val caminho: String, val obrigatorio: Boolean, val elf: Boolean = false, val tamanho: Long = -1, val nota: String = "")

    private val POPS = listOf(
        Regra("POPS/POPS.ELF", true, elf = true, nota = "emulador da Sony (traga o seu)"),
        Regra("POPS/IOPRP252.IMG", true, nota = "módulos do IOP da Sony (traga o seu)"),
        Regra("POPS/POPS_IOX.PAK", true, nota = "necessário para USB e rede (SMB/UDPFS)"),
        Regra("POPS/POPSTARTER.ELF", true, elf = true, nota = "o OPL/RiptOPL usa para abrir os VCDs"),
        Regra("POPS/BIOS.BIN", false, nota = "opcional: BIOS do PS1 (cartão de memória, CD de áudio)"),
    )

    private val EMBER = listOf(
        Regra("EMBER/ember.elf", true, elf = true, nota = "emulador Ember (Gageformer)"),
        Regra("EMBER/bios.bin", true, tamanho = 512 * 1024, nota = "BIOS do PS1 de 512 KiB (traga a sua)"),
        Regra("EMBER/games", true, nota = "pasta dos jogos"),
    )

    private val NEUTRINO = listOf(
        Regra("NEUTRINO/modules/ee_core.elf", true, elf = true),
        Regra("NEUTRINO/modules/cdvdman_emu.irx", true),
        Regra("NEUTRINO/modules/smap.irx", true, nota = "rede"),
        Regra("NEUTRINO/config/system.toml", true),
    )

    /** Grupos relevantes: os que têm pasta ou jogos. [cat] diz se há jogos de cada tipo. */
    fun verificar(raiz: VFile, cat: Catalogo?): List<Item> {
        val out = mutableListOf<Item>()
        val temVcd = cat?.jogos?.any { it.tipo == Catalogo.Tipo.VCD } == true
        val temCue = cat?.jogos?.any { it.tipo == Catalogo.Tipo.CUE } == true
        if (temVcd || existe(raiz, "POPS")) checar(raiz, "POPStarter", POPS, out)
        if (temCue || existe(raiz, "EMBER")) checar(raiz, "Ember", EMBER, out)
        if (existe(raiz, "NEUTRINO")) checar(raiz, "Neutrino", NEUTRINO, out)
        // Pastas que o OPL espera no compartilhamento
        for (p in listOf("DVD", "CD", "ART", "CFG", "VMC")) {
            out += Item("OPL", "$p/", if (existe(raiz, p)) Estado.OK else Estado.OPCIONAL, if (existe(raiz, p)) "" else "use Organizar para criar")
        }
        return out
    }

    private fun existe(raiz: VFile, rel: String) = achar(raiz, rel) != null

    /** Acha sem diferenciar maiúsculas (FAT/exFAT do PS2 não diferencia; o OPL pede "POPS", a pasta pode ser "pops"). */
    private fun achar(raiz: VFile, rel: String): VFile? {
        var cur = raiz
        for (parte in rel.split('/')) {
            val exato = cur.child(parte)
            cur = if (exato.exists()) exato else cur.listFiles()?.firstOrNull { it.name.equals(parte, true) } ?: return null
        }
        return cur
    }

    private fun checar(raiz: VFile, grupo: String, regras: List<Regra>, out: MutableList<Item>) {
        for (r in regras) {
            val f = achar(raiz, r.caminho)
            val estado = when {
                f == null -> if (r.obrigatorio) Estado.FALTA else Estado.OPCIONAL
                r.tamanho >= 0 && f.length() != r.tamanho -> Estado.ERRADO
                f.isFile && f.length() == 0L -> Estado.ERRADO
                r.elf && !ehElf(f) -> Estado.ERRADO
                else -> Estado.OK
            }
            val nota = when (estado) {
                Estado.ERRADO -> when {
                    r.tamanho >= 0 -> "tamanho ${f!!.length()} bytes; esperado ${r.tamanho}"
                    f!!.length() == 0L -> "arquivo vazio"
                    else -> "não é um ELF válido (arquivo corrompido ou trocado)"
                }
                else -> r.nota
            }
            out += Item(grupo, r.caminho, estado, nota)
        }
    }

    private fun ehElf(f: VFile): Boolean = try {
        f.open(false).use { h ->
            val b = ByteArray(4)
            h.readFully(0, b, 0, 4) == 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (e: IOException) {
        false
    }
}
