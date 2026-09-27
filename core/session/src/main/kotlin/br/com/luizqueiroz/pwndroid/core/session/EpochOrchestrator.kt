package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.EpochResult
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.model.Target
import br.com.luizqueiroz.pwndroid.core.radio.StartedBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orquestrador de épocas — porte do agent.py do pwnagotchi:
 * recon (recon_time s) → seleção de alvo pelo cérebro → interações
 * (assoc/deauth) → fim de época (report ao cérebro).
 */
class EpochOrchestrator(
    private val backend: StartedBackend,
    private val brain: Brain,
    private val bus: EventBus,
    private val clock: AppClock,
) {
    data class State(
        val epoch: Long = 0,
        val phase: Phase = Phase.IDLE,
        val candidates: List<Target> = emptyList(),
        val handshakesThisEpoch: Int = 0,
    )

    enum class Phase { IDLE, RECON, INTERACT, REPORT }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            bus.subscribe<RadioEvent.HandshakeDetected>(this) { }
            backend.start()
            while (true) {
                runEpoch(scope)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(phase = Phase.IDLE)
    }

    private suspend fun runEpoch(scope: CoroutineScope) {
        val candidates = mutableListOf<Target>()
        var handshakes = 0

        // Coleta eventos do recon em paralelo ao loop de tempo.
        val collector = scope.launch {
            bus.events.collect { event ->
                when (event) {
                    is RadioEvent.ApSeen -> candidates.add(
                        Target(
                            bssid = event.bssid,
                            essid = event.essid,
                            channel = event.channel,
                            rssi = event.rssi,
                        ),
                    )
                    is RadioEvent.HandshakeDetected -> handshakes++
                    else -> Unit
                }
            }
        }

        _state.value = State(phase = Phase.RECON)
        val personality = brain.nextPersonality()
        val deadline = clock.nowMillis() + personality.reconTimeSec * 1000
        while (clock.nowMillis() < deadline) delay(500)
        collector.cancel()

        // Interações: assoc (PMKID) + deauth nos alvos eleitos pelo cérebro.
        _state.value = _state.value.copy(phase = Phase.INTERACT, candidates = candidates.toList())
        run {
            repeat(personality.maxInteractions) {
                val target = brain.selectTarget(candidates) ?: return@run
                backend.setChannel(target.channel)
                backend.associate(target.bssid, null)
                if (target.clients > 0) {
                    backend.deauth(target.bssid, null, personality.deauthCount)
                }
            }
        }

        // Report do desfecho.
        _state.value = _state.value.copy(phase = Phase.REPORT)
        brain.reportEpochResult(
            EpochResult(
                handshakesCaptured = handshakes,
                pmkidCaptured = 0,
                interactionsAttempted = personality.maxInteractions,
                reward = handshakes.toDouble(),
                channelsVisited = candidates.map { it.channel }.distinct(),
            ),
        )
        _state.value = _state.value.copy(
            epoch = _state.value.epoch + 1,
            phase = Phase.IDLE,
            handshakesThisEpoch = handshakes,
        )
    }
}
