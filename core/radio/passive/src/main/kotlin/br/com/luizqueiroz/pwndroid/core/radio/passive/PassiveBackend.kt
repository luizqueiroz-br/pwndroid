package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.AccessPoint
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.radio.BackendUnavailableException
import br.com.luizqueiroz.pwndroid.core.radio.RadioBackend
import br.com.luizqueiroz.pwndroid.core.radio.RadioCapabilities
import br.com.luizqueiroz.pwndroid.core.radio.StartedBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backend sem root (v0.1): scans periódicos via [WifiManager] — cadência
 * throttled pelo SO (~4 scans/2min em background), aceita para wardriving.
 *
 * Sem clientes (scan passivo não vê estações) e sem injeção: assoc/deauth/
 * setChannel retornam falha de operação não suportada.
 */
class PassiveBackend(
    private val deps: PassiveDependencies,
    private val wifiManagerProvider: WifiManagerProvider = WifiManagerProvider { c ->
        c.getSystemService(Context.WIFI_SERVICE) as WifiManager
    },
    private val checks: SystemChecks,
    private val settingsOpener: SettingsOpener = SettingsOpener { },
) : RadioBackend {

    override val id: BackendId = BackendId.PASSIVE
    override val capabilities = RadioCapabilities(
        canRecon = true,
        canAssoc = false,
        canDeauth = false,
        canCaptureEapol = false,
        canCapturePmkid = false,
        canSetChannel = false,
        canSeePeers = false,
        canBle = false,
    )

    override suspend fun start(env: BackendEnvironment): StartedBackend {
        if (!checks.isLocationEnabled()) {
            settingsOpener.openLocationSettings()
            throw BackendUnavailableException(
                "localização desligada: ative em Configurações para escanear redes",
            )
        }
        if (!checks.isWifiEnabled()) {
            throw BackendUnavailableException("Wi-Fi desligado no device")
        }
        return StartedPassive(env.clock, deps, wifiManagerProvider)
    }

    private class StartedPassive(
        private val clock: AppClock,
        private val deps: PassiveDependencies,
        private val wifiManagerProvider: WifiManagerProvider,
    ) : StartedBackend {

        private val bus = MutableSharedFlow<RadioEvent>(replay = 64, extraBufferCapacity = 256)
        private var scanJob: Job? = null
        private var netJob: Job? = null
        private var scope: CoroutineScope? = null

        override val backendId: BackendId = BackendId.PASSIVE

        override suspend fun startRecon(channels: Set<Int>, dwellMs: Long) {
            scanJob?.cancel()
            netJob?.cancel()
            val activeScope = CoroutineScope(clock.io)
            scope = activeScope
            scanJob = activeScope.launch {
                while (isActive) {
                    emitScan()
                    delay(dwellMs)
                }
            }
            // InternetAvailable via ConnectivityManager (issue #9).
            netJob = activeScope.launch {
                ConnectivityWatcher(deps.appContext).internetEvents().collect { bus.tryEmit(it) }
            }
        }

        /** Um ciclo de scan: dispara scan (throttled pelo SO) e emite o que houver. */
        private suspend fun emitScan() {
            val wifi = wifiManagerProvider.get(deps.appContext)
            // Dispara o scan; o SO pode throttar (~4/2min) — aceito, é wardriving.
            runCatching { @Suppress("DEPRECATION") wifi.startScan() }
            @Suppress("DEPRECATION")
            val results: List<ScanResult> = try {
                wifi.scanResults ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
            ScanResultMapper.toApSeen(results).forEach { bus.tryEmit(it) }
        }

        override suspend fun stopRecon() {
            scanJob?.cancel()
            scanJob = null
            netJob?.cancel()
            netJob = null
        }

        override suspend fun accessPoints(): List<AccessPoint> =
            emptyList() // snapshot via eventos; o orquestrador usa o fluxo

        override suspend fun assoc(ap: MacAddress) =
            Result.failure<Unit>(UnsupportedOperationException("scan passivo não injeta frames"))

        override suspend fun deauth(sta: MacAddress?, ap: MacAddress) =
            Result.failure<Unit>(UnsupportedOperationException("scan passivo não injeta frames"))

        override suspend fun setChannel(channel: Int, widthMhz: Int) =
            Result.failure<Unit>(UnsupportedOperationException("scan passivo não muda canal"))

        override fun events(): SharedFlow<RadioEvent> = bus.asSharedFlow()

        override suspend fun applyPersonality(p: Personality) = Unit

        override suspend fun shutdown() {
            stopRecon()
        }
    }
}
