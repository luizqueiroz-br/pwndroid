package br.com.luizqueiroz.pwndroid.core.mood

import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Humores do tamagotchi, espelhando automata.py do original.
 */
enum class Mood {
    LONELY, BORED, SAD, ANGRY, EXCITED, GRATEFUL, HAPPY,
}

/**
 * Estado facial completo: humor + kaomoji + texto de status.
 */
data class FaceState(
    val mood: Mood,
    val kaomoji: String,
    val status: String,
)

/**
 * Rede de suporte: mapeia (humor, contadores) → FaceState, igual ao
 * automata.py do pwnagotchi (com a regra de "grateful" anulando os
 * humores negativos quando há peers próximos).
 */
class MoodAutomata(private val bus: EventBus) {

    private var mood: Mood = Mood.LONELY
    private var peerCount: Int = 0
    private var lastHandshakeMillis: Long = 0
    private var epochsSinceHandshake: Int = 0

    /** Consumes eventos do bus no [scope]. */
    fun start(scope: CoroutineScope) {
        bus.subscribe<RadioEvent.HandshakeDetected>(scope) {
            lastHandshakeMillis = System.currentTimeMillis()
            epochsSinceHandshake = 0
            mood = Mood.HAPPY
        }
        bus.subscribe<RadioEvent.Error>(scope) {
            mood = Mood.SAD
        }
    }

    /** Atualiza contadores de fim de época e recalcula o humor. */
    fun onEpochEnd(handshakesThisEpoch: Int, peersSeen: Int): FaceState {
        peerCount = peersSeen
        if (handshakesThisEpoch == 0) {
            epochsSinceHandshake++
        } else {
            epochsSinceHandshake = 0
        }
        mood = when {
            peerCount > 0 && mood in NEGATIVE -> Mood.GRATEFUL
            epochsSinceHandshake >= 8 -> Mood.ANGRY
            epochsSinceHandshake >= 4 -> Mood.SAD
            epochsSinceHandshake >= 2 -> Mood.BORED
            handshakesThisEpoch > 1 -> Mood.EXCITED
            else -> Mood.LONELY
        }
        return face()
    }

    /** Estado facial atual. */
    fun face(): FaceState = when (mood) {
        Mood.LONELY -> FaceState(mood, "(⌒▽⌒)", "Estou sozinho, procurando redes…")
        Mood.BORED -> FaceState(mood, "(-__-)", "Zzz… nada interessante por perto.")
        Mood.SAD -> FaceState(mood, "(╥﹏╥)", "Sem handshakes faz tempo…")
        Mood.ANGRY -> FaceState(mood, "(▼ヘ▼#)", "Tô com raiva! Ninguém aparece!")
        Mood.EXCITED -> FaceState(mood, "ヽ(⌒▽⌒)ノ", "BOA! Peguei um handshake!")
        Mood.GRATEFUL -> FaceState(mood, "(^‿^)", "Tenho amigos por perto!")
        Mood.HAPPY -> FaceState(mood, "(•‿•)", "Satisfeito.")
    }

    private companion object {
        val NEGATIVE = setOf(Mood.LONELY, Mood.BORED, Mood.SAD, Mood.ANGRY)
    }
}