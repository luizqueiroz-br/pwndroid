package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.EpochResult
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.model.Target
import br.com.luizqueiroz.pwndroid.core.radio.StartedBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Orquestrador de épocas — porte do agent.py do pwnagotchi:
 * recon (recon_time s) → filtros → seleção de alvo pelo cérebro →
 * interações (assoc/deauth) → report ao cérebro e humores.
 *
 * Modos: [PwnMode.MANUAL] recon apenas (nunca interage);
 * [PwnMode.AUTO] ciclo completo; [PwnMode.PASSIVE] igual ao manual.
 */
class EpochOrchestrator(
    private val backend: StartedBackend,
    private val brain: Brain,
    private val bus: EventBus,
    private val config: SessionConfig = SessionConfig(),
    /** Espera da janela de recon; injetável (testes avançam o relógio). */
    private val waitFor: suspend (millis: Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(SessionState())
    val state = _state.asStateFlow()

    private var job: Job? = null

    /** BSSIDs já capturados nesta sessão (não repetir interações). */
    private val capturedBssids = mutableSetOf<String>()

    /** Contagem de interações por BSSID na sessão (max_interactions). */
    private val interactionsByBssid = mutableMapOf<String, Int>()

    /** Inicia o ciclo de épocas no [mode]. Idempotente. */
    fun start(scope: CoroutineScope, mode: PwnMode = PwnMode.AUTO, maxEpochs: Long = Long.MAX_VALUE) {
        if (job != null) return
        _state.value = SessionState(mode = mode)
        capturedBssids.clear()
        interactionsByBssid.clear()
        job = scope.launch {
            var epoch = 0L
            while (currentCoroutineContext().isActive && epoch < maxEpochs) {
                runEpoch(scope)
                epoch++
                if (_state.value.stopped) break
            }
            backend.shutdown()
        }
    }

    /** Para o ciclo (chamado pelo FGS ou pela UI). */
    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(phase = EpochPhase.IDLE)
    }

    private suspend fun runEpoch(scope: CoroutineScope) {
        val candidates = mutableListOf<Target>()
        var handshakes = 0
        var pmkids = 0
        var blind = true

        // A personalidade da época é pedida ao cérebro antes do recon.
        val personality = brain.nextPersonality()

        // Coleta eventos do backend em paralelo ao loop de tempo.
        val collector = scope.launch {
            backend.events().collect { event ->
                when (event) {
                    is RadioEvent.ApSeen -> {
                        blind = false
                        if (accepts(event.essid, event.rssi, personality.minRssi)) {
                            candidates.add(
                                Target(
                                    bssid = event.bssid,
                                    essid = event.essid,
                                    channel = event.channel,
                                    rssi = event.rssi,
                                ),
                            )
                        }
                    }
                    is RadioEvent.StationSeen -> candidates.find { it.bssid == event.bssid }?.let { ap ->
                        val index = candidates.indexOf(ap)
                        candidates[index] = ap.copy(clients = ap.clients + 1)
                    }
                    is RadioEvent.HandshakeDetected -> {
                        capturedBssids.add(event.bssid)
                        if (event.isPmkid) pmkids++ else handshakes++
                    }
                    else -> Unit
                }
            }
        }
        yield()

        _state.value = _state.value.copy(phase = EpochPhase.RECON)
        val blindEpochs = _state.value.blindEpochs
        val reconMs = if (blindEpochs > 0) {
            (personality.reconTimeSec * config.reconInactiveMultiplier * 1000).toLong()
        } else {
            personality.reconTimeSec * 1000
        }
        backend.applyPersonality(personality)
        backend.startRecon(personality.channels, dwellMs = 100)

        waitFor(reconMs)
        backend.stopRecon()
        collector.cancel()

        if (_state.value.mode == PwnMode.AUTO) {
            interact(personality, candidates)
        }

        val isBlind = candidates.isEmpty() && blind
        report(personality, candidates, handshakes, pmkids, isBlind)
        checkBlindEpochs(isBlind)
    }

    /** Fase INTERACT: assoc (PMKID) + deauth nos alvos eleitos pelo cérebro. */
    private suspend fun interact(personality: Personality, candidates: List<Target>) {
        _state.value = _state.value.copy(phase = EpochPhase.INTERACT, candidates = candidates)
        run {
            repeat(personality.maxInteractions) {
                val eligible = candidates.filter { isEligible(it, personality.maxInteractions) }
                val target = brain.selectTarget(eligible) ?: return@run
                interactionsByBssid.merge(target.bssid, 1, Int::plus)
                backend.setChannel(target.channel)
                backend.assoc(MacAddress.parse(target.bssid))
                if (target.clients > 0) {
                    backend.deauth(null, MacAddress.parse(target.bssid))
                }
            }
        }
    }

    /** Alvo ainda elegível: não capturado e dentro de max_interactions. */
    private fun isEligible(target: Target, maxInteractions: Int): Boolean =
        target.bssid !in capturedBssids &&
            (interactionsByBssid[target.bssid] ?: 0) < maxInteractions

    /** Fase REPORT: desfecho da época ao cérebro e humores, e avança. */
    private suspend fun report(
        personality: Personality,
        candidates: List<Target>,
        handshakes: Int,
        pmkids: Int,
        blind: Boolean,
    ) {
        _state.value = _state.value.copy(phase = EpochPhase.REPORT)
        val previous = _state.value
        brain.reportEpochResult(
            EpochResult(
                handshakesCaptured = handshakes,
                pmkidCaptured = pmkids,
                interactionsAttempted = personality.maxInteractions,
                reward = (handshakes + pmkids).toDouble(),
                channelsVisited = candidates.map { it.channel }.distinct(),
            ),
        )
        bus.publish(
            EpochFinished(
                epoch = previous.epoch + 1,
                handshakes = handshakes,
                pmkids = pmkids,
                blind = blind,
            ),
        )
        _state.value = _state.value.copy(
            epoch = previous.epoch + 1,
            phase = EpochPhase.IDLE,
            handshakes = previous.handshakes + handshakes,
            pmkids = previous.pmkids + pmkids,
            blindEpochs = if (blind) previous.blindEpochs + 1 else 0,
        )
    }

    /** Parada protetiva: sem APs por maxBlindEpochs épocas seguidas. */
    private fun checkBlindEpochs(isBlind: Boolean) {
        val current = _state.value
        if (isBlind && current.blindEpochs >= config.maxBlindEpochs) {
            bus.publish(BackendFatal("sem APs por ${current.blindEpochs} épocas cegas"))
            _state.value = current.copy(
                stopped = true,
                stopReason = "sem APs por ${current.blindEpochs} épocas cegas (mon_max_blind_epochs)",
            )
        }
    }

    /** Filtros da sessão: regex de ESSID, whitelist e min_rssi. */
    private fun accepts(essid: String?, rssi: Int, minRssi: Int): Boolean {
        if (essid != null && essid in config.whitelist) return false
        if (rssi < minRssi) return false
        val filter = config.essidFilter ?: return true
        return essid?.let { filter.containsMatchIn(it) } ?: false
    }

    /** Fim de uma época, publicado no bus para humores/persistência. */
    data class EpochFinished(
        val epoch: Long,
        val handshakes: Int,
        val pmkids: Int,
        val blind: Boolean,
    )

    /** Erro fatal de rádio que para a sessão (sem reboot do device). */
    data class BackendFatal(val message: String)
}
