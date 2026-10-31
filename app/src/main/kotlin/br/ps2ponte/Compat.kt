package br.ps2ponte

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** Diferenças entre as versões do Android (o app vai do 5.0 ao 15). */
object Compat {
    val IMUTAVEL = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    val MUTAVEL = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun registrar(ctx: Context, r: BroadcastReceiver, filtro: IntentFilter, exportado: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.registerReceiver(r, filtro, if (exportado) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(r, filtro)
        }
    }

    /** Um volume de armazenamento: memória interna, cartão SD ou pendrive. */
    class Volume(
        val descricao: String,
        /** Caminho que os apps veem (/storage/emulated/0, /storage/XXXX-XXXX); null se desconhecido. */
        val caminho: String?,
        val primario: Boolean,
        val uuid: String?,
        /** Só no Android 7+ (para abrir o seletor direto na raiz). */
        val sv: StorageVolume?,
    )

    fun volumes(ctx: Context): List<Volume> {
        if (Build.VERSION.SDK_INT >= 24) {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            return sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED }.map { v ->
                Volume(
                    if (v.isPrimary) ctx.getString(R.string.vol_interno) else v.getDescription(ctx),
                    caminhoAcessivel(v), v.isPrimary, v.uuid, v,
                )
            }
        }
        // Android 5/6: a raiz de cada volume sai de getExternalFilesDirs (".../Android/data/<pacote>/files")
        return ctx.getExternalFilesDirs(null).filterNotNull().mapIndexedNotNull { i, d ->
            val raiz = d.path.substringBefore("/Android/data/").takeIf { it != d.path } ?: return@mapIndexedNotNull null
            if (i == 0) Volume(ctx.getString(R.string.vol_interno), raiz, true, null, null)
            else Volume(File(raiz).name, raiz, false, File(raiz).name, null)
        }
    }

    /**
     * Caminho do volume que o app consegue abrir. Em alguns Samsung o Android informa o caminho interno
     * /mnt/media_rw/<uuid> (só o sistema acessa); para os apps o pendrive fica em /storage/<uuid>.
     */
    @TargetApi(24)
    private fun caminhoAcessivel(v: StorageVolume): String? {
        val dir = if (Build.VERSION.SDK_INT >= 30) v.directory?.path else caminhoAntigo(v)
        val uuid = v.uuid ?: return dir
        return if (dir == null || dir.startsWith("/mnt/") || !File(dir).canRead()) "/storage/$uuid" else dir
    }

    /** StorageVolume.getPath() existe (oculto) até o Android 10; no 11 virou getDirectory(). */
    @TargetApi(24)
    private fun caminhoAntigo(v: StorageVolume): String? =
        try { v.javaClass.getMethod("getPath").invoke(v) as? String } catch (e: Exception) { null }

    /** Caminho de um pendrive/SD por uuid (para o seletor que devolve "XXXX-XXXX:pasta"). */
    fun caminhoDoVolume(ctx: Context, uuid: String): String =
        volumes(ctx).firstOrNull { it.uuid.equals(uuid, true) }?.caminho ?: "/storage/$uuid"
}
