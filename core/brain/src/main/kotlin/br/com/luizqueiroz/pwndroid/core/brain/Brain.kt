package br.com.luizqueiroz.pwndroid.core.brain

import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.Target
import kotlinx.coroutines.flow.Flow

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
 * Cérebro fixo: não aprende; apenas devolve a personalidade configurada.
 * Útil para modo AUTO e para testes.
 */
class FixedBrain(private val personality: Personality = Personality()) : Brain {
    override val id: String = "fixed"
    override suspend fun nextPersonality(): Personality = personality
    override suspend fun selectTarget(candidates: List<Target>): Target? = candidates.firstOrNull()
    override suspend fun reportEpochResult(result: EpochResult) { /* sem aprendizado */ }
    override val diagnostics: Flow<String> = kotlinx.coroutines.flow.emptyFlow()
}