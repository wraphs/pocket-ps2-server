package br.ps2ponte.core.ferramentas

import br.ps2ponte.core.fs.LocalFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ps1Test {
    @TempDir lateinit var dir: File

    /** Imagem PS1 crua: faixa de dados MODE2 com ISO 9660 (SYSTEM.CNF) e [audios] faixas de áudio aleatórias. */
    private fun faixaDados(setores: Int): ByteArray {
        val iso = ByteArray(setores * 2048)
        val pvd = 16 * 2048
        iso[pvd] = 1
        "CD001".toByteArray().copyInto(iso, pvd + 1)
        fun le32(off: Int, v: Int) { for (k in 0..3) iso[off + k] = (v ushr (8 * k)).toByte() }
        le32(pvd + 156 + 2, 18); le32(pvd + 156 + 10, 2048)
        val r = 18 * 2048
        val nome = "SYSTEM.CNF;1".toByteArray()
        iso[r] = (33 + nome.size + 1).toByte(); le32(r + 2, 20)
        val cnf = "BOOT = cdrom:\\SLUS_000.67;1\r\nTCB = 4\r\n".toByteArray()
        le32(r + 10, cnf.size); iso[r + 32] = nome.size.toByte(); nome.copyInto(iso, r + 33)
        cnf.copyInto(iso, 20 * 2048)
        val raw = ByteArray(setores * Ps1.SETOR)
        val rnd = Random(5)
        for (s in 0 until setores) {
            val b = s * Ps1.SETOR
            for (k in 0 until 24) raw[b + k] = rnd.nextInt().toByte() // sincronismo/cabeçalho (irrelevante aqui)
            System.arraycopy(iso, s * 2048, raw, b + 24, 2048)
        }
        return raw
    }

    private fun audio(setores: Int, semente: Int) = Random(semente).nextBytes(setores * Ps1.SETOR)

    private fun msf(s: Int) = "%02d:%02d:%02d".format(s / 4500, (s % 4500) / 75, s % 75)

    private fun converter(cue: File, saida: File): ByteArray {
        val c = Ps1.lerCue(cue.readText()) { n -> File(cue.parentFile, n).let { if (it.exists()) it.length() else -1 } }
        val raiz = LocalFile.root(cue.parentFile)
        val bins = c.arquivos.map { raiz.child(it).open(false) }
        raiz.child(saida.name).open(true).use { s -> Ps1.paraVcd(c, bins, s) { _, _ -> } }
        bins.forEach { it.close() }
        return saida.readBytes()
    }

    @Test
    fun vcdDeUmaBinENoFormatoRedumpSaoIguais() {
        val d = faixaDados(1200); val a1 = audio(900, 1); val a2 = audio(800, 2)
        File(dir, "jogo.bin").writeBytes(d + a1 + a2)
        val t2 = 1200; val t3 = 1200 + 900
        File(dir, "jogo.cue").writeText(
            "FILE \"jogo.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n" +
                "  TRACK 02 AUDIO\r\n    INDEX 00 ${msf(t2)}\r\n    INDEX 01 ${msf(t2 + 150)}\r\n" +
                "  TRACK 03 AUDIO\r\n    INDEX 00 ${msf(t3)}\r\n    INDEX 01 ${msf(t3 + 150)}\r\n")
        val vcd = converter(File(dir, "jogo.cue"), File(dir, "jogo.VCD"))

        // Redump: uma .bin por faixa, tempos relativos a cada arquivo
        File(dir, "t1.bin").writeBytes(d); File(dir, "t2.bin").writeBytes(a1); File(dir, "t3.bin").writeBytes(a2)
        File(dir, "redump.cue").writeText(
            "FILE \"t1.bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n" +
                "FILE \"t2.bin\" BINARY\n  TRACK 02 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n" +
                "FILE \"t3.bin\" BINARY\n  TRACK 03 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n")
        val vcd2 = converter(File(dir, "redump.cue"), File(dir, "redump.VCD"))
        assertContentEquals(vcd, vcd2)

        assertEquals(Ps1.CABECALHO_VCD + d.size + a1.size + a2.size, vcd.size)
        assertEquals("kHn ", String(vcd, 1024, 4, Charsets.ISO_8859_1))
        assertEquals(2900, (vcd[1032].toInt() and 0xFF) or ((vcd[1033].toInt() and 0xFF) shl 8))
        // Faixa 1: INDEX 01 = 00:02:00; faixa 2: INDEX 00 = 1200+150 setores = 00:18:00
        assertContentEquals(byteArrayOf(0x41, 0, 0x01, 0, 0, 0, 0, 0, 0x02, 0), vcd.copyOfRange(30, 40))
        assertContentEquals(byteArrayOf(0x01, 0, 0x02, 0x00, 0x18, 0x00), vcd.copyOfRange(40, 46))
        assertEquals("SLUS_000.67", File(dir, "jogo.VCD").let { f -> LocalFile.root(dir).child(f.name).open(false).use { Ps1.lerId(it, vcd = true) } })
        assertEquals("SLUS_000.67", LocalFile.root(dir).child("jogo.bin").open(false).use { Ps1.lerId(it, vcd = false) })

        // De volta: o CUE gerado do VCD aponta os mesmos setores
        val volta = Ps1.lerCue(Ps1.cueDoVcd(vcd.copyOf(2048), "x.bin")) { (d.size + a1.size + a2.size).toLong() }
        val ida = Ps1.lerCue(File(dir, "jogo.cue").readText()) { File(dir, "jogo.bin").length() }
        assertEquals(ida.faixas.map { it.index00 to it.index01 }, volta.faixas.map { it.index00 to it.index01 })
        assertEquals(listOf(false, true, true), volta.faixas.map { it.audio })

        conferirComCue2pops("jogo.cue", vcd)
    }

    @Test
    fun cdrwinGanhaSilencio() {
        val d = faixaDados(600); val a = audio(300, 3)
        File(dir, "cw.bin").writeBytes(d + a)
        File(dir, "cw.cue").writeText(
            "FILE \"cw.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n" +
                "  TRACK 02 AUDIO\r\n    PREGAP 00:02:00\r\n    INDEX 01 ${msf(600)}\r\n")
        val vcd = converter(File(dir, "cw.cue"), File(dir, "cw.VCD"))
        assertEquals(Ps1.CABECALHO_VCD + d.size + 150 * Ps1.SETOR + a.size, vcd.size)
        // Silêncio entre os dados e o áudio
        val ini = Ps1.CABECALHO_VCD + d.size
        assertTrue(vcd.copyOfRange(ini, ini + 150 * Ps1.SETOR).all { it.toInt() == 0 })
        assertContentEquals(a.copyOf(100), vcd.copyOfRange(ini + 150 * Ps1.SETOR, ini + 150 * Ps1.SETOR + 100))
        conferirComCue2pops("cw.cue", vcd)
    }

    /** Se CUE2POPS apontar para o cue2pops original compilado, compara byte a byte. */
    private fun conferirComCue2pops(cue: String, nosso: ByteArray) {
        val exe = System.getenv("CUE2POPS")?.let { File(it) }?.takeIf { it.exists() } ?: return
        val p = ProcessBuilder(exe.path, cue, "REF.VCD").directory(dir).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor()
        val ref = File(dir, "REF.VCD").readBytes()
        assertEquals(ref.size, nosso.size, "tamanho igual ao do cue2pops")
        val dif = (ref.indices).firstOrNull { ref[it] != nosso[it] }
        assertNull(dif, "primeiro byte diferente do cue2pops: $dif")
        println("conferido com o cue2pops: $cue (${ref.size} bytes iguais)")
        File(dir, "REF.VCD").delete()
    }

    private fun cueSimples(dir: File, base: String, dados: ByteArray) {
        File(dir, "$base.bin").writeBytes(dados)
        File(dir, "$base.cue").writeText("FILE \"$base.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n")
    }

    private val semProgresso = OrganizadorPs1.Progresso { _, _, _ -> }

    @Test
    fun organizaParaEmberEPops() {
        val dados = faixaDados(300)
        File(dir, "Downloads").mkdirs()
        cueSimples(File(dir, "Downloads"), "Crash Bandicoot (USA)", dados)
        File(dir, "Downloads/Crash Bandicoot (USA).sbi").writeText("sbi")
        // Um VCD solto na raiz
        File(dir, "Spyro.cue").writeText("FILE \"Spyro.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n")
        File(dir, "Spyro.bin").writeBytes(dados)
        converter(File(dir, "Spyro.cue"), File(dir, "Spyro.VCD"))
        File(dir, "Spyro.cue").delete(); File(dir, "Spyro.bin").delete()

        val raiz = LocalFile.root(dir)
        val cat = Catalogo.varrer(raiz, null)
        assertEquals(2, cat.ps1.size)
        assertTrue(cat.ps1.all { it.foraDoLugar && it.id == "SLUS_000.67" })

        // MANTER: CUE/BIN vai para o Ember, VCD para o POPS, sem conversão
        val plano = OrganizadorPs1(raiz).planejar(cat, OrganizadorPs1.Destino.MANTER)
        assertEquals(0, plano.conversoes)
        assertTrue(plano.executar(false, semProgresso).isEmpty())
        assertTrue(File(dir, "EMBER/games/Crash Bandicoot (USA)/Crash Bandicoot (USA).cue").exists())
        assertTrue(File(dir, "EMBER/games/Crash Bandicoot (USA)/Crash Bandicoot (USA).bin").exists())
        assertTrue(File(dir, "EMBER/games/Crash Bandicoot (USA)/Crash Bandicoot (USA).sbi").exists())
        assertTrue(File(dir, "POPS/Spyro.VCD").exists())
        val cat2 = Catalogo.varrer(raiz, cat)
        assertEquals(0, cat2.foraDoLugar)
        assertEquals("Crash Bandicoot (USA)", cat2.ps1.first { it.tipo == Catalogo.Tipo.CUE }.chaveArte)
        assertEquals("Spyro", cat2.ps1.first { it.tipo == Catalogo.Tipo.VCD }.chaveArte)

        // Tudo para o POPS: o jogo do Ember é convertido (e o original apagado)
        val p2 = OrganizadorPs1(raiz).planejar(cat2, OrganizadorPs1.Destino.POPS)
        assertEquals(1, p2.conversoes)
        assertTrue(p2.executar(true, semProgresso).isEmpty())
        assertTrue(File(dir, "POPS/Crash Bandicoot (USA).VCD").length() == Ps1.CABECALHO_VCD + dados.size.toLong())
        assertFalse(File(dir, "EMBER/games/Crash Bandicoot (USA)/Crash Bandicoot (USA).bin").exists())

        // E de volta para o Ember: VCD -> CUE/BIN com os mesmos dados
        val p3 = OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.EMBER)
        assertEquals(2, p3.conversoes)
        assertTrue(p3.executar(false, semProgresso).isEmpty())
        assertContentEquals(dados, File(dir, "EMBER/games/Spyro/Spyro.bin").readBytes())
        val cue = Ps1.lerCue(File(dir, "EMBER/games/Spyro/Spyro.cue").readText()) { File(dir, "EMBER/games/Spyro/$it").length() }
        assertEquals(1, cue.faixas.size)
        assertTrue(File(dir, "POPS/Spyro.VCD").exists(), "manter originais")
    }

    @Test
    fun conversaoPorPastaTemporaria() {
        val dados = faixaDados(300)
        val pen = File(dir, "pen").apply { mkdirs() }
        val outro = File(dir, "outro").apply { mkdirs() }
        cueSimples(pen, "Spyro", dados)
        val raiz = LocalFile.root(pen)
        val temp = LocalFile.root(outro)

        // CUE/BIN -> VCD montado no outro volume, original apagado, VCD trazido de volta
        val p1 = OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.POPS)
        assertTrue(p1.executar(true, semProgresso, temp).isEmpty())
        assertEquals(Ps1.CABECALHO_VCD + dados.size.toLong(), File(pen, "POPS/Spyro.VCD").length())
        assertFalse(File(pen, "Spyro.bin").exists())
        assertTrue(outro.listFiles()!!.isEmpty(), "o temporário fica vazio")

        // E de volta: VCD -> CUE/BIN pelo outro volume
        val p2 = OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.EMBER)
        assertTrue(p2.executar(true, semProgresso, temp).isEmpty())
        assertContentEquals(dados, File(pen, "EMBER/games/Spyro/Spyro.bin").readBytes())
        assertTrue(File(pen, "EMBER/games/Spyro/Spyro.cue").exists())
        assertFalse(File(pen, "POPS/Spyro.VCD").exists())
        assertTrue(outro.listFiles()!!.isEmpty())
    }

    @Test
    fun multidisco() {
        val dados = faixaDados(200)
        File(dir, "FF").mkdirs()
        cueSimples(File(dir, "FF"), "Final Fantasy VII (Disc 1)", dados)
        cueSimples(File(dir, "FF"), "Final Fantasy VII (Disc 2)", dados)
        cueSimples(File(dir, "FF"), "Final Fantasy VII (Disc 3)", dados)
        val raiz = LocalFile.root(dir)

        // Ember: uma pasta só, com Discs.txt na ordem
        val pe = OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.MANTER)
        assertTrue(pe.executar(false, semProgresso).isEmpty())
        val pasta = File(dir, "EMBER/games/Final Fantasy VII")
        assertEquals(
            listOf("Final Fantasy VII (Disc 1).cue", "Final Fantasy VII (Disc 2).cue", "Final Fantasy VII (Disc 3).cue"),
            File(pasta, "Discs.txt").readLines().filter { it.isNotBlank() },
        )

        // POPS: converte os três, DISCS.TXT em cada pasta e o cartão do disco 1 nos outros
        val pp = OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.POPS)
        assertEquals(3, pp.conversoes)
        assertTrue(pp.executar(true, semProgresso).isEmpty())
        for (n in 1..3) {
            assertTrue(File(dir, "POPS/Final Fantasy VII (Disc $n).VCD").exists())
            assertEquals(
                listOf("Final Fantasy VII (Disc 1).VCD", "Final Fantasy VII (Disc 2).VCD", "Final Fantasy VII (Disc 3).VCD"),
                File(dir, "POPS/Final Fantasy VII (Disc $n)/DISCS.TXT").readLines().filter { it.isNotBlank() },
            )
        }
        assertFalse(File(dir, "POPS/Final Fantasy VII (Disc 1)/VMCDIR.TXT").exists())
        assertEquals("Final Fantasy VII (Disc 1)", File(dir, "POPS/Final Fantasy VII (Disc 3)/VMCDIR.TXT").readText())

        // Já no lugar e certo: nada a fazer
        assertTrue(OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.MANTER).vazio)
        // Se o DISCS.TXT sumir, o organizador refaz
        File(dir, "POPS/Final Fantasy VII (Disc 2)/DISCS.TXT").delete()
        val conserto = OrganizadorPs1(raiz).planejar(Catalogo.varrer(raiz, null), OrganizadorPs1.Destino.MANTER)
        assertEquals(1, conserto.acoes.size)
        conserto.executar(false, semProgresso)
        assertTrue(File(dir, "POPS/Final Fantasy VII (Disc 2)/DISCS.TXT").exists())
    }

    @Test
    fun verificador() {
        File(dir, "POPS").mkdirs()
        File(dir, "POPS/POPS.ELF").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1))
        File(dir, "POPS/POPSTARTER.ELF").writeBytes(ByteArray(10)) // não é ELF
        File(dir, "EMBER").mkdirs()
        File(dir, "EMBER/bios.bin").writeBytes(ByteArray(1000)) // tamanho errado
        val r = Verificador.verificar(LocalFile.root(dir), null).associateBy { it.caminho }
        assertEquals(Verificador.Estado.OK, r["POPS/POPS.ELF"]!!.estado)
        assertEquals(Verificador.Estado.FALTA, r["POPS/IOPRP252.IMG"]!!.estado)
        assertEquals(Verificador.Estado.ERRADO, r["POPS/POPSTARTER.ELF"]!!.estado)
        assertEquals(Verificador.Estado.ERRADO, r["EMBER/bios.bin"]!!.estado)
        assertEquals(Verificador.Estado.FALTA, r["EMBER/ember.elf"]!!.estado)
        assertEquals(Verificador.Estado.OPCIONAL, r["POPS/BIOS.BIN"]!!.estado)
    }

    @Test
    fun discos() {
        assertEquals("Final Fantasy VII" to 2, Ps1.disco("Final Fantasy VII (Disc 2)"))
        assertEquals("Metal Gear Solid (USA)" to 1, Ps1.disco("Metal Gear Solid (USA) (Disc 1)"))
        assertEquals("MGS" to 2, Ps1.disco("MGS_CD2"))
        assertEquals("Parasite Eve" to 1, Ps1.disco("Parasite Eve [Disco 1 de 2]"))
        assertEquals("Crash Bandicoot" to null, Ps1.disco("Crash Bandicoot"))
    }
}
