package br.ps2ponte.core.smb

import br.ps2ponte.core.EventLog
import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.smb.OplClient.Companion.CAP_LARGE_FILES
import br.ps2ponte.core.smb.OplClient.Companion.CAP_LARGE_READX
import br.ps2ponte.core.smb.OplClient.Companion.CAP_LARGE_WRITEX
import br.ps2ponte.core.smb.OplClient.Companion.CAP_NT_SMBS
import br.ps2ponte.core.smb.OplClient.Companion.CAP_STATUS32
import br.ps2ponte.core.smb.OplClient.Companion.CAP_UNICODE
import br.ps2ponte.core.smb.OplClient.Companion.EACCES
import br.ps2ponte.core.smb.OplClient.Companion.EIO
import br.ps2ponte.core.smb.OplClient.Companion.HASHED_PASSWORD
import br.ps2ponte.core.smb.OplClient.Companion.NO_PASSWORD
import br.ps2ponte.core.smb.OplClient.Companion.O_CREAT
import br.ps2ponte.core.smb.OplClient.Companion.O_RDONLY
import br.ps2ponte.core.smb.OplClient.Companion.O_RDWR
import br.ps2ponte.core.smb.OplClient.Companion.O_TRUNC
import br.ps2ponte.core.smb.OplClient.Companion.O_WRONLY
import br.ps2ponte.core.smb.OplClient.Companion.SMB_COM_CREATE_DIRECTORY
import br.ps2ponte.core.smb.OplClient.Companion.SMB_COM_DELETE_DIRECTORY
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.EnumSet
import kotlin.random.Random

/**
 * O servidor contra os clientes do OPL reproduzidos em [OplClient]. Cada teste leva o nome da função C
 * que imita (smbman do ps2sdk = menu; cdvdman = jogo) e confere os campos que o C lê.
 */
class OplSmbTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var root: File
    private var server: SmbServer? = null
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val stats = TransferStats()
    private val clients = ArrayList<AutoCloseable>()
    private val gameA = Random(1).nextBytes(300 * 1024 + 123)
    private val gameB = Random(2).nextBytes(10 * 1024)

    @BeforeEach
    fun prepara() {
        root = File(tmp, "PS2SMB")
        File(root, "DVD/sub").mkdirs()
        File(root, "CD").mkdirs()
        File(root, "CFG").mkdirs()
        File(root, "VMC").mkdirs()
        File(root, "DVD/Jogo A.iso").writeBytes(gameA)
        File(root, "DVD/Jogo B.iso").writeBytes(gameB)
        File(root, "DVD/leia.txt").writeText("nao e iso")
        File(root, "VMC/slot0.bin").writeBytes(ByteArray(16 * 1024))
    }

    @AfterEach
    fun encerra() {
        clients.forEach { it.close() }
        server?.close()
    }

    private fun start(readOnly: Boolean = false, unicode: Boolean = true): SmbServer {
        val s = SmbServer(LocalTree(root, readOnly), port = 0, log = EventLog { logs += it }, stats = stats, unicode = unicode)
        s.start()
        server = s
        return s
    }

    private fun client() = OplClient(server!!.localPort).also { clients += it }

    /** smb_LogOn + eco de teste + smb_OpenShare, na ordem do ethSMBConnect (ethsupport.c). */
    private fun menuLogon(c: OplClient, share: String = "PS2SMB"): Pair<Int, Int> {
        val caps = c.smb_NegotiateProtocol()
        assertTrue(caps >= 0)
        val uid = c.smb_SessionSetupAndX("guest", "senha", HASHED_PASSWORD, caps)
        assertTrue(uid > 0, "UID $uid")
        assertEquals(0, c.smb_Echo("ALIVE ECHO TEST".toByteArray()))
        val tid = c.smb_TreeConnectAndX(uid, "\\\\127.0.0.1\\$share", "senha", HASHED_PASSWORD)
        assertTrue(tid > 0, "TID $tid")
        return uid to tid
    }

    /** DeviceFSInit do cdvdman: sessão, compartilhamento. */
    private fun gameLogon(c: OplClient, password: String = "senha", share: String = "PS2SMB") {
        val caps = c.cdvdman_NegotiateProtocol(password)
        assertTrue(caps >= 0)
        assertEquals(1, c.cdvdman_SessionSetupAndX("guest", caps))
        assertEquals(1, c.cdvdman_TreeConnectAndX("\\\\127.0.0.1\\$share"))
    }

    private fun sparse(f: File, size: Long, marker: ByteArray, at: Long) {
        Files.newByteChannel(f.toPath(), EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)).use { ch ->
            ch.position(at)
            ch.write(ByteBuffer.wrap(marker))
            if (ch.size() < size) {
                ch.position(size - 1)
                ch.write(ByteBuffer.wrap(byteArrayOf(0)))
            }
        }
    }

    // ------------------------------------------------------------------ negociação e sessão

    @Test
    fun smb_NegotiateProtocol() {
        start()
        val c = client()
        val caps = c.smb_NegotiateProtocol()
        assertEquals(0, c.status())
        assertEquals(17, c.r8(32))
        assertEquals(0, c.r16(33), "DialectIndex de \"NT LM 0.12\"")
        val sc = c.Capabilities
        for (bit in listOf(CAP_UNICODE, CAP_LARGE_FILES, CAP_NT_SMBS, CAP_STATUS32, CAP_LARGE_READX, CAP_LARGE_WRITEX)) {
            assertTrue((sc and bit) != 0L, "falta capacidade 0x${bit.toString(16)}")
        }
        assertEquals(0L, sc and 0x80000000L, "segurança estendida não pode ser oferecida")
        assertEquals(0L, sc and 0x1L, "sem modo raw")
        assertEquals(sc and (CAP_LARGE_WRITEX or CAP_LARGE_READX or CAP_UNICODE or CAP_LARGE_FILES or CAP_STATUS32), caps)
        assertEquals(1, c.SecurityMode, "nível usuário")
        assertEquals(1, c.PasswordType, "desafio/resposta")
        assertEquals(8, c.r8(66), "EncryptionKeyLength")
        assertEquals("WORKGROUP", c.PrimaryDomainServerName)
        assertTrue(c.MaxBufferSize >= 4096)
        assertTrue(c.MaxMpxCount >= 2)
    }

    @Test
    fun cdvdman_smb_NegotiateProtocol_semStatus32() {
        start()
        val c = client()
        assertTrue(c.cdvdman_NegotiateProtocol("") >= 0)
        assertEquals(0, c.r16s(5), "Eclass")
        assertEquals(17, c.r8(32))
        // o jogo copia 64 bytes crus do nome do domínio (UTF-16 com CAP_UNICODE)
        assertEquals('W'.code.toByte(), c.rawDomain[0])
        assertEquals(0.toByte(), c.rawDomain[1])
    }

    @Test
    fun smb_SessionSetupAndX_semSenhaEComSenha() {
        start()
        val c1 = client()
        val caps = c1.smb_NegotiateProtocol()
        assertTrue(c1.smb_SessionSetupAndX("", null, NO_PASSWORD, caps) > 0)  // convidado sem senha
        val c2 = client()
        val caps2 = c2.smb_NegotiateProtocol()
        val uid = c2.smb_SessionSetupAndX("qualquer", "x", HASHED_PASSWORD, caps2)
        assertTrue(uid > 0)
        assertEquals(0, c2.status(), "aceita na primeira tentativa (NTLM), sem cair no LM")
    }

    @Test
    fun cdvdman_smb_SessionSetupAndX_e_TreeConnectAndX() {
        start()
        val c = client()
        gameLogon(c, password = "")
        assertTrue(c.gameUid > 0)
        assertTrue(c.gameTid > 0)
        val c2 = client()
        gameLogon(c2, password = "senha", share = "OUTRO_NOME")
        assertTrue(c2.gameTid > 0)
    }

    @Test
    fun smb_TreeConnectAndX_qualquerNome() {
        start()
        val c = client()
        val (uid, _) = menuLogon(c, "PS2SMB")
        val tid2 = c.smb_TreeConnectAndX(uid, "\\\\127.0.0.1\\jogos", null, NO_PASSWORD)
        assertTrue(tid2 > 0)
        val info = OplClient.PathInformation()
        assertEquals(0, c.smb_QueryPathInformation(uid, tid2, info, "\\DVD"))
        assertEquals(1, info.IsDirectory)
    }

    @Test
    fun smb_NetShareEnum_listaUmCompartilhamento() {
        start()
        val c = client()
        val caps = c.smb_NegotiateProtocol()
        val uid = c.smb_SessionSetupAndX("guest", null, NO_PASSWORD, caps)
        val shares = c.smb_GetShareList(uid, "127.0.0.1")
        assertNotNull(shares)
        assertEquals(listOf("PS2SMB"), shares!!.map { it.name })
        assertEquals("PS2 Ponte", shares[0].comment)
        // a mesma resposta lida como no C: status RAP em ParamOffset (par), disponíveis em +6
        val tid = c.smb_TreeConnectAndX(uid, "\\\\127.0.0.1\\IPC$", null, 0)
        assertEquals(1, c.smb_NetShareEnum(uid, tid, ArrayList(), 0, 0))
        assertEquals(0, c.r16(41) % 4, "ParamOffset alinhado")
        assertEquals(0, c.r16(47) % 4, "DataOffset alinhado")
    }

    @Test
    fun smb_Echo_keepalive() {
        start()
        val c = client()
        menuLogon(c)
        // o keepalive_thread manda com TID/UID zerados e Flags2 = 0
        assertEquals(0, c.smb_Echo("PS2 KEEPALIVE ECHO".toByteArray()))
        assertEquals(1, c.r16(33), "SequenceNumber")
    }

    @Test
    fun smb_LogOffAndX_e_TreeDisconnect() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        assertEquals(0, c.smb_TreeDisconnect(uid, tid))
        assertTrue(c.smb_TreeDisconnect(uid, tid) < 0, "TID já desconectado")
        assertEquals(0, c.smb_LogOffAndX(uid))
    }

    @Test
    fun smb_QueryInformationDisk() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val d = c.smb_QueryInformationDisk(uid, tid)
        assertNotNull(d)
        assertEquals(512, d!!.blockSize)
        assertTrue(d.totalUnits > 0)
    }

    // ------------------------------------------------------------------ pastas

    @Test
    fun smb_QueryPathInformation() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val raiz = OplClient.PathInformation()
        assertEquals(0, c.smb_QueryPathInformation(uid, tid, raiz, "\\"))
        assertEquals(1, raiz.IsDirectory)
        assertTrue((raiz.FileAttributes and 0x10) != 0L)

        val iso = OplClient.PathInformation()
        assertEquals(0, c.smb_QueryPathInformation(uid, tid, iso, "\\dvd\\JOGO A.ISO"))  // sem diferenciar maiúsculas
        assertEquals(gameA.size.toLong(), iso.EndOfFile)
        assertEquals(0, iso.IsDirectory)
        assertEquals(fileTime(File(root, "DVD/Jogo A.iso").lastModified()), iso.Change)
        assertEquals(-EIO, c.smb_QueryPathInformation(uid, tid, OplClient.PathInformation(), "\\DVD\\nao existe.iso"))
        assertEquals(0xC0000034L.toInt(), c.status(), "STATUS_OBJECT_NAME_NOT_FOUND")
    }

    @Test
    fun smb_FindFirstNext2_comoSmbDread() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val list = c.listDir(uid, tid, "\\DVD")!!
        assertEquals(listOf(".", "..", "Jogo A.iso", "Jogo B.iso", "leia.txt", "sub"), list.map { it.FileName })
        val a = list.first { it.FileName == "Jogo A.iso" }
        assertEquals(gameA.size.toLong(), a.fileInfo.EndOfFile)
        assertEquals(0, a.fileInfo.IsDirectory)
        assertEquals(1, list.first { it.FileName == "sub" }.fileInfo.IsDirectory)
        assertTrue(list.last().EOS == 1, "EndOfSearch na última entrada")
        // a busca foi fechada (CLOSE_SEARCH_IF_EOS): o próximo FIND_NEXT2 falha e o dread termina
        val again = OplClient.SearchInfo().also { it.SID = list[0].SID }
        assertTrue(c.smb_FindFirstNext2(uid, tid, null, OplClient.TRANS2_FIND_NEXT2, again) < 0)

        val vazia = c.listDir(uid, tid, "\\CD")!!
        assertEquals(listOf(".", ".."), vazia.map { it.FileName })
        assertTrue(c.lastLength <= OplClient.MAX_SMB_BUF + OplClient.MAX_SMB_BUF_HDR)
    }

    @Test
    fun smb_FindFirstNext2_mascaraIso() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val names = ArrayList<String>()
        val info = OplClient.SearchInfo()
        var r = c.smb_FindFirstNext2(uid, tid, "\\dvd\\*.ISO", OplClient.TRANS2_FIND_FIRST2, info)
        while (r == 0) {
            names += info.FileName
            r = c.smb_FindFirstNext2(uid, tid, null, OplClient.TRANS2_FIND_NEXT2, info)
        }
        assertEquals(listOf("Jogo A.iso", "Jogo B.iso"), names)
        val none = OplClient.SearchInfo()
        assertEquals(-EIO, c.smb_FindFirstNext2(uid, tid, "\\DVD\\*.zso", OplClient.TRANS2_FIND_FIRST2, none))
        assertEquals(0xC000000FL.toInt(), c.status(), "STATUS_NO_SUCH_FILE")
    }

    @Test
    fun smb_ManageDirectory_Delete_Rename() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        assertEquals(0, c.smb_ManageDirectory(uid, tid, "\\ART", SMB_COM_CREATE_DIRECTORY))
        assertTrue(File(root, "ART").isDirectory)
        assertTrue(c.smb_ManageDirectory(uid, tid, "\\ART", SMB_COM_CREATE_DIRECTORY) < 0, "já existe")
        assertEquals(0, c.smb_Rename(uid, tid, "\\DVD\\Jogo B.iso", "\\DVD\\Jogo C.iso"))
        assertTrue(File(root, "DVD/Jogo C.iso").isFile)
        assertFalse(File(root, "DVD/Jogo B.iso").exists())
        assertEquals(0, c.smb_Rename(uid, tid, "\\DVD\\Jogo C.iso", "\\DVD\\JOGO C.iso"))  // só maiúsculas
        assertTrue(root.resolve("DVD").list()!!.contains("JOGO C.iso"))
        assertEquals(0, c.smb_Delete(uid, tid, "\\DVD\\JOGO C.iso"))
        assertFalse(File(root, "DVD/JOGO C.iso").exists())
        assertEquals(-EIO, c.smb_Delete(uid, tid, "\\DVD\\JOGO C.iso"))
        assertEquals(0, c.smb_ManageDirectory(uid, tid, "\\ART", SMB_COM_DELETE_DIRECTORY))
        assertFalse(File(root, "ART").exists())
        assertTrue(c.smb_ManageDirectory(uid, tid, "\\DVD", SMB_COM_DELETE_DIRECTORY) < 0, "pasta não vazia")
    }

    // ------------------------------------------------------------------ arquivos (menu)

    @Test
    fun smb_NTCreateAndX_e_smb_ReadFile() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val size = LongArray(1)
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\Jogo A.iso", size, O_RDONLY)  // CAP_NT_SMBS: vai por NT_CREATE_ANDX
        assertTrue(fid > 0)
        assertEquals(gameA.size.toLong(), size[0])
        val data = ByteArray(gameA.size)
        assertEquals(data.size, c.smb_ReadFile(uid, tid, fid, 0, data, data.size))  // pedidos de 65535
        assertArrayEquals(gameA, data)
        val part = ByteArray(1000)
        assertEquals(1000, c.smb_ReadAndX(uid, tid, fid, 70_001, part, 0, 1000))
        assertArrayEquals(gameA.copyOfRange(70_001, 71_001), part)
        assertEquals(0, c.smb_ReadAndX(uid, tid, fid, gameA.size.toLong(), part, 0, 1000), "no fim: 0 bytes, sem erro")
        assertEquals(0, c.smb_Close(uid, tid, fid))
        assertTrue(c.smb_Close(uid, tid, fid) < 0, "FID já fechado")
        assertTrue(stats.bytesRead.get() >= gameA.size)
        assertTrue(logs.contains("SMB: 127.0.0.1 abriu \\DVD\\Jogo A.iso"), logs.toString())
    }

    @Test
    fun smb_OpenAndX_semNtSmbs() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        c.Capabilities = c.Capabilities and CAP_NT_SMBS.inv()  // caminho de NAS antigos: OPEN_ANDX + QUERY_PATH
        val size = LongArray(1)
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\Jogo B.iso", size, O_RDONLY)
        assertTrue(fid > 0)
        assertEquals(gameB.size.toLong(), size[0])
        val data = ByteArray(gameB.size)
        assertEquals(data.size, c.smb_ReadFile(uid, tid, fid, 0, data, data.size))
        assertArrayEquals(gameB, data)
        assertEquals(0, c.smb_Close(uid, tid, fid))
        // criar com OPEN_ANDX (O_CREAT|O_TRUNC -> OpenMode 0x12)
        val fid2 = c.smb_OpenAndX(uid, tid, "\\CFG\\novo.cfg", size, O_WRONLY or O_CREAT or O_TRUNC)
        assertTrue(fid2 > 0)
        assertEquals(5, c.smb_WriteFile(uid, tid, fid2, 0, "a=1\r\n".toByteArray(), 5))
        assertEquals(0, c.smb_Close(uid, tid, fid2))
        assertEquals("a=1\r\n", File(root, "CFG/novo.cfg").readText())
    }

    @Test
    fun smb_WriteFile_configEVmc() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val size = LongArray(1)
        // configuração: fopen("w") = O_WRONLY|O_CREAT|O_TRUNC -> disposição 5
        File(root, "CFG/SLUS_123.45.cfg").writeText("antigo antigo antigo")
        val fid = c.smb_OpenAndX(uid, tid, "\\CFG\\SLUS_123.45.cfg", size, O_WRONLY or O_CREAT or O_TRUNC)
        assertTrue(fid > 0)
        val cfg = "\$Compatibility=1\r\n".toByteArray()
        assertEquals(cfg.size, c.smb_WriteFile(uid, tid, fid, 0, cfg, cfg.size))
        assertEquals(0, c.smb_Close(uid, tid, fid))
        assertArrayEquals(cfg, File(root, "CFG/SLUS_123.45.cfg").readBytes())

        // criação de VMC: grande, em pedidos de 65535
        val vmc = Random(3).nextBytes(200_000)
        val fid2 = c.smb_OpenAndX(uid, tid, "\\VMC\\novo.bin", size, O_RDWR or O_CREAT or O_TRUNC)
        assertTrue(fid2 > 0)
        assertEquals(vmc.size, c.smb_WriteFile(uid, tid, fid2, 0, vmc, vmc.size))
        val back = ByteArray(vmc.size)
        assertEquals(vmc.size, c.smb_ReadFile(uid, tid, fid2, 0, back, back.size))
        assertArrayEquals(vmc, back)
        assertEquals(0, c.smb_Close(uid, tid, fid2))
        assertArrayEquals(vmc, File(root, "VMC/novo.bin").readBytes())
        assertTrue(stats.bytesWritten.get() >= vmc.size + cfg.size)

        // O_CREAT sem O_TRUNC (disposição 3) não apaga o conteúdo
        val fid3 = c.smb_OpenAndX(uid, tid, "\\VMC\\novo.bin", size, O_RDWR or O_CREAT)
        assertEquals(vmc.size.toLong(), size[0])
        assertEquals(0, c.smb_Close(uid, tid, fid3))
    }

    @Test
    fun arquivoMaiorQue4GiB_menuEJogo() {
        val big = 5L shl 30
        val marker = Random(4).nextBytes(64 * 1024)
        val at = big - 100_000  // a partir de 5 GiB - 100000: acima de 4 GiB
        sparse(File(root, "DVD/Grande.iso"), big, marker, at)
        start()

        val c = client()
        val (uid, tid) = menuLogon(c)
        val list = c.listDir(uid, tid, "\\DVD")!!
        assertEquals(big, list.first { it.FileName == "Grande.iso" }.fileInfo.EndOfFile)
        val info = OplClient.PathInformation()
        assertEquals(0, c.smb_QueryPathInformation(uid, tid, info, "\\DVD\\Grande.iso"))
        assertEquals(big, info.EndOfFile)
        val size = LongArray(1)
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\Grande.iso", size, O_RDWR)
        assertEquals(big, size[0])
        val got = ByteArray(marker.size)
        assertEquals(marker.size, c.smb_ReadFile(uid, tid, fid, at, got, got.size))  // OffsetHigh = 1
        assertArrayEquals(marker, got)
        // escrita acima de 4 GiB (WRITE_ANDX com OffsetHigh)
        val patch = Random(5).nextBytes(3000)
        val wat = (4L shl 30) + 12345
        assertEquals(patch.size, c.smb_WriteFile(uid, tid, fid, wat, patch, patch.size))
        assertEquals(0, c.smb_Close(uid, tid, fid))
        RandomAccessFile(File(root, "DVD/Grande.iso"), "r").use { raf ->
            val b = ByteArray(patch.size)
            raf.seek(wat); raf.readFully(b)
            assertArrayEquals(patch, b)
        }

        // jogo: smb_ReadCD(lsn) com offsetlow = lsn*2048 (32 bits) e offsethigh = lsn >> 21
        val g = client()
        gameLogon(g)
        val gfid = IntArray(1)
        assertEquals(1, g.cdvdman_OpenAndX("\\DVD\\Grande.iso", gfid, false))
        val lsn = at / 2048 + 1
        val buf = ByteArray(16 * 2048)
        assertEquals(buf.size, g.cdvdman_ReadCD(gfid[0], lsn, 16, buf))
        val off = (lsn * 2048 - at).toInt()
        assertArrayEquals(marker.copyOfRange(off, off + buf.size), buf)
        assertEquals(0, g.cdvdman_Close(gfid[0]))
    }

    // ------------------------------------------------------------------ jogo (cdvdman)

    @Test
    fun cdvdman_DeviceFSInit_e_smb_ReadFile() {
        start()
        val c = client()
        gameLogon(c)
        val fid = IntArray(1)
        assertEquals(1, c.cdvdman_OpenAndX("\\DVD\\Jogo A.iso", fid, false))
        assertTrue(fid[0] > 0)
        val data = ByteArray(gameA.size)
        assertEquals(data.size, c.cdvdman_ReadFile(fid[0], 0, 0, data, data.size))  // pedidos de 8192
        assertArrayEquals(gameA, data)
        val m = c.lastReadMessage
        assertEquals(64, (m[49].toInt() and 0xFF) + 4, "dados em DataOffset 60 (+4 da sessão)")
        assertEquals(12, m[4 + 32].toInt(), "WordCount do READ_ANDX")
        val sectors = ByteArray(4 * 2048)
        assertEquals(sectors.size, c.cdvdman_ReadCD(fid[0], 3, 4, sectors))
        assertArrayEquals(gameA.copyOfRange(3 * 2048, 7 * 2048), sectors)
        assertEquals(0, c.cdvdman_Close(fid[0]))
        assertTrue(logs.contains("SMB: 127.0.0.1 abriu \\DVD\\Jogo A.iso"))
    }

    @Test
    fun cdvdman_smb_WriteFile_vmc() {
        start()
        val c = client()
        gameLogon(c)
        val fid = IntArray(1)
        assertEquals(1, c.cdvdman_OpenAndX("VMC\\slot0.bin", fid, true))  // como smb_vmc_infos.fname
        val page = Random(6).nextBytes(528)
        assertEquals(page.size, c.cdvdman_WriteAndX(fid[0], 4096, 0, page, 0, page.size))
        val back = ByteArray(page.size)
        assertEquals(page.size, c.cdvdman_ReadAndX(fid[0], 4096, 0, back, 0, page.size))
        assertArrayEquals(page, back)
        assertEquals(0, c.cdvdman_Close(fid[0]))
        val disk = File(root, "VMC/slot0.bin").readBytes()
        assertArrayEquals(page, disk.copyOfRange(4096, 4096 + page.size))
        assertEquals(16 * 1024, disk.size)
    }

    // ------------------------------------------------------------------ somente leitura, transporte, nomes

    @Test
    fun somenteLeitura_negaEscritas() {
        start(readOnly = true)
        val c = client()
        val (uid, tid) = menuLogon(c)
        val size = LongArray(1)
        assertEquals(-EACCES, c.smb_OpenAndX(uid, tid, "\\CFG\\x.cfg", size, O_WRONLY or O_CREAT or O_TRUNC))
        assertEquals(-EACCES, c.smb_OpenAndX(uid, tid, "\\DVD\\Jogo A.iso", size, O_RDWR))
        assertEquals(-EACCES, c.smb_ManageDirectory(uid, tid, "\\ART", SMB_COM_CREATE_DIRECTORY))
        assertTrue(c.smb_Delete(uid, tid, "\\DVD\\Jogo B.iso") < 0)
        assertEquals(-EACCES, c.smb_Rename(uid, tid, "\\DVD\\Jogo B.iso", "\\DVD\\x.iso"))
        assertTrue(File(root, "DVD/Jogo B.iso").exists())
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\Jogo B.iso", size, O_RDONLY)
        assertTrue(fid > 0)
        assertEquals(0, c.smb_Close(uid, tid, fid))
        val g = client()
        gameLogon(g)
        assertEquals(-1000, g.cdvdman_OpenAndX("\\VMC\\slot0.bin", IntArray(1), true), "VMC sem escrita")
    }

    @Test
    fun sessaoNetbios_0x81_e_keepalive_0x85() {
        start()
        Socket("127.0.0.1", server!!.localPort).use { s ->
            s.soTimeout = 5000
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            val names = ByteArray(68) { 0x41 }
            o.write(byteArrayOf(0x81.toByte(), 0, 0, names.size.toByte()) + names)
            val resp = ByteArray(4)
            i.readFully(resp)
            assertArrayEquals(byteArrayOf(0x82.toByte(), 0, 0, 0), resp)
            o.write(byteArrayOf(0x85.toByte(), 0, 0, 0))  // keepalive: sem resposta
            // NEGOTIATE em seguida funciona
            val neg = ByteArray(36 + 11)
            neg[0] = 0xFF.toByte(); neg[1] = 'S'.code.toByte(); neg[2] = 'M'.code.toByte(); neg[3] = 'B'.code.toByte()
            neg[4] = 0x72; neg[32] = 0; neg[33] = 12; neg[35] = 2
            "NT LM 0.12".toByteArray().copyInto(neg, 36)
            o.write(byteArrayOf(0, 0, 0, neg.size.toByte()) + neg)
            i.readFully(resp)
            assertEquals(0, resp[0].toInt())
            val body = ByteArray(((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF))
            i.readFully(body)
            assertEquals(0x72, body[4].toInt())
            assertEquals(17, body[32].toInt())
        }
    }

    /** Nenhum cliente do OPL encadeia, mas clientes Windows mandam SESSION_SETUP + TREE_CONNECT juntos. */
    @Test
    fun andx_sessionSetupMaisTreeConnect() {
        start()
        Socket("127.0.0.1", server!!.localPort).use { s ->
            s.soTimeout = 5000
            val i = DataInputStream(s.getInputStream())
            fun send(m: ByteArray) {
                s.getOutputStream().write(byteArrayOf(0, (m.size ushr 16).toByte(), (m.size ushr 8).toByte(), m.size.toByte()) + m)
            }
            fun recv(): ByteBuffer {
                val h = ByteArray(4); i.readFully(h)
                val b = ByteArray(((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF))
                i.readFully(b)
                return ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }
            fun header(cmd: Int, tid: Int = 0, uid: Int = 0): ByteBuffer = ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put(byteArrayOf(0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()))
                put(cmd.toByte()); putInt(0); put(0x18); putShort(0x4001); put(ByteArray(12)); putShort(tid.toShort())
                putShort(1); putShort(uid.toShort()); putShort(7)
            }
            val neg = header(0x72).apply { put(0); putShort(12); put(2); put("NT LM 0.12\u0000".toByteArray()) }
            send(neg.array().copyOf(neg.position()))
            recv()
            val m = header(0x73)
            m.put(13); m.put(0x75); m.put(0); val andxPos = m.position(); m.putShort(0)
            m.putShort(16644); m.putShort(2); m.putShort(1); m.putInt(0); m.putShort(0); m.putShort(0); m.putInt(0); m.putInt(0x40)
            val b1 = "ps2\u0000WORKGROUP\u0000OS\u0000LM\u0000".toByteArray()
            m.putShort(b1.size.toShort()); m.put(b1)
            m.putShort(andxPos, m.position().toShort())
            m.put(4); m.put(0xFF.toByte()); m.put(0); m.putShort(0); m.putShort(0); m.putShort(1)
            val b2 = "\u0000\\\\127.0.0.1\\PS2SMB\u0000?????\u0000".toByteArray()
            m.putShort(b2.size.toShort()); m.put(b2)
            send(m.array().copyOf(m.position()))
            val r = recv()
            assertEquals(0, r.getInt(5), "status")
            val uid = r.getShort(28).toInt() and 0xFFFF
            val tid = r.getShort(24).toInt() and 0xFFFF
            assertTrue(uid > 0 && tid > 0)
            assertEquals(3, r.get(32).toInt(), "WordCount do SESSION_SETUP")
            assertEquals(0x75, r.get(33).toInt() and 0xFF, "AndXCommand aponta para o TREE_CONNECT")
            val next = r.getShort(35).toInt()
            assertTrue(r.get(next).toInt() == 3 || r.get(next).toInt() == 7, "bloco do TREE_CONNECT")
            assertEquals(0xFF, r.get(next + 1).toInt() and 0xFF)
            // o TID devolvido vale para o pedido seguinte
            val td = header(0x71, tid, uid).apply { put(0); putShort(0) }
            send(td.array().copyOf(td.position()))
            assertEquals(0, recv().getInt(5))
        }
    }

    @Test
    fun semUnicode_nomesUtf8VoltamIguais() {
        File(root, "DVD/Ação Total.iso").writeBytes(gameB)
        start(unicode = false)
        val c = client()
        val (uid, tid) = menuLogon(c)
        assertFalse(c.unicode)
        val names = c.listDir(uid, tid, "\\DVD")!!.map { it.FileName }
        assertTrue(names.contains("Ação Total.iso"), names.toString())
        val size = LongArray(1)
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\Ação Total.iso", size, O_RDONLY)
        assertTrue(fid > 0)
        assertEquals(gameB.size.toLong(), size[0])
        assertEquals(0, c.smb_Close(uid, tid, fid))
        val g = client()
        gameLogon(g)
        assertEquals(1, g.cdvdman_OpenAndX("\\DVD\\Ação Total.iso", IntArray(1), false))
    }

    @Test
    fun unicode_nomeComInterrogacaoAbreOArquivo() {
        File(root, "DVD/Ação Total.iso").writeBytes(gameB)
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val names = c.listDir(uid, tid, "\\DVD")!!.map { it.FileName }
        assertTrue(names.contains("A??o Total.iso"), names.toString())  // utf16ToAscii troca não-ASCII por '?'
        val size = LongArray(1)
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\A??o Total.iso", size, O_RDONLY)
        assertTrue(fid > 0)
        assertEquals(gameB.size.toLong(), size[0])
        assertEquals(0, c.smb_Close(uid, tid, fid))
        val g = client()
        gameLogon(g)
        val gfid = IntArray(1)
        assertEquals(1, g.cdvdman_OpenAndX("\\DVD\\A??o Total.iso", gfid, false))
        val data = ByteArray(gameB.size)
        assertEquals(data.size, g.cdvdman_ReadFile(gfid[0], 0, 0, data, data.size))
        assertArrayEquals(gameB, data)
    }

    @Test
    fun caminhoForaDaRaiz_negado() {
        start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        assertEquals(-EACCES, c.smb_QueryPathInformation(uid, tid, OplClient.PathInformation(), "\\..\\..\\Windows"))
        assertEquals(-EACCES, c.smb_OpenAndX(uid, tid, "\\DVD\\..\\..\\x", LongArray(1), O_RDONLY))
        val none = OplClient.SearchInfo()
        assertEquals(-EACCES, c.smb_FindFirstNext2(uid, tid, "\\..\\*", OplClient.TRANS2_FIND_FIRST2, none))
    }

    @Test
    fun closeLiberaPortaEConexoes() {
        val s = start()
        val c = client()
        val (uid, tid) = menuLogon(c)
        val fid = c.smb_OpenAndX(uid, tid, "\\DVD\\Jogo A.iso", LongArray(1), O_RDWR)
        assertTrue(fid > 0)  // fica aberto: o close() do servidor tem que fechar
        s.close()
        s.close()  // idempotente
        server = null
        // a porta foi liberada: abre de novo na mesma porta
        val again = SmbServer(LocalTree(root), port = s.localPort)
        again.start()
        again.close()
        // os arquivos foram fechados: dá para apagar a pasta
        assertTrue(File(root, "DVD/Jogo A.iso").delete())
    }
}
