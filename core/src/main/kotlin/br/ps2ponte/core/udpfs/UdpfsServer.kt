package br.ps2ponte.core.udpfs

import br.ps2ponte.core.EventLog
import br.ps2ponte.core.NoLog
import br.ps2ponte.core.PonteServer
import br.ps2ponte.core.TransferStats
import br.ps2ponte.core.block.BlockDevice
import br.ps2ponte.core.fs.LocalTree
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.core.fs.VHandle
import br.ps2ponte.core.net.ReplyRouter
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Calendar

/**
 * UDPFS sobre UDPRDMA (porta 0xF5F6, serviço 0xF5F5): port fiel de `pc/udpfs_server.py` do Neutrino
 * (sem a descompressão de .zso/.cso/.chd).
 *
 * - [tree]: pasta servida no modo arquivo (`udpfs:` / NHDDL modo udpfs). null = desligado.
 * - [device]: dispositivo de bloco no handle 0 (`udpfs_bd.irx`). null = sem bloco.
 *
 * Uma thread atende a descoberta (porta fixa) e outra os dados (porta efêmera anunciada no INFORM),
 * como os dois sockets do servidor Python.
 */
class UdpfsServer(
    private val tree: LocalTree?,
    private val device: BlockDevice?,
    val port: Int = PORT,
    private val log: EventLog = NoLog,
    private val stats: TransferStats = TransferStats(),
    private val router: ReplyRouter = ReplyRouter(),
) : PonteServer {
    override val name = "UDPFS"

    @Volatile private var running = false
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var dsock: DatagramSocket? = null
    @Volatile private var lastPeer: String? = null

    // Estado do protocolo (só a thread de dados mexe)
    private var txSeq = 0
    private var txSeqAcked = 0
    private var rxSeqExpected = 0
    private var txBuffer = ArrayList<Pair<Int, ByteArray>>()

    private sealed class Handle {
        class FileH(val io: VHandle, val append: Boolean, var pos: Long = 0) : Handle()
        class DirH(val entries: List<VFile>, var index: Int = 0) : Handle()
        class BlockH(var pos: Long = 0) : Handle()
    }

    private val handles = HashMap<Int, Handle>()
    private var nextHandle = 1

    // Escrita em andamento (arquivo ou bloco)
    private var wHandle = -1
    private var wIsBlock = false
    private var wSector = 0L
    private var wSectorCount = 0
    private var wData = java.io.ByteArrayOutputStream()
    private var wTotalChunks = 0
    private var wReceived = 0

    override fun start() {
        val s = DatagramSocket(null)
        val d = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(port))
            d.broadcast = true
            d.receiveBufferSize = 1 shl 20
            d.sendBufferSize = 1 shl 20
            d.bind(InetSocketAddress(0))
        } catch (e: Exception) {
            s.close(); d.close()
            throw e
        }
        if (device != null) handles[BLOCK_HANDLE] = Handle.BlockH()
        sock = s
        dsock = d
        running = true
        Thread({ discoveryLoop(s, d) }, "udpfs-disc").apply { isDaemon = true; start() }
        Thread({ dataLoop(d) }, "udpfs-data").apply { isDaemon = true; start() }
    }

    override fun close() {
        running = false
        sock?.close(); sock = null
        dsock?.close(); dsock = null
    }

    // ---------------------------------------------------------------- laços

    private fun discoveryLoop(s: DatagramSocket, d: DatagramSocket) {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                p.setData(buf, 0, buf.size)
                s.receive(p)
                if (p.length < 6) continue
                val h = u16(buf, 0)
                if (h and 0xF != PT_DISCOVERY) continue
                if (u16(buf, 2) != SERVICE_ID) continue
                val addr = InetSocketAddress(p.address, p.port)
                val ip = p.address.hostAddress
                if (ip != lastPeer) {
                    lastPeer = ip
                    log.log("UDPFS: PS2 $ip conectado")
                }
                // INFORM sai do socket de dados: o PS2 aprende a porta pela porta de origem.
                val inform = ByteArray(6)
                putU16(inform, 0, PT_INFORM or (1 shl 4)) // seq do INFORM é sempre 1
                putU16(inform, 2, SERVICE_ID)
                putU16(inform, 4, d.localPort)
                sendTo(d, inform, addr)
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                log.log("UDPFS: erro na descoberta: ${e.message}")
            }
        }
    }

    private fun dataLoop(d: DatagramSocket) {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                p.setData(buf, 0, buf.size)
                d.soTimeout = 0
                d.receive(p)
                handleData(d, buf.copyOf(p.length), InetSocketAddress(p.address, p.port))
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                log.log("UDPFS: erro: ${e.message}")
            }
        }
        synchronized(handles) {
            for (h in handles.values) closeHandle(h)
            handles.clear()
        }
    }

    // ---------------------------------------------------------------- UDPRDMA

    private fun handleData(d: DatagramSocket, data: ByteArray, addr: InetSocketAddress) {
        if (data.size < 6) return
        val h = u16(data, 0)
        if (h and 0xF != PT_DATA) return
        val seq = (h shr 4) and 0xFFF
        val dh = u32(data, 2).toInt()
        val ack = dh and 0xFFF
        val flags = (dh shr 12) and 0x3
        val hdrSize = ((dh shr 14) and 0xF) * 4
        val payloadSize = hdrSize + ((dh shr 18) and 0x3FFF)
        val payload = if (payloadSize > 0) data.copyOfRange(6, minOf(data.size, 6 + payloadSize)) else ByteArray(0)

        if (flags and DF_ACK != 0) {
            txSeqAcked = ack
            pruneAcked(ack)
        }
        if (payloadSize == 0 && flags and DF_ACK != 0) return
        if (payloadSize == 0) { // NACK
            txSeqAcked = (ack - 1) and 0xFFF
            retransmitFrom(d, addr, ack)
            return
        }

        if (seq != rxSeqExpected) {
            val prev = (rxSeqExpected - 1) and 0xFFF
            if (seq == prev) {
                // Pedido repetido: re-ACK e reenvia a resposta
                sendAck(d, addr, true)
                if (txBuffer.isNotEmpty()) retransmitFrom(d, addr, txBuffer[0].first)
                return
            } else if (seq == 0) {
                // O PS2 recomeçou a conexão (ex.: reboot do IOP)
                txSeq = 0; txBuffer = ArrayList(); txSeqAcked = 0; rxSeqExpected = 0
            } else {
                sendAck(d, addr, false)
                return
            }
        }
        rxSeqExpected = (rxSeqExpected + 1) and 0xFFF

        // ACK imediato: o udprdma_send() do PS2 volta logo e espera a resposta com 5 s
        sendAck(d, addr, true)
        if (payload.isEmpty()) return

        when (payload[0].toInt() and 0xFF) {
            OPEN_REQ -> handleOpen(d, addr, payload)
            CLOSE_REQ -> handleClose(d, addr, payload)
            READ_REQ -> handleRead(d, addr, payload)
            WRITE_REQ -> handleWriteReq(d, addr, payload)
            WRITE_DATA -> handleWriteData(d, addr, payload, 0)
            LSEEK_REQ -> handleLseek(d, addr, payload)
            DREAD_REQ -> handleDread(d, addr, payload)
            GETSTAT_REQ -> handleGetstat(d, addr, payload)
            MKDIR_REQ -> handleMkdir(d, addr, payload)
            REMOVE_REQ -> handleRemove(d, addr, payload)
            RMDIR_REQ -> handleRmdir(d, addr, payload)
            BREAD_REQ -> handleBread(d, addr, payload)
            BWRITE_REQ -> handleBwriteReq(d, addr, payload)
            else -> {
                log.log("UDPFS: mensagem desconhecida 0x%02x".format(payload[0].toInt() and 0xFF))
                sendAck(d, addr, true)
            }
        }
    }

    private fun pruneAcked(ack: Int) {
        if (txBuffer.isEmpty()) return
        txBuffer = ArrayList(txBuffer.filter { ((it.first - ack - 1) and 0xFFF) < 2048 })
    }

    private fun sendAck(d: DatagramSocket, addr: InetSocketAddress, isAck: Boolean) {
        val p = ByteArray(6)
        putU16(p, 0, PT_DATA or (txSeq shl 4))
        val ackNr = if (isAck) (rxSeqExpected - 1) and 0xFFF else rxSeqExpected
        putU32(p, 2, dataHeader(ackNr, if (isAck) DF_ACK else 0, 0, 0))
        sendTo(d, p, addr)
    }

    /** Resposta curta num pacote só (sempre FIN), com confirmação. */
    private fun sendData(d: DatagramSocket, addr: InetSocketAddress, payload: ByteArray) {
        val padded = (payload.size + 3) and 3.inv()
        val p = ByteArray(6 + padded)
        putU16(p, 0, PT_DATA or (txSeq shl 4))
        putU32(p, 2, dataHeader((rxSeqExpected - 1) and 0xFFF, DF_ACK or DF_FIN, 0, padded))
        System.arraycopy(payload, 0, p, 6, payload.size)
        txBuffer = arrayListOf(txSeq to p)
        sendTo(d, p, addr)
        txSeq = (txSeq + 1) and 0xFFF
        waitForFinalAck(d, addr)
    }

    private fun sendDataPacket(d: DatagramSocket, addr: InetSocketAddress, header: ByteArray?, data: ByteArray, off: Int, len: Int, fin: Boolean) {
        val hdrSize = header?.size ?: 0
        val padded = (len + 3) and 3.inv()
        val p = ByteArray(6 + hdrSize + padded)
        putU16(p, 0, PT_DATA or (txSeq shl 4))
        putU32(p, 2, dataHeader((rxSeqExpected - 1) and 0xFFF, DF_ACK or (if (fin) DF_FIN else 0), hdrSize / 4, padded))
        if (header != null) System.arraycopy(header, 0, p, 6, hdrSize)
        System.arraycopy(data, off, p, 6 + hdrSize, len)
        txBuffer.add(txSeq to p)
        sendTo(d, p, addr)
        txSeq = (txSeq + 1) and 0xFFF
    }

    private fun retransmitFrom(d: DatagramSocket, addr: InetSocketAddress, fromSeq: Int) {
        for ((seq, p) in txBuffer) if (((seq - fromSeq) and 0xFFF) < 2048) sendTo(d, p, addr)
    }

    private fun inFlight() = (txSeq - txSeqAcked - 1) and 0xFFF

    /** Lê um ACK/NACK puro; pacotes com dados que chegarem durante a espera são descartados (como no Python). */
    private fun recvAckNack(d: DatagramSocket, timeoutMs: Int): Pair<Int, Boolean>? {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        d.soTimeout = timeoutMs
        while (true) {
            try {
                d.receive(p)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (p.length < 6) continue
            if (u16(buf, 0) and 0xF != PT_DATA) continue
            val dh = u32(buf, 2).toInt()
            if ((dh shr 18) and 0x3FFF != 0 || (dh shr 14) and 0xF != 0) continue
            return (dh and 0xFFF) to ((dh shr 12) and DF_ACK != 0)
        }
    }

    private fun waitForWindowAck(d: DatagramSocket, addr: InetSocketAddress) {
        try {
            while (true) {
                val (ack, isAck) = recvAckNack(d, WINDOW_ACK_TIMEOUT_MS) ?: run {
                    if (txBuffer.isNotEmpty()) retransmitFrom(d, addr, txBuffer[0].first)
                    return
                }
                if (isAck) {
                    txSeqAcked = ack
                    pruneAcked(ack)
                    return
                }
                txSeqAcked = (ack - 1) and 0xFFF
                retransmitFrom(d, addr, ack)
            }
        } finally {
            d.soTimeout = 0
        }
    }

    private fun waitForAck(d: DatagramSocket, addr: InetSocketAddress, timeoutMs: Int): Boolean {
        val finSeq = (txSeq - 1) and 0xFFF
        try {
            while (true) {
                val (ack, isAck) = recvAckNack(d, timeoutMs) ?: return false
                if (isAck) {
                    txSeqAcked = ack
                    pruneAcked(ack)
                    if (ack == finSeq) {
                        txBuffer = ArrayList()
                        return true
                    }
                } else {
                    retransmitFrom(d, addr, ack)
                }
            }
        } finally {
            d.soTimeout = 0
        }
    }

    private fun waitForFinalAck(d: DatagramSocket, addr: InetSocketAddress) {
        repeat(MAX_WINDOW_RETRIES + 1) {
            if (waitForAck(d, addr, WINDOW_ACK_TIMEOUT_MS)) return
            if (txBuffer.isEmpty()) return
            retransmitFrom(d, addr, txBuffer[0].first)
        }
    }

    /** RESULT_REPLY como cabeçalho de aplicação no 1º pacote + dados crus, com controle de fluxo. */
    private fun sendRawDataWithHeader(d: DatagramSocket, addr: InetSocketAddress, header: ByteArray, data: ByteArray, len: Int) {
        txBuffer = ArrayList()
        val maxChunk = optimalChunkSize(len)
        val first = minOf(minOf(maxChunk, MAX_PAYLOAD - header.size), len)
        sendDataPacket(d, addr, header, data, 0, first, fin = first >= len)
        var offset = first
        var windowRetries = 0
        while (offset < len) {
            if (inFlight() >= SEND_WINDOW) {
                val old = txSeqAcked
                waitForWindowAck(d, addr)
                if (txSeqAcked == old) {
                    if (++windowRetries >= MAX_WINDOW_RETRIES) {
                        log.log("UDPFS: PS2 não confirmou a janela; transferência abortada")
                        return
                    }
                } else windowRetries = 0
                continue
            }
            windowRetries = 0
            val n = minOf(maxChunk, len - offset)
            sendDataPacket(d, addr, null, data, offset, n, fin = offset + n >= len)
            offset += n
        }
        waitForFinalAck(d, addr)
    }

    private fun sendTo(d: DatagramSocket, p: ByteArray, addr: InetSocketAddress) {
        for (t in router.targets(addr)) d.send(DatagramPacket(p, p.size, t))
    }

    // ---------------------------------------------------------------- respostas

    private fun reply8(type: Int, value: Int): ByteArray = ByteArray(8).also { it[0] = type.toByte(); putU32(it, 4, value.toLong() and 0xFFFFFFFFL) }

    private fun sendOpenReply(d: DatagramSocket, addr: InetSocketAddress, handle: Int, st: Stat? = null) {
        val s = st ?: Stat.EMPTY
        val r = ByteArray(36)
        r[0] = OPEN_REPLY.toByte()
        putU32(r, 4, handle.toLong() and 0xFFFFFFFFL)
        putU32(r, 8, s.mode.toLong())
        putU32(r, 12, s.size and 0xFFFFFFFFL)
        putU32(r, 16, (s.size ushr 32) and 0xFFFFFFFFL)
        System.arraycopy(s.time, 0, r, 20, 8)
        System.arraycopy(s.time, 0, r, 28, 8)
        sendData(d, addr, r)
    }

    private fun statBytes(r: ByteArray, at: Int, s: Stat) {
        putU32(r, at, s.mode.toLong())
        putU32(r, at + 4, 0) // attr
        putU32(r, at + 8, s.size and 0xFFFFFFFFL)
        putU32(r, at + 12, (s.size ushr 32) and 0xFFFFFFFFL)
        System.arraycopy(s.time, 0, r, at + 16, 8)
        System.arraycopy(s.time, 0, r, at + 24, 8)
        System.arraycopy(s.time, 0, r, at + 32, 8)
    }

    private fun sendDreadReply(d: DatagramSocket, addr: InetSocketAddress, result: Int, name: String = "", st: Stat? = null) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val padded = if (result > 0) (nameBytes.size + 1 + 3) and 3.inv() else 0
        val r = ByteArray(48 + padded)
        r[0] = DREAD_REPLY.toByte()
        putU16(r, 2, nameBytes.size)
        putU32(r, 4, result.toLong() and 0xFFFFFFFFL)
        statBytes(r, 8, st ?: Stat.EMPTY)
        if (result > 0) System.arraycopy(nameBytes, 0, r, 48, nameBytes.size)
        sendData(d, addr, r)
    }

    private fun sendGetstatReply(d: DatagramSocket, addr: InetSocketAddress, result: Int, st: Stat? = null) {
        val r = ByteArray(48)
        r[0] = GETSTAT_REPLY.toByte()
        putU32(r, 4, result.toLong() and 0xFFFFFFFFL)
        statBytes(r, 8, st ?: Stat.EMPTY)
        sendData(d, addr, r)
    }

    private fun sendReadResult(d: DatagramSocket, addr: InetSocketAddress, result: Int, data: ByteArray = ByteArray(0), len: Int = 0) =
        sendRawDataWithHeader(d, addr, reply8(RESULT_REPLY, result), data, len)

    private fun sendLseekReply(d: DatagramSocket, addr: InetSocketAddress, pos: Long) {
        val r = ByteArray(12)
        r[0] = LSEEK_REPLY.toByte()
        if (pos < 0) {
            putU32(r, 4, pos and 0xFFFFFFFFL)
            putU32(r, 8, 0xFFFFFFFFL)
        } else {
            putU32(r, 4, pos and 0xFFFFFFFFL)
            putU32(r, 8, (pos ushr 32) and 0xFFFFFFFFL)
        }
        sendData(d, addr, r)
    }

    // ---------------------------------------------------------------- operações

    private class Stat(val mode: Int, val size: Long, val time: ByteArray) {
        companion object {
            val EMPTY = Stat(0, 0, ByteArray(8))
            fun of(f: VFile) = Stat(if (f.isDirectory) FIO_S_IFDIR else FIO_S_IFREG, if (f.isDirectory) 0 else f.length(), encodeTime(f.lastModified()))
        }
    }

    private fun resolve(path: String): VFile? = tree?.resolve(path)

    private fun allocHandle(h: Handle): Int = synchronized(handles) {
        if (handles.size >= MAX_HANDLES) return -EMFILE
        val id = nextHandle++
        handles[id] = h
        id
    }

    private fun closeHandle(h: Handle) {
        if (h is Handle.FileH) try { h.io.close() } catch (_: IOException) {}
    }

    private fun handleOpen(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 8) return sendOpenReply(d, addr, -EINVAL)
        val isDir = p[1].toInt() != 0
        val flags = u16(p, 2)
        val path = cString(p, 8)
        val f = resolve(path) ?: return sendOpenReply(d, addr, -EACCES)
        if (isDir) {
            if (!f.exists()) return sendOpenReply(d, addr, -ENOENT)
            val list = f.listFiles()
            if (!f.isDirectory || list == null) return sendOpenReply(d, addr, -ENOTDIR)
            val id = allocHandle(Handle.DirH(list.sortedBy { it.name.lowercase() }))
            return sendOpenReply(d, addr, id, if (id >= 0) Stat.of(f) else null)
        }
        val access = flags and 0x3
        val writable = access == O_WRONLY || access == O_RDWR
        if (writable && tree!!.readOnly) return sendOpenReply(d, addr, -EACCES)
        val creat = flags and O_CREAT != 0
        if (!f.exists() && !creat) return sendOpenReply(d, addr, -ENOENT)
        if (f.isDirectory) return sendOpenReply(d, addr, -EISDIR)
        val trunc = flags and O_TRUNC != 0
        val append = flags and O_APPEND != 0
        try {
            if (creat) f.parentFile?.mkdirs()
            val io = f.open(writable)
            val doTrunc = (access == O_WRONLY && trunc && !append) || (access == O_RDWR && creat && trunc)
            val doAppend = (access == O_WRONLY && append) || (access == O_RDWR && creat && !trunc && append)
            if (doTrunc) io.setLength(0)
            val id = allocHandle(Handle.FileH(io, doAppend))
            if (id < 0) io.close()
            log.log("UDPFS: abriu $path (${f.length() / (1024 * 1024)} MiB)")
            sendOpenReply(d, addr, id, if (id >= 0) Stat.of(f) else null)
        } catch (e: IOException) {
            log.log("UDPFS: erro ao abrir $path: ${e.message}")
            sendOpenReply(d, addr, -EIO)
        }
    }

    private fun handleClose(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 8) return sendData(d, addr, reply8(CLOSE_REPLY, -EINVAL))
        val id = i32(p, 4)
        if (id == BLOCK_HANDLE) return sendData(d, addr, reply8(CLOSE_REPLY, 0))
        val h = synchronized(handles) { handles.remove(id) } ?: return sendData(d, addr, reply8(CLOSE_REPLY, -EBADF))
        closeHandle(h)
        sendData(d, addr, reply8(CLOSE_REPLY, 0))
    }

    private fun handleRead(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 12) return sendReadResult(d, addr, -EINVAL)
        val id = i32(p, 4)
        val size = u32(p, 8).coerceAtMost(MAX_READ.toLong()).toInt()
        val h = synchronized(handles) { handles[id] }
        try {
            when (h) {
                is Handle.FileH -> {
                    val buf = ByteArray(size)
                    val n = h.io.readFully(h.pos, buf, 0, size)
                    h.pos += n
                    stats.bytesRead.addAndGet(n.toLong())
                    sendReadResult(d, addr, n, buf, n)
                }
                is Handle.BlockH -> {
                    val dev = device!!
                    val total = dev.sectorCount * dev.sectorSize
                    val n = minOf(size.toLong(), maxOf(0L, total - h.pos)).toInt()
                    val buf = readBytesFromDevice(dev, h.pos, n)
                    h.pos += n
                    stats.bytesRead.addAndGet(n.toLong())
                    sendReadResult(d, addr, n, buf, n)
                }
                else -> sendReadResult(d, addr, -EBADF)
            }
        } catch (e: IOException) {
            log.log("UDPFS: erro de leitura: ${e.message}")
            sendReadResult(d, addr, -EIO)
        }
    }

    private fun readBytesFromDevice(dev: BlockDevice, pos: Long, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val ss = dev.sectorSize
        val first = pos / ss
        val last = (pos + n - 1) / ss
        val count = (last - first + 1).toInt()
        val tmp = ByteArray(count * ss)
        dev.read(first, count, tmp, 0)
        return tmp.copyOfRange((pos - first * ss).toInt(), (pos - first * ss).toInt() + n)
    }

    private fun handleWriteReq(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 12) return sendAck(d, addr, true)
        val id = i32(p, 4)
        if (tree?.readOnly == true) return sendData(d, addr, reply8(WRITE_DONE, -EACCES))
        val h = synchronized(handles) { handles[id] }
        if (h !is Handle.FileH) return sendData(d, addr, reply8(WRITE_DONE, -EBADF))
        wHandle = id; wIsBlock = false
        wData = java.io.ByteArrayOutputStream(); wTotalChunks = 0; wReceived = 0
        if (p.size > 12) handleWriteData(d, addr, p, 12) else sendAck(d, addr, true)
    }

    private fun handleWriteData(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray, at: Int) {
        if (p.size - at < 8) return sendAck(d, addr, true)
        val chunkNr = u16(p, at + 2)
        val chunkSize = u16(p, at + 4)
        val total = u16(p, at + 6)
        if (chunkNr != wReceived) {
            log.log("UDPFS: ordem errada nos blocos de escrita (esperado $wReceived, veio $chunkNr)")
            return sendAck(d, addr, true)
        }
        wData.write(p, at + 8, minOf(chunkSize, p.size - at - 8))
        wTotalChunks = total
        wReceived++
        if (wReceived >= total) {
            if (wIsBlock) completeBwrite(d, addr) else completeWrite(d, addr)
        } else sendAck(d, addr, true)
    }

    private fun completeWrite(d: DatagramSocket, addr: InetSocketAddress) {
        val h = synchronized(handles) { handles[wHandle] } as? Handle.FileH ?: return sendData(d, addr, reply8(WRITE_DONE, -EBADF))
        try {
            val bytes = wData.toByteArray()
            if (h.append) h.pos = h.io.length()
            h.io.write(h.pos, bytes, 0, bytes.size)
            h.pos += bytes.size
            stats.bytesWritten.addAndGet(bytes.size.toLong())
            sendData(d, addr, reply8(WRITE_DONE, bytes.size))
        } catch (e: IOException) {
            log.log("UDPFS: erro de escrita: ${e.message}")
            sendData(d, addr, reply8(WRITE_DONE, -EIO))
        }
    }

    private fun completeBwrite(d: DatagramSocket, addr: InetSocketAddress) {
        val h = synchronized(handles) { handles[wHandle] } ?: return sendData(d, addr, reply8(WRITE_DONE, -EBADF))
        val bytes = wData.toByteArray()
        try {
            when (h) {
                is Handle.BlockH -> {
                    val dev = device!!
                    if (dev.readOnly) return sendData(d, addr, reply8(WRITE_DONE, -EACCES))
                    val expected = wSectorCount * dev.sectorSize
                    val data = if (bytes.size >= expected) bytes else bytes.copyOf(expected)
                    dev.write(wSector, wSectorCount, data, 0)
                    stats.bytesWritten.addAndGet(expected.toLong())
                }
                is Handle.FileH -> {
                    val expected = wSectorCount * 512
                    h.io.write(wSector * 512, bytes, 0, minOf(expected, bytes.size))
                    stats.bytesWritten.addAndGet(expected.toLong())
                }
                else -> return sendData(d, addr, reply8(WRITE_DONE, -EBADF))
            }
            sendData(d, addr, reply8(WRITE_DONE, 0))
        } catch (e: IOException) {
            log.log("UDPFS: erro de escrita em bloco: ${e.message}")
            sendData(d, addr, reply8(WRITE_DONE, -EIO))
        }
    }

    private fun handleLseek(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 16) return sendLseekReply(d, addr, -EINVAL.toLong())
        val whence = p[1].toInt()
        val id = i32(p, 4)
        val offset = (i32(p, 12).toLong() shl 32) or (i32(p, 8).toLong() and 0xFFFFFFFFL)
        val h = synchronized(handles) { handles[id] }
        try {
            when (h) {
                is Handle.FileH -> {
                    val base = when (whence) { 0 -> 0L; 1 -> h.pos; 2 -> h.io.length(); else -> return sendLseekReply(d, addr, -EINVAL.toLong()) }
                    val np = base + offset
                    if (np < 0) return sendLseekReply(d, addr, -EINVAL.toLong())
                    h.pos = np
                    sendLseekReply(d, addr, np)
                }
                is Handle.BlockH -> {
                    val dev = device!!
                    val base = when (whence) { 0 -> 0L; 1 -> h.pos; 2 -> dev.sectorCount * dev.sectorSize; else -> return sendLseekReply(d, addr, -EINVAL.toLong()) }
                    val np = base + offset
                    if (np < 0) return sendLseekReply(d, addr, -EINVAL.toLong())
                    h.pos = np
                    sendLseekReply(d, addr, np)
                }
                else -> sendLseekReply(d, addr, -EBADF.toLong())
            }
        } catch (e: IOException) {
            sendLseekReply(d, addr, -EIO.toLong())
        }
    }

    private fun handleDread(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 8) return sendDreadReply(d, addr, -EINVAL)
        val h = synchronized(handles) { handles[i32(p, 4)] } as? Handle.DirH ?: return sendDreadReply(d, addr, -EBADF)
        if (h.index >= h.entries.size) return sendDreadReply(d, addr, 0)
        val e = h.entries[h.index++]
        sendDreadReply(d, addr, 1, e.name, Stat.of(e))
    }

    private fun handleGetstat(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 4) return sendGetstatReply(d, addr, -EINVAL)
        val path = cString(p, 4)
        if (path.isEmpty() && device != null) {
            // Capacidade do dispositivo de bloco (udpfs_bd)
            return sendGetstatReply(d, addr, 0, Stat(0, device.sectorCount * device.sectorSize, ByteArray(8)))
        }
        val f = resolve(path) ?: return sendGetstatReply(d, addr, -EACCES)
        if (!f.exists()) return sendGetstatReply(d, addr, -ENOENT)
        sendGetstatReply(d, addr, 0, Stat.of(f))
    }

    private fun handleMkdir(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 4) return sendData(d, addr, reply8(RESULT_REPLY, -EINVAL))
        if (tree == null || tree.readOnly) return sendData(d, addr, reply8(RESULT_REPLY, -EACCES))
        val f = resolve(cString(p, 4)) ?: return sendData(d, addr, reply8(RESULT_REPLY, -EACCES))
        val r = when {
            f.exists() -> -EEXIST
            f.mkdir() -> 0
            else -> -EIO
        }
        sendData(d, addr, reply8(RESULT_REPLY, r))
    }

    private fun handleRemove(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 4) return sendData(d, addr, reply8(RESULT_REPLY, -EINVAL))
        if (tree == null || tree.readOnly) return sendData(d, addr, reply8(RESULT_REPLY, -EACCES))
        val f = resolve(cString(p, 4)) ?: return sendData(d, addr, reply8(RESULT_REPLY, -EACCES))
        val r = when {
            !f.exists() -> -ENOENT
            f.isDirectory -> -EISDIR
            f.delete() -> 0
            else -> -EIO
        }
        sendData(d, addr, reply8(RESULT_REPLY, r))
    }

    private fun handleRmdir(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 4) return sendData(d, addr, reply8(RESULT_REPLY, -EINVAL))
        if (tree == null || tree.readOnly) return sendData(d, addr, reply8(RESULT_REPLY, -EACCES))
        val f = resolve(cString(p, 4)) ?: return sendData(d, addr, reply8(RESULT_REPLY, -EACCES))
        val r = when {
            !f.exists() -> -ENOENT
            !f.isDirectory -> -ENOTDIR
            (f.listFiles()?.isNotEmpty() == true) -> -ENOTEMPTY
            f.delete() -> 0
            else -> -EIO
        }
        sendData(d, addr, reply8(RESULT_REPLY, r))
    }

    private fun handleBread(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 16) return sendReadResult(d, addr, -EINVAL)
        val count = u16(p, 2)
        val id = i32(p, 4)
        val sector = u32(p, 8) or (u32(p, 12) shl 32)
        val h = synchronized(handles) { handles[id] }
        try {
            when (h) {
                is Handle.BlockH -> {
                    val dev = device!!
                    val n = maxOf(0L, minOf(count.toLong(), dev.sectorCount - sector)).toInt()
                    val buf = ByteArray(n * dev.sectorSize)
                    if (n > 0) dev.read(sector, n, buf, 0)
                    stats.bytesRead.addAndGet(buf.size.toLong())
                    sendReadResult(d, addr, buf.size, buf, buf.size)
                }
                is Handle.FileH -> {
                    val total = count * 512
                    val buf = ByteArray(total)
                    val n = h.io.readFully(sector * 512, buf, 0, total)
                    stats.bytesRead.addAndGet(n.toLong())
                    sendReadResult(d, addr, n, buf, n)
                }
                else -> sendReadResult(d, addr, -EBADF)
            }
        } catch (e: IOException) {
            log.log("UDPFS: erro de leitura em bloco no setor $sector: ${e.message}")
            sendReadResult(d, addr, -EIO)
        }
    }

    private fun handleBwriteReq(d: DatagramSocket, addr: InetSocketAddress, p: ByteArray) {
        if (p.size < 16) return sendAck(d, addr, true)
        val count = u16(p, 2)
        val id = i32(p, 4)
        val sector = u32(p, 8) or (u32(p, 12) shl 32)
        val h = synchronized(handles) { handles[id] }
        val readOnly = when (h) {
            is Handle.BlockH -> device!!.readOnly
            is Handle.FileH -> tree?.readOnly == true
            else -> return sendData(d, addr, reply8(WRITE_DONE, -EBADF))
        }
        if (readOnly) return sendData(d, addr, reply8(WRITE_DONE, -EACCES))
        wHandle = id; wIsBlock = true; wSector = sector; wSectorCount = count
        wData = java.io.ByteArrayOutputStream(); wTotalChunks = 0; wReceived = 0
        if (p.size > 16) handleWriteData(d, addr, p, 16) else sendAck(d, addr, true)
    }

    companion object {
        const val PORT = 0xF5F6
        const val SERVICE_ID = 0xF5F5
        const val PT_DISCOVERY = 0
        const val PT_INFORM = 1
        const val PT_DATA = 2
        const val DF_ACK = 1
        const val DF_FIN = 2

        const val OPEN_REQ = 0x10; const val OPEN_REPLY = 0x11
        const val CLOSE_REQ = 0x12; const val CLOSE_REPLY = 0x13
        const val READ_REQ = 0x14
        const val WRITE_REQ = 0x16; const val WRITE_DATA = 0x17; const val WRITE_DONE = 0x18
        const val LSEEK_REQ = 0x1A; const val LSEEK_REPLY = 0x1B
        const val DREAD_REQ = 0x1C; const val DREAD_REPLY = 0x1D
        const val GETSTAT_REQ = 0x1E; const val GETSTAT_REPLY = 0x1F
        const val MKDIR_REQ = 0x20; const val REMOVE_REQ = 0x22; const val RMDIR_REQ = 0x24
        const val RESULT_REPLY = 0x26
        const val BREAD_REQ = 0x28; const val BWRITE_REQ = 0x2A

        const val FIO_S_IFREG = 0x2000
        const val FIO_S_IFDIR = 0x1000
        const val O_WRONLY = 0x0002; const val O_RDWR = 0x0003
        const val O_APPEND = 0x0100; const val O_CREAT = 0x0200; const val O_TRUNC = 0x0400

        const val ENOENT = 2; const val EIO = 5; const val EBADF = 9; const val EACCES = 13; const val EEXIST = 17
        const val ENOTDIR = 20; const val EISDIR = 21; const val EINVAL = 22; const val EMFILE = 24; const val ENOTEMPTY = 39

        const val MAX_PAYLOAD = 1408
        const val MAX_READ = 1 shl 20
        const val MAX_HANDLES = 128
        const val BLOCK_HANDLE = 0
        const val SEND_WINDOW = 8
        const val WINDOW_ACK_TIMEOUT_MS = 100
        const val MAX_WINDOW_RETRIES = 4

        fun dataHeader(ack: Int, flags: Int, hdrWords: Int, dataBytes: Int): Long =
            ((ack and 0xFFF) or ((flags and 0x3) shl 12) or ((hdrWords and 0xF) shl 14) or ((dataBytes and 0x3FFF) shl 18)).toLong() and 0xFFFFFFFFL

        /** Mesmo critério do servidor Python: menos pacotes, empate pelo maior alinhamento. */
        fun optimalChunkSize(total: Int): Int {
            var best = 1408
            var bestPackets = (total + 1407) / 1408
            var bestAlign = 128
            for ((chunk, align) in listOf(1024 to 512, 1280 to 256, 1408 to 128)) {
                val packets = (total + chunk - 1) / chunk
                if (packets < bestPackets || (packets == bestPackets && align > bestAlign)) {
                    best = chunk; bestPackets = packets; bestAlign = align
                }
            }
            return best
        }

        /** Formato de data do PS2 (iox_stat_t): 0, seg, min, hora, dia, mês, ano (u16 LE), hora local. */
        fun encodeTime(millis: Long): ByteArray {
            if (millis <= 0) return ByteArray(8)
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            val year = c.get(Calendar.YEAR)
            return byteArrayOf(
                0, c.get(Calendar.SECOND).toByte(), c.get(Calendar.MINUTE).toByte(), c.get(Calendar.HOUR_OF_DAY).toByte(),
                c.get(Calendar.DAY_OF_MONTH).toByte(), (c.get(Calendar.MONTH) + 1).toByte(), year.toByte(), (year shr 8).toByte(),
            )
        }

        fun cString(b: ByteArray, from: Int): String {
            if (from >= b.size) return ""
            var end = from
            while (end < b.size && b[end].toInt() != 0) end++
            return String(b, from, end - from, Charsets.UTF_8)
        }

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        fun i32(b: ByteArray, o: Int) = u32(b, o).toInt()
        fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun putU32(b: ByteArray, o: Int, v: Long) { putU16(b, o, (v and 0xFFFF).toInt()); putU16(b, o + 2, ((v shr 16) and 0xFFFF).toInt()) }
    }
}
