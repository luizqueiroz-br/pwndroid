package br.com.luizqueiroz.pwndroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.luizqueiroz.pwndroid.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Retoma a última sessão após reboot quando a config `auto_start` está
 * ativa (issue #17). Consulta a config do [ConfigStore] em uma corrotina
 * curta (`goAsync` dá ~10s) e inicia o [PwnForegroundService] via
 * `startForegroundService` — em BOOT_COMPLETED o FGS é permitido sem o
 * app estar em foreground (isento de Bal).
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val koin = GlobalContext.get()
                val autoStart = koin.get<ConfigStore>().config.first().autoStart
                if (autoStart) {
                    PwnForegroundService.start(context)
                }
            } catch (_: Exception) {
                // Sem Koin/config (processo recém-nascido falhou): não
                // retoma a sessão — o usuário pode abri-la manualmente.
            } finally {
                pending.finish()
            }
        }
    }
}
