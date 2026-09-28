package br.com.luizqueiroz.pwndroid.core.brain

import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.Target
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Wrapper de [Brain] que aplica a personalidade configurada pelo usuário
 * (issue #59): a cada `nextPersonality()` lê o valor mais recente do fluxo
 * de config — mudança de personality com a sessão rodando vale na próxima
 * época, sem restart. O delegate (Thompson, Fixed...) continua dono do
 * aprendizado e da seleção de alvos; apenas a personalidade é sobreposta.
 *
 * Vive em `:core:brain` (Kotlin puro) porque `:core:session` não pode
 * depender de `:data`: o `:app` injeta um [ConfigBrain] construído sobre o
 * `config` Flow do ConfigStore, mapeando `AppConfig.personality`.
 */
class ConfigBrain(
    private val delegate: Brain,
    /** Emissão da personality configurada (DataStore emite valor corrente). */
    private val personalityUpdates: Flow<Personality>,
) : Brain {

    override val id: String = "config(${delegate.id})"

    /** Persona configurada mais recente (cacheada para o snapshot entre épocas). */
    private var configuredPersona: Personality? = null

    /** Personalidade configurada — vale nesta época. */
    override suspend fun nextPersonality(): Personality =
        personalityUpdates.first().also { persona ->
            configuredPersona = persona
            // A persona é vigente a partir de agora: publica no snapshot
            // para a UI ver a persona da época corrente (issue #26).
            _state.value = _state.value.copy(id = id, personality = persona)
        }

    override suspend fun selectTarget(candidates: List<Target>): Target? =
        delegate.selectTarget(candidates)

    override suspend fun reportEpochResult(result: EpochResult) {
        delegate.reportEpochResult(result)
        // O desfecho da época entra direto no snapshot (o delegate pode não
        // publicar lastEpoch no seu próprio estado) e a persona exibida é a
        // configurada pelo usuário, não a interna do delegate (#26).
        _state.value = _state.value.copy(
            id = id,
            personality = configuredPersona,
            lastEpoch = result,
        )
    }

    private val _state = MutableStateFlow(BrainSnapshot(id = id))
    override val state: StateFlow<BrainSnapshot> = _state.asStateFlow()

    override val diagnostics: Flow<String> get() = delegate.diagnostics
}
