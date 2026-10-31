package br.ps2ponte

import android.content.Context
import android.os.Build
import android.os.PowerManager
import br.ps2ponte.core.TransferStats

/** Traduz erros técnicos em "onde pode estar o problema" para quem usa o app. */
object Dicas {
    private val ultimaVez = HashMap<Int, Long>()

    /** Dica para uma linha do log do núcleo (PT ou EN), no máximo uma por tipo a cada 30 s. */
    fun depois(ctx: Context, msg: String): String? {
        val m = msg.lowercase()
        val id = when {
            "rede indisponível" in m || "network unavailable" in m -> R.string.dica_rede_oscilou
            "não confirmou" in m || "did not acknowledge" in m -> R.string.dica_ps2_parou
            m.startsWith("smb:") && ("desconectou" in m || "disconnected" in m) && "eofexception" !in m -> R.string.dica_conexao_caiu
            "smb2" in m -> R.string.dica_smb2
            "acesso root caiu" in m || "acesso shizuku caiu" in m || "access stopped" in m -> R.string.dica_acesso_caiu
            "erro ao ler" in m || "erro de leitura" in m || "error reading" in m || "read error" in m ||
                "erro ao abrir" in m || "error opening" in m -> R.string.dica_leitura
            else -> return null
        }
        val agora = System.currentTimeMillis()
        synchronized(ultimaVez) {
            if (agora - (ultimaVez[id] ?: 0) < 30_000) return null
            ultimaVez[id] = agora
        }
        return ctx.getString(id)
    }

    /**
     * Vigia a sessão (chamado a cada segundo pelo serviço): cabo que cai/volta, PS2 que nunca aparece,
     * PS2 falando por outra rede. Escreve no log uma vez por ocorrência e atualiza [Estado.dica].
     */
    class Vigia(private val ctx: Context, private val stats: TransferStats) {
        private val inicio = System.currentTimeMillis()
        private var semCabo = false
        private var avisouSemPs2 = false
        private var avisouOutraRede: String? = null
        private var avisouBateria = false

        fun passo() {
            val agora = System.currentTimeMillis()
            val cabo = Rede.cabo()
            if (cabo == null) {
                if (!semCabo) Estado.log(ctx.getString(R.string.dica_sem_cabo))
                semCabo = true
            } else if (semCabo) {
                semCabo = false
                Estado.log(ctx.getString(R.string.dica_cabo_voltou, cabo.ip))
            }

            val ultimo = stats.lastRequestAt.get()
            if (ultimo == 0L && cabo != null && agora - inicio > ESPERA_PS2 && !avisouSemPs2) {
                avisouSemPs2 = true
                Estado.log(ctx.getString(R.string.dica_sem_ps2, (ESPERA_PS2 / 1000).toInt(), cabo.ip, Rede.sugestaoPs2(cabo.ip)))
            }

            val cliente = stats.lastClient
            if (cliente != null && cabo != null && cliente != avisouOutraRede && !Rede.mesmaRede(cliente, cabo)) {
                avisouOutraRede = cliente
                Estado.log(ctx.getString(R.string.dica_outra_rede, cliente, "${cabo.ip}/${cabo.prefixo}", Rede.sugestaoPs2(cabo.ip)))
            }

            if (!avisouBateria && ultimo != 0L && Build.VERSION.SDK_INT >= 23) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) Estado.log(ctx.getString(R.string.dica_bateria))
                avisouBateria = true
            }

            val novaDica = when {
                cabo == null -> ctx.getString(R.string.st_sem_cabo_dica)
                ultimo == 0L && agora - inicio > 3000 -> ctx.getString(R.string.st_esperando_ps2)
                else -> null
            }
            if (novaDica != Estado.dica) {
                Estado.dica = novaDica
                Estado.avisar()
            }
        }

        companion object {
            const val ESPERA_PS2 = 30_000L
        }
    }
}
