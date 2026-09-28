package br.com.luizqueiroz.pwndroid.ui.home

import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.BrainSnapshot
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.mood.EpochMoodInput
import br.com.luizqueiroz.pwndroid.core.mood.Mood
import br.com.luizqueiroz.pwndroid.core.mood.MoodAutomata
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.session.EpochOrchestrator
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.core.session.SessionState
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState
import br.com.luizqueiroz.pwndroid.feature.display.FaceFrame
import br.com.luizqueiroz.pwndroid.feature.display.FaceTable
import br.com.luizqueiroz.pwndroid.feature.display.FaceUiState
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ponte entre a sessão e o rosto (issue #13): observa o [SessionRegistry]
 * e o [EventBus], alimenta o [MoodAutomata] com o desfecho de cada época
 * e publica o [FaceUiState] renderizado pela tela Home.
 *
 * O humor é decidido pelo automata (core/mood, porte do automata.py);
 * esta classe só faz o wiring: época → mood → frame (kaomoji + stats).
 * A tela Home é burra: coleciona [face] e renderiza.
 */
class HomeState(
    private val registry: SessionRegistry,
    private val bus: EventBus,
    /** Cérebro injetado — o snapshot observável alimenta a Home (issue #26). */
    private val brain: Brain? = null,
    private val automata: MoodAutomata = MoodAutomata(),
    private val faceTable: FaceTable = FaceTable(),
    /** Random injetável (testes determinísticos do blink). */
    private val random: Random = Random.Default,
) {
    private val _face = MutableStateFlow(FaceUiState(frameOf(Mood.LONELY, SessionState())))
    /** Estado facial corrente (frame + blink) — consumido pelo FaceRenderer. */
    val face: StateFlow<FaceUiState> = _face.asStateFlow()

    private val _mood = MutableStateFlow(Mood.LONELY)
    /** Humor atual, para o chip colorido da tela. */
    val mood: StateFlow<Mood> = _mood.asStateFlow()

    /** Snapshot observável do cérebro (id, persona, última época) — issue #26. */
    val brainState: StateFlow<BrainSnapshot> = brain?.state ?: MutableStateFlow(BrainSnapshot(id = "none"))

    private var subscriptions: List<Job>? = null

    /** Assina o bus e o registry; chamado uma vez (LaunchedEffect na tela). */
    fun observe(scope: CoroutineScope) {
        if (subscriptions != null) return
        subscriptions = listOf(
            bus.subscribe<EpochOrchestrator.EpochFinished>(scope) { onEpoch(it) },
            scope.launch {
                registry.state.collect { publishFrame(automata.current) }
            },
        )
    }

    /** Para de coletar e volta ao estado inicial (só para em testes). */
    fun dispose() {
        subscriptions?.forEach { it.cancel() }
        subscriptions = null
        automata.reset()
        _mood.value = Mood.LONELY
        _face.value = FaceUiState(frameOf(Mood.LONELY, SessionState()))
    }

    /** Um tick do relógio de 1 FPS: sorteia o blink (injetável p/ testes). */
    fun tick(blinkEveryTicks: Int = 4) {
        val shouldBlink = random.nextInt(blinkEveryTicks) == 0
        _face.value = _face.value.copy(blink = shouldBlink)
    }

    /** Desfecho de época: alimenta o automata e republica o frame. */
    private fun onEpoch(epoch: EpochOrchestrator.EpochFinished) {
        // Uma época cega conta como miss (o rádio não viu nada); handshakes
        // > 0 marcam atividade (porte do anyActivity simplificado p/ v0.1).
        val mood = automata.onEpoch(
            EpochMoodInput(
                epoch = epoch.epoch,
                handshakes = epoch.handshakes,
                blind = epoch.blind,
                misses = if (epoch.blind) 1 else 0,
                peersEncounters = 0,
            ),
        )
        _mood.value = mood
        publishFrame(mood)
    }

    private fun publishFrame(mood: Mood) {
        _face.value = FaceUiState(frameOf(mood, registry.state.value.session))
    }

    private fun frameOf(mood: Mood, session: SessionState): FaceFrame = FaceFrame(
        mood = mood,
        expression = faceTable.of(mood),
        lines = statsLines(session),
        mode = modeLabel(session.mode),
        peers = 0,
        handshakes = session.handshakes,
    )

    /** Linhas de stats da sessão, formato terminal do original. */
    private fun statsLines(session: SessionState): List<String> = listOf(
        "epoch=${session.epoch}",
        "phase=${session.phase.name.lowercase()}",
        "aps=${session.candidates.size}",
        "handshakes=${session.handshakes}",
        "pmkids=${session.pmkids}",
    )

    /** Rótulo de modo estilo pwnagotchi (MANU/AUTO/AI). */
    internal fun modeLabel(mode: PwnMode): String = when (mode) {
        PwnMode.MANUAL -> "manu"
        PwnMode.AUTO -> "auto"
        PwnMode.AI -> "ai"
        PwnMode.PASSIVE -> "passive"
    }
}
