package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.radio.StartedBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estado observável do controlador para a UI e a web API: qual backend
 * está ativo, o [SessionState] corrente e um erro de seleção, se houver.
 */
data class SessionUiState(
    val backend: BackendId? = null,
    val session: SessionState = SessionState(),
    val error: String? = null,
)

/**
 * Dono do ciclo de vida da sessão: seleciona um backend, cria o
 * [EpochOrchestrator] e expõe o estado como StateFlow compartilhado.
 *
 * Idempotente por design: start duplo não reinicia a sessão; stop duplo
 * não derruba o rádio de uma sessão já encerrada.
 */
class SessionController(
    private val selector: BackendSelector,
    private val environment: BackendEnvironment,
    private val brain: Brain,
    private val bus: EventBus,
    private val config: SessionConfig = SessionConfig(),
    /** Escopo da sessão (injetável: testes usam dispatcher de teste). */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Espera da janela de recon, repassada ao orquestrador. */
    private val waitFor: suspend (millis: Long) -> Unit = { delay(it) },
    /** Registro compartilhado do estado (opcional; UI/web API observam). */
    private val registry: SessionRegistry? = null,
) {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private var running = false
    private var orchestrator: EpochOrchestrator? = null
    private var startedBackend: StartedBackend? = null
    private var collector: Job? = null

    /** Inicia a sessão no [mode]; ignorado enquanto uma sessão já roda. */
    fun start(mode: PwnMode = PwnMode.AUTO, maxEpochs: Long = Long.MAX_VALUE) {
        if (running) return
        running = true
        scope.launch {
            if (!running) return@launch
            val backend = runCatching { selector.select(environment) }.getOrNull()
            if (backend == null) {
                running = false
                _state.value = SessionUiState(error = "nenhum backend de rádio disponível neste device")
                return@launch
            }
            startedBackend = backend
            _state.value = SessionUiState(backend = backend.backendId)
            val orch = EpochOrchestrator(backend, brain, bus, config, waitFor)
            orchestrator = orch
            collector = scope.launch {
                orch.state.collect { session ->
                    val ui = _state.value.copy(session = session)
                    _state.value = ui
                    registry?.publish(ui)
                }
            }
            orch.start(scope, mode, maxEpochs)
        }
    }

    /** Para a sessão e libera o rádio; seguro chamar sem sessão ativa. */
    fun stop() {
        if (!running) return
        running = false
        orchestrator?.stop()
        orchestrator = null
        collector?.cancel()
        collector = null
        startedBackend?.let { backend ->
            scope.launch {
                runCatching { backend.shutdown() }
            }
        }
        startedBackend = null
        _state.value = SessionUiState()
        registry?.publish(SessionUiState())
    }
}
