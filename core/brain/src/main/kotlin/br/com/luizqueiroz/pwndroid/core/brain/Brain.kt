package br.com.luizqueiroz.pwndroid.core.brain

import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.Target
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Cérebro plugável — decide a personalidade da época seguinte a partir do
 * histórico. Thompson Sampling é o padrão; A2C é opcional (flag-gated).
 */
interface Brain {
    /** Identificador estável ("thompson", "a2c", "fixed"...). */
    val id: String

    /** Personalidade para a época que começa agora. */
    suspend fun nextPersonality(): Personality

    /** Observa os alvos do recon da época atual e elege o alvo. */
    suspend fun selectTarget(candidates: List<Target>): Target?

    /** Reporta o desfecho da época (sucessos, falhas, reward). */
    suspend fun reportEpochResult(result: EpochResult)

    /**
     * Snapshot observável do cérebro (issue #26): a UI mostra quem está
     * decidindo, a personalidade vigente e o desfecho da última época —
     * sem conhecer a implementação.
     */
    val state: StateFlow<BrainSnapshot>

    /** Fluxo de logs/diagnóstico do aprendizado, para a UI. */
    val diagnostics: Flow<String>
}

/** Desfecho agregado de uma época. */
data class EpochResult(
    val handshakesCaptured: Int,
    val pmkidCaptured: Int,
    val interactionsAttempted: Int,
    val reward: Double,
    /** Canais hoppnados na época, para aprendizado espacial. */
    val channelsVisited: List<Int> = emptyList(),
)

/**
 * Snapshot do estado interno do cérebro, para a UI (issue #26): id,
 * personalidade vigente, desfecho da última época reportada e métricas
 * livres (`extra`) — o Thompson publica armas α/β na #27.
 */
data class BrainSnapshot(
    val id: String,
    val personality: Personality? = null,
    val lastEpoch: EpochResult? = null,
    /** Métricas legíveis (ex.: "alpha=3.0 beta=1.0"). */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * Cérebro fixo: não aprende; apenas devolve a personalidade configurada.
 * Usado nos modos MANUAL e AUTO (a persona editável na UI chega ao
 * backend via ConfigBrain/EpochOrchestrator) e nos testes.
 */
class FixedBrain(private val personality: Personality = Personality()) : Brain {
    override val id: String = "fixed"

    override suspend fun nextPersonality(): Personality = personality

    override suspend fun selectTarget(candidates: List<Target>): Target? = candidates.firstOrNull()

    override suspend fun reportEpochResult(result: EpochResult) {
        // Sem aprendizado, mas o snapshot registra o desfecho (UI).
        _state.value = _state.value.copy(lastEpoch = result)
    }

    private val _state = MutableStateFlow(BrainSnapshot(id = id, personality = personality))
    override val state: StateFlow<BrainSnapshot> = _state.asStateFlow()

    override val diagnostics: Flow<String> = emptyFlow()
}

