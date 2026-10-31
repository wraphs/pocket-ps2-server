package br.ps2ponte

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import br.ps2ponte.core.fs.LocalFile
import br.ps2ponte.core.fs.VFile
import br.ps2ponte.priv.Privilegio
import br.ps2ponte.saf.SafRaiz
import java.io.File

/**
 * A pasta escolhida pode ser:
 * - um caminho (memória interna, com "acesso a todos os arquivos");
 * - uma árvore do SAF "content://..." (pendrive USB, que o Android só libera pelo seletor);
 * - "root:/caminho" ou "shizuku:/caminho" (lida pelo auxiliar com privilégio, ver [Privilegio]).
 */
object Fonte {
    fun ehSaf(pasta: String) = SafRaiz.ehSaf(pasta)
    fun ehPriv(pasta: String) = Privilegio.ehPriv(pasta)

    /** Pode bloquear nos modos root/Shizuku (sobe o auxiliar): não chame na thread principal. */
    fun raiz(ctx: Context, pasta: String): VFile = when {
        ehSaf(pasta) -> SafRaiz(ctx, Uri.parse(pasta)).raiz()
        ehPriv(pasta) -> Privilegio.raiz(ctx, pasta)
        else -> LocalFile.root(File(pasta))
    }

    /** Para mostrar na tela: caminho, "PS2SMB (pendrive) › /DVD" ou "/storage/emulated/0 (root)". */
    fun descricao(ctx: Context, pasta: String): String {
        if (ehPriv(pasta)) return "${Privilegio.caminho(pasta)} (${if (Privilegio.modo(pasta) == Privilegio.ROOT) "root" else "Shizuku"})"
        if (!ehSaf(pasta)) return pasta
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(pasta))
            val vol = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val nome = if (Build.VERSION.SDK_INT >= 24) {
                (ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes
                    .firstOrNull { it.uuid.equals(vol, true) }?.getDescription(ctx)
            } else null
            "${nome ?: vol} (${ctx.getString(R.string.pendrive)}) › /$rel"
        } catch (e: Exception) {
            pasta
        }
    }

    /** No SAF a permissão fica salva no sistema; some se o app for reinstalado ou o acesso for revogado. */
    fun temPermissao(ctx: Context, pasta: String): Boolean =
        !ehSaf(pasta) || ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == pasta && it.isReadPermission }
}
