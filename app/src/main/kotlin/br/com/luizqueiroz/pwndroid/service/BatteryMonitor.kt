package br.com.luizqueiroz.pwndroid.service

import android.content.Context
import android.os.BatteryManager
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Medição básica de bateria durante a sessão (issue #17 — risco #6 do
 * plano): loga o % de bateria periodicamente para embasar a decisão de
 * quanto o ciclo contínuo drena o device em background.
 */
internal class BatteryMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
    private val registryProvider: () -> br.com.luizqueiroz.pwndroid.core.session.SessionRegistry,
) {

    private var job: Job? = null
    private var startPercent: Int? = null

    fun start() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        startPercent = bm.capacity()
        job = scope.launch {
            while (true) {
                delay(SAMPLE_MS)
                val pct = bm.capacity()
                logger.d(
                    TAG,
                    "battery: start=$startPercent% now=$pct% " +
                        "epoch=${registry().state.value.session.epoch}",
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun registry(): SessionRegistry = registryProvider()

    /** % de bateria (0–100), via property padrão do BatteryManager. */
    private fun BatteryManager.capacity(): Int =
        getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    companion object {
        private const val TAG = "BatteryMonitor"

        /** Amostragem de bateria: a cada 5 min. */
        private const val SAMPLE_MS = 5 * 60 * 1000L
    }
}
