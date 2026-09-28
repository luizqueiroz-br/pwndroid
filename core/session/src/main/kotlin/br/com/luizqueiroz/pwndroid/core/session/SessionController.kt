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
import kotlinx.coroutines.flow.Flow
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
    /**
     * Fluxo de troca de modo em tempo real (issue #59): observado enquanto a
     * sessão roda; cada emissão vale a partir da próxima época, sem restart.
     * `null` = sem atualização de modo em tempo real (comportamento antigo).
     * A preferência de backend NÃO é observável aqui — exige nova sessão
     * (o backend é selecionado apenas no start).
     */
    private val modeUpdates: Flow<br.com.luizqueiroz.pwndroid.core.model.PwnMode>? = null,
) {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private var running = false
    private var orchestrator: EpochOrchestrator? = null
    private var startedBackend: StartedBackend? = null
    private var collector: Job? = null
    private var modeWatcher: Job? = null

    /** Inicia a sessão no [mode]; ignorado enquanto uma sessão já roda. */
    fun start(
        mode: PwnMode = PwnMode.AUTO,
        maxEpochs: Long = Long.MAX_VALUE,
        /**
         * Backend preferido (config do usuário, issue #13): passado ao
         * selector no start. Trocar a preferência com sessão rodando não
         * afeta a sessão atual — o backend é selecionado só aqui, vale
         * até o stop (a próxima sessão lê a config de novo).
         */
        preferredBackend: BackendId? = null,
    ) {
        if (running) return
        running = true
        scope.launch {
            if (!running) return@launch
            val backend = runCatching { selector.select(environment, preferredBackend) }.getOrNull()
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
            // Atualizações de modo em tempo real (issue #59): cada emissão
            // troca o modo do orquestrador — vale na próxima época.
            val updates = modeUpdates
            if (updates != null) {
                modeWatcher = scope.launch {
                    updates.collect { orch.setMode(it) }
                }
            }
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
        modeWatcher?.cancel()
        modeWatcher = null
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
