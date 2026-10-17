package br.ps2ponte

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import br.ps2ponte.core.fs.LocalFile
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.saf.SafRaiz
import java.io.File

/**
 * A pasta escolhida pode ser um caminho (memória interna, com "acesso a todos os arquivos")
 * ou uma árvore do SAF "content://..." (pendrive USB, que o Android só libera pelo seletor).
 */
object Fonte {
    fun ehSaf(pasta: String) = SafRaiz.ehSaf(pasta)

    fun raiz(ctx: Context, pasta: String): VFile =
        if (ehSaf(pasta)) SafRaiz(ctx, Uri.parse(pasta)).raiz() else LocalFile.root(File(pasta))

    /** Para mostrar na tela: caminho, ou "PS2SMB (pendrive) › /DVD". */
    fun descricao(ctx: Context, pasta: String): String {
        if (!ehSaf(pasta)) return pasta
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(pasta))
            val vol = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val nome = ctx.getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.uuid.equals(vol, true) }?.getDescription(ctx) ?: vol
            "$nome (pendrive) › /$rel"
        } catch (e: Exception) {
            pasta
        }
    }

    /** No SAF a permissão fica salva no sistema; some se o app for reinstalado ou o acesso for revogado. */
    fun temPermissao(ctx: Context, pasta: String): Boolean =
        !ehSaf(pasta) || ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == pasta && it.isReadPermission }
}
