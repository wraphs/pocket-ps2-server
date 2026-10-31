package br.ps2ponte.priv

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.RandomAccessFile

/**
 * Processo auxiliar com privilégio (root pelo `su`, ou usuário shell pelo Shizuku).
 *
 * Roda fora do app, por `app_process` com o próprio APK no CLASSPATH, e atende pedidos de arquivo pelo
 * stdin/stdout: assim o servidor (no app, sem privilégio) lê pastas que o Android esconde dos apps, como
 * a raiz do armazenamento ou o pendrive em /mnt/media_rw. Só usa java.io: aqui não existe Context.
 *
 * Protocolo (big-endian): pedido = op (u8) + argumentos; resposta = status (u8: 0 ok, 1 erro + texto) + dados.
 */
object Ajudante {
    const val MAGICO = 0x50505332 // "PPS2", mandado ao subir: o app sabe que o auxiliar está vivo
    const val VERSAO = 1

    const val OP_STAT = 1
    const val OP_LIST = 2
    const val OP_MKDIR = 3
    const val OP_DELETE = 4
    const val OP_RENAME = 5
    const val OP_OPEN = 6
    const val OP_READ = 7
    const val OP_WRITE = 8
    const val OP_LENGTH = 9
    const val OP_SETLEN = 10
    const val OP_CLOSE = 11
    const val OP_SPACE = 12

    const val MAX_BLOCO = 1 shl 20

    @JvmStatic
    fun main(args: Array<String>) {
        // O stdout é o canal: nada mais pode escrever nele (System.out vira stderr)
        val saida = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 17))
        System.setOut(System.err)
        val entrada = DataInputStream(BufferedInputStream(System.`in`, 1 shl 17))
        val abertos = HashMap<Int, RandomAccessFile>()
        var proximo = 1
        var buf = ByteArray(64 * 1024)

        saida.writeInt(MAGICO)
        saida.writeInt(VERSAO)
        saida.flush()

        while (true) {
            val op = try {
                entrada.readUnsignedByte()
            } catch (e: EOFException) {
                break // o app fechou o canal
            }
            try {
                when (op) {
                    OP_STAT -> {
                        val f = File(entrada.readUTF())
                        saida.writeByte(0)
                        escreverStat(saida, f)
                    }
                    OP_LIST -> {
                        val lista = File(entrada.readUTF()).listFiles()
                        saida.writeByte(0)
                        if (lista == null) {
                            saida.writeInt(-1)
                        } else {
                            saida.writeInt(lista.size)
                            for (f in lista) {
                                saida.writeUTF(f.name)
                                escreverStat(saida, f)
                            }
                        }
                    }
                    OP_MKDIR -> ok(saida, File(entrada.readUTF()).mkdir())
                    OP_DELETE -> ok(saida, File(entrada.readUTF()).delete())
                    OP_RENAME -> {
                        val de = File(entrada.readUTF())
                        val para = File(entrada.readUTF())
                        ok(saida, de.renameTo(para))
                    }
                    OP_OPEN -> {
                        val caminho = entrada.readUTF()
                        val gravar = entrada.readBoolean()
                        val raf = RandomAccessFile(caminho, if (gravar) "rw" else "r")
                        val id = proximo++
                        abertos[id] = raf
                        saida.writeByte(0)
                        saida.writeInt(id)
                    }
                    OP_READ -> {
                        val id = entrada.readInt()
                        val pos = entrada.readLong()
                        val len = entrada.readInt().coerceIn(0, MAX_BLOCO)
                        val raf = handle(abertos, id)
                        if (buf.size < len) buf = ByteArray(len)
                        val n = if (len == 0) 0 else raf.channel.read(java.nio.ByteBuffer.wrap(buf, 0, len), pos)
                        saida.writeByte(0)
                        saida.writeInt(n)
                        if (n > 0) saida.write(buf, 0, n)
                    }
                    OP_WRITE -> {
                        val raf = abertos[entrada.readInt()]
                        val pos = entrada.readLong()
                        val len = entrada.readInt()
                        if (len < 0 || len > MAX_BLOCO) throw IllegalArgumentException("bloco inválido: $len")
                        if (buf.size < len) buf = ByteArray(len)
                        entrada.readFully(buf, 0, len) // lê sempre, para o canal não dessincronizar
                        if (raf == null) throw IllegalStateException("arquivo não está aberto")
                        val bb = java.nio.ByteBuffer.wrap(buf, 0, len)
                        var p = pos
                        while (bb.hasRemaining()) p += raf.channel.write(bb, p)
                        saida.writeByte(0)
                    }
                    OP_LENGTH -> {
                        val raf = handle(abertos, entrada.readInt())
                        saida.writeByte(0)
                        saida.writeLong(raf.length())
                    }
                    OP_SETLEN -> {
                        val id = entrada.readInt()
                        val n = entrada.readLong()
                        handle(abertos, id).setLength(n)
                        saida.writeByte(0)
                    }
                    OP_CLOSE -> {
                        abertos.remove(entrada.readInt())?.close()
                        saida.writeByte(0)
                    }
                    OP_SPACE -> {
                        val f = File(entrada.readUTF())
                        saida.writeByte(0)
                        saida.writeLong(f.totalSpace)
                        saida.writeLong(f.usableSpace)
                    }
                    else -> {
                        // Op desconhecida: não dá para saber quantos bytes pular; encerra em vez de dessincronizar
                        saida.writeByte(1)
                        saida.writeUTF("op desconhecida $op")
                        saida.flush()
                        break
                    }
                }
            } catch (e: Exception) {
                saida.writeByte(1)
                saida.writeUTF(e.message ?: e.javaClass.simpleName)
            }
            saida.flush()
        }
        for (raf in abertos.values) try { raf.close() } catch (_: Exception) {}
    }

    private fun handle(abertos: Map<Int, RandomAccessFile>, id: Int) =
        abertos[id] ?: throw IllegalStateException("arquivo não está aberto")

    private fun ok(saida: DataOutputStream, r: Boolean) {
        saida.writeByte(0)
        saida.writeBoolean(r)
    }

    private fun escreverStat(saida: DataOutputStream, f: File) {
        val dir = f.isDirectory
        saida.writeBoolean(f.exists())
        saida.writeBoolean(dir)
        saida.writeLong(if (dir) 0 else f.length())
        saida.writeLong(f.lastModified())
    }
}
