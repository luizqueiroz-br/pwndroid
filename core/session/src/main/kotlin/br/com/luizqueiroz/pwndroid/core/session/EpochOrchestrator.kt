package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.EpochResult
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
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
        var pmkids = 0

        // Coleta eventos do backend em paralelo ao loop de tempo.
        val collector = scope.launch {
            backend.events().collect { event ->
                when (event) {
                    is RadioEvent.ApSeen -> candidates.add(
                        Target(
                            bssid = event.bssid,
                            essid = event.essid,
                            channel = event.channel,
                            rssi = event.rssi,
                        ),
                    )
                    is RadioEvent.StationSeen -> candidates.find { it.bssid == event.bssid }?.let { ap ->
                        val index = candidates.indexOf(ap)
                        candidates[index] = ap.copy(clients = ap.clients + 1)
                    }
                    is RadioEvent.HandshakeDetected -> if (event.isPmkid) pmkids++ else handshakes++
                    else -> Unit
                }
            }
        }

        _state.value = _state.value.copy(phase = Phase.RECON)
        val personality = brain.nextPersonality()
        backend.applyPersonality(personality)
        backend.startRecon(personality.channels, dwellMs = 100)

        val deadline = clock.nowMillis() + personality.reconTimeSec * 1000
        while (clock.nowMillis() < deadline) {
            delay(500)
        }
        backend.stopRecon()
        collector.cancel()

        interact(personality, candidates)
        report(personality, candidates, handshakes, pmkids)
    }

    /** Fase INTERACT: assoc (PMKID) + deauth nos alvos eleitos pelo cérebro. */
    private suspend fun interact(personality: Personality, candidates: List<Target>) {
        _state.value = _state.value.copy(phase = Phase.INTERACT, candidates = candidates)
        run {
            repeat(personality.maxInteractions) {
                val target = brain.selectTarget(candidates) ?: return@run
                backend.setChannel(target.channel)
                backend.assoc(MacAddress.parse(target.bssid))
                if (target.clients > 0) {
                    backend.deauth(null, MacAddress.parse(target.bssid))
                }
            }
        }
    }

    /** Fase REPORT: desfecho da época ao cérebro e avança o contador. */
    private suspend fun report(
        personality: Personality,
        candidates: List<Target>,
        handshakes: Int,
        pmkids: Int,
    ) {
        _state.value = _state.value.copy(phase = Phase.REPORT)
        brain.reportEpochResult(
            EpochResult(
                handshakesCaptured = handshakes,
                pmkidCaptured = pmkids,
                interactionsAttempted = personality.maxInteractions,
                reward = (handshakes + pmkids).toDouble(),
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
