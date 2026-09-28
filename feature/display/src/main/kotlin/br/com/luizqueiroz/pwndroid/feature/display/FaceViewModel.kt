package br.com.luizqueiroz.pwndroid.feature.display

import br.com.luizqueiroz.pwndroid.core.mood.Mood
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Kaomojis por humor — porte da seção `ui.face` do `defaults.toml` do
 * pwnagotchi original. Configurável (a #59 já plumbou personality; faces
 * customizáveis entram junto com o config UI).
 */
data class FaceTable(
    val kaomojis: Map<Mood, String> = defaultKaomojis(),
) {
    fun of(mood: Mood): String = kaomojis.getValue(mood)

    companion object {
        fun defaultKaomojis(): Map<Mood, String> = mapOf(
            Mood.LONELY to "(╯°□°)╯",
            Mood.BORED to "(-_-)",
            Mood.SAD to "(╥﹏╥)",
            Mood.ANGRY to "(⌐■_■)",
            Mood.EXCITED to "(ᵔ◡◡ᵔ)",
            Mood.GRATEFUL to "(^‿^)",
            Mood.HAPPY to "(•‿‿•)",
        )
    }
}

/**
 * Estado facial completo consumido pelo [FaceRenderer] — campos da
 * issue #12: mood, expression, lines, mode, peers, handshakes.
 */
data class FaceFrame(
    val mood: Mood,
    /** Kaomoji (com variação de blink aplicada). */
    val expression: String,
    /** Linhas de stats abaixo do rosto (epoch, canal, APs, handshakes, uptime). */
    val lines: List<String>,
    val mode: String,
    val peers: Int,
    val handshakes: Int,
)

/**
 * Estado do renderizador: o [FaceFrame] corrente + se o rosto está
 * piscando neste tick (ticker de 1 FPS).
 */
data class FaceUiState(
    val frame: FaceFrame,
    /** Tick do blink atual (variação do kaomoji). */
    val blink: Boolean = false,
)

/**
 * Estado + ticker do rosto (issue #12): consome [FaceFrame] publicado
 * pela sessão e controla o blink. A renderização em si é o
 * [FaceRenderer] Compose; esta classe é o estado puro, testável.
 */
class FaceViewModel(
    private val faceTable: FaceTable = FaceTable(),
    /** Random com semente por sessão (injetável para testes). */
    private val random: Random = Random.Default,
) {
    // frameLines etc. antes de _state: o inicializador de _state chama
    // frameOf() e precisa dos stats já inicializados.
    private var currentMood: Mood = Mood.LONELY
    private var frameLines: List<String> = emptyList()
    private var frameMode: String = "auto"
    private var framePeers: Int = 0
    private var frameHandshakes: Int = 0

    private val _state = MutableStateFlow(FaceUiState(frameOf(Mood.LONELY)))
    val state: StateFlow<FaceUiState> = _state.asStateFlow()

    /**
     * Atualiza o frame publicado (kaomoji do mood + stats). O blink é
     * aplicado quando o ticker marca (1 em cada [blinkEveryTicks] ticks,
     * 50% de chance — aleatório, semente por sessão).
     */
    fun publish(
        mood: Mood,
        lines: List<String>,
        mode: String,
        peers: Int,
        handshakes: Int,
    ) {
        currentMood = mood
        frameLines = lines
        frameMode = mode
        framePeers = peers
        frameHandshakes = handshakes
        _state.value = FaceUiState(frame = frameOf(mood))
    }

    /** Um tick do relógio de 1 FPS: sorteia o blink. */
    fun tick(blinkEveryTicks: Int = 4) {
        val shouldBlink = random.nextInt(blinkEveryTicks) == 0
        _state.value = _state.value.copy(blink = shouldBlink)
    }

    /** Frame do humor com o kaomoji da tabela (e blink de olhos). */
    private fun frameOf(mood: Mood): FaceFrame = FaceFrame(
        mood = mood,
        expression = faceTable.of(mood),
        lines = frameLines,
        mode = frameMode,
        peers = framePeers,
        handshakes = frameHandshakes,
    )

    /** Volta ao estado inicial. */
    fun reset() {
        inactive()
    }

    private fun inactive() {
        currentMood = Mood.LONELY
        frameLines = emptyList()
        frameMode = "auto"
        framePeers = 0
        frameHandshakes = 0
        _state.value = FaceUiState(frameOf(Mood.LONELY))
    }
}

/** Ticker de 1 FPS — fidelidade ao e-ink (e economia de bateria). */
suspend fun faceTicker(onTick: () -> Unit) {
    while (true) {
        onTick()
        delay(1_000)
    }
}
