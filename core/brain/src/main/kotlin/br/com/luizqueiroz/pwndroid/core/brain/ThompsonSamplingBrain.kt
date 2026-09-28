package br.com.luizqueiroz.pwndroid.core.brain

import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.Target
import java.util.Locale
import java.util.Random
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Contadores Beta(α,β) de uma arma (param, value) do Thompson (issue #27). */
data class ArmCounts(val value: String, val alpha: Double, val beta: Double)

/**
 * Persistência das armas (issue #27): a tabela `brain_arm` (:data, Room)
 * implementa via [RoomArmStore]; testes usam fake in-memory. Sobrevive a
 * restarts — carregamento lazy por brainId.
 */
interface ArmStore {
    suspend fun armsOf(brainId: String, param: String): List<ArmCounts>

    /** Cria a arma com os contadores dados (prior (1,1) no primeiro uso). */
    suspend fun upsert(brainId: String, param: String, value: String, alpha: Double, beta: Double)

    /** Atualiza os contadores após o decay/reward da época. */
    suspend fun updateCounts(brainId: String, param: String, value: String, alpha: Double, beta: Double)
}

/**
 * Cérebro Thompson Sampling (issue #27) — o padrão do pwndroid: leve e
 * estável (o fork jayofelony removeu o A2C por instabilidade; bandit é o
 * caminho pragmático).
 *
 * Para cada parâmetro da [Personality] há um conjunto discreto de valores
 * ("armas") com contadores Beta(α,β), prior uniforme (1,1). A decisão
 * amostra θ~Beta de cada arma e escolhe a maior (Thompson clássico). O
 * desfecho da época é Bernoulli: handshake/pmkid → α+=1 nas armas usadas;
 * senão β+=1. Decay multiplicativo (×[decayFactor] por época) adapta a
 * ambientes que mudam.
 *
 * α/β persistem no [ArmStore] (tabela `brain_arm`) e sobrevivem a
 * restarts; o RNG com seed torna os testes determinísticos.
 */
class ThompsonSamplingBrain(
    private val store: ArmStore,
    seed: Long = 0L,
    /** Decay multiplicativo de α/β a cada época (1.0 desliga). */
    private val decayFactor: Double = DEFAULT_DECAY,
) : Brain {

    override val id: String = "thompson"

    private val rng = Random(seed)

    private val _state = MutableStateFlow(BrainSnapshot(id = id))
    override val state: StateFlow<BrainSnapshot> = _state.asStateFlow()

    private val _diagnostics = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val diagnostics: Flow<String> get() = _diagnostics

    /** Decisão corrente (param → value) usada no reward da época. */
    private var decision: Map<String, String> = emptyMap()

    private var epochs = 0L

    override suspend fun nextPersonality(): Personality {
        val chosen = mutableMapOf<String, String>()
        val preferred = mutableMapOf<String, String>()
        for ((param, values) in ARM_SPACE) {
            val counts = ensureArms(param, values)
            // Thompson: amostra θ de cada arma e escolhe a maior.
            val sampled = counts.map { (value, arm) -> value to sampleBeta(arm.alpha, arm.beta) }
            chosen[param] = sampled.maxByOrNull { it.second }?.first ?: values.first()
            // Arma preferida para o snapshot: maior média posterior α/(α+β).
            val best = counts.values.maxByOrNull { it.alpha / (it.alpha + it.beta) }
                ?: counts.values.first()
            preferred[param] = formatArm(best)
        }
        decision = chosen
        epochs++
        val persona = toPersonality(chosen)
        _state.value = _state.value.copy(
            personality = persona,
            extra = preferred + mapOf(EXTRA_EPOCHS to epochs.toString()),
        )
        return persona
    }

    override suspend fun selectTarget(candidates: List<Target>): Target? = candidates.firstOrNull()

    override suspend fun reportEpochResult(result: EpochResult) {
        val success = result.handshakesCaptured + result.pmkidCaptured > 0
        for (param in ARM_SPACE.keys) {
            updateArms(param, decision[param], success)
        }
        _state.value = _state.value.copy(lastEpoch = result)
        _diagnostics.tryEmit(
            "época=$epochs success=$success " +
                ARM_SPACE.keys.joinToString(" ") { "$it=${decision[it]}" },
        )
    }

    /**
     * Decay multiplicativo (×[decayFactor]) nas armas do param e reward
     * Bernoulli na escolhida: sucesso → α+=1; fracasso → β+=1. Só grava no
     * [store] quando os contadores mudam de fato.
     */
    private suspend fun updateArms(param: String, chosenValue: String?, success: Boolean) {
        val arms = ensureArms(param, ARM_SPACE.getValue(param))
        for (value in ARM_SPACE.getValue(param)) {
            val arm = arms.getValue(value)
            var alpha = arm.alpha * decayFactor
            var beta = arm.beta * decayFactor
            if (value == chosenValue) {
                if (success) alpha += 1.0 else beta += 1.0
            }
            if (alpha != arm.alpha || beta != arm.beta) {
                store.updateCounts(id, param, value, alpha, beta)
            }
        }
    }

    /** Garante uma linha por valor do param (prior (1,1) no primeiro uso). */
    private suspend fun ensureArms(param: String, values: List<String>): Map<String, ArmCounts> {
        val existing = store.armsOf(id, param).associateBy { it.value }.toMutableMap()
        for (value in values) {
            if (existing[value] == null) {
                existing[value] = ArmCounts(value, 1.0, 1.0)
                store.upsert(id, param, value, 1.0, 1.0)
            }
        }
        return existing
    }

    private fun toPersonality(chosen: Map<String, String>): Personality = Personality(
        reconTimeSec = chosen.getValue(PARAM_RECON).toLong(),
        apTtlSec = chosen.getValue(PARAM_AP_TTL).toLong(),
        staTtlSec = chosen.getValue(PARAM_STA_TTL).toLong(),
        minRssi = chosen.getValue(PARAM_RSSI).toInt(),
        maxInteractions = chosen.getValue(PARAM_INTERACTIONS).toInt(),
        channels = if (chosen.getValue(PARAM_CHANNELS) == CHANNEL_TOP3) {
            setOf(1, 6, 11)
        } else {
            (1..13).toSet()
        },
    )

    private fun formatArm(arm: ArmCounts): String =
        String.format(Locale.ROOT, "%s (α=%.1f β=%.1f)", arm.value, arm.alpha, arm.beta)

    /** Amostra θ~Beta(α,β) via razão de gamas (Marsaglia-Tsang). */
    private fun sampleBeta(alpha: Double, beta: Double): Double {
        val x = sampleGamma(alpha)
        val y = sampleGamma(beta)
        return x / (x + y)
    }

    /** Amostra Gamma(shape) — Marsaglia-Tsang; shape<1 via boost. */
    private fun sampleGamma(shape: Double): Double {
        require(shape > 0.0) { "shape deve ser positivo" }
        if (shape < 1.0) {
            val boost = rng.nextDouble().pow(1.0 / shape)
            return sampleGamma(shape + 1.0) * boost
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            val x = rng.nextGaussian()
            val v = (1.0 + c * x).pow(3)
            if (v <= 0.0) continue
            val u = rng.nextDouble()
            val x2 = x * x
            if (u < 1.0 - 0.0331 * x2 * x2) return d * v
            if (ln(u) < 0.5 * x2 + d * (1.0 - v + ln(v))) return d * v
        }
    }

    companion object {
        /** Decay padrão: ×0.98 por época (issue #27). */
        const val DEFAULT_DECAY = 0.98

        private const val EXTRA_EPOCHS = "epochs"
        private const val CHANNEL_TOP3 = "top3"
        private const val PARAM_RECON = "recon_time"
        private const val PARAM_RSSI = "min_rssi"
        private const val PARAM_INTERACTIONS = "max_interactions"
        private const val PARAM_AP_TTL = "ap_ttl"
        private const val PARAM_STA_TTL = "sta_ttl"
        private const val PARAM_CHANNELS = "channel_preset"

        /** Espaço de ações discretizado por parâmetro (issue #27). */
        val ARM_SPACE: Map<String, List<String>> = linkedMapOf(
            PARAM_RECON to listOf("15", "20", "25", "30", "35", "40", "60"),
            PARAM_RSSI to listOf("-90", "-85", "-80", "-75", "-200"),
            PARAM_INTERACTIONS to listOf("1", "2", "3", "4", "5", "6"),
            PARAM_AP_TTL to listOf("60", "120", "300", "600"),
            PARAM_STA_TTL to listOf("30", "45", "90", "180"),
            PARAM_CHANNELS to listOf("all", "top3"),
        )
    }
}
