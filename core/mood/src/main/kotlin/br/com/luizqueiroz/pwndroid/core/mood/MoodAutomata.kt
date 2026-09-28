package br.com.luizqueiroz.pwndroid.core.mood

import br.com.luizqueiroz.pwndroid.core.model.EpochResult

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
 * Entrada de época para o automata: o que a sessão produziu + o grid.
 *
 * `peersEncounters` é a soma de `peer.encounters` dos peers visíveis
 * (rede de suporte, acumulada entre épocas como no original);
 * `misses` são interações perdidas (assoc/deauth para alvo fora de
 * alcance) nesta época — porte de `num_missed` (track(miss=True)).
 */
data class EpochMoodInput(
    val epoch: Long,
    /** Handshakes capturados nesta época (qualquer um = época ativa). */
    val handshakes: Int,
    /** Época cega (nenhum AP visto). */
    val blind: Boolean,
    /** Interações perdidas nesta época (porte de num_missed). */
    val misses: Int = 0,
    /** Soma de encounters dos peers visíveis nesta época. */
    val peersEncounters: Int = 0,
)

/**
 * Máquina de humores (issue #11): porte 1:1 de `pwnagotchi/ai/epoch.py`
 * (`Epoch.next()`) + `pwnagotchi/automata.py` (`Automata.next_epoch()`).
 *
 * Por época, primeiro os contadores (epoch.py), depois as transições
 * (automata.py), nesta ordem:
 * 1. stale (misses > max_misses_for_recon): fator ≥ 2 → ANGRY; senão LONELY
 * 2. sad_for > 0: fator ≥ 2 → ANGRY; senão SAD
 * 3. bored_for > 0 → BORED
 * 4. active_for ≥ excited_num_epochs → EXCITED
 * 5. active_for ≥ 5 e rede de suporte para fator 5.0 → GRATEFUL
 *
 * Cada humor negativo (lonely/bored/sad/angry) vira GRATEFUL quando
 * `total_encounters / bond_encounters_factor ≥ fator_da_transição`
 * ("grateful instead of lonely/bored/sad/angry" no original).
 */
class MoodAutomata(private val config: MoodConfig = MoodConfig()) {

    // Contadores do Epoch (porte de epoch.py).
    private var inactiveFor = 0
    private var activeFor = 0
    private var sadFor = 0
    private var boredFor = 0
    private var numMissed = 0
    private var totalEncounters = 0L

    private var mood: Mood = Mood.LONELY

    /** Humor atual (o FaceState completo vem de [face]). */
    val current: Mood get() = mood

    /**
     * Avança uma época e recalcula o humor. O porte de `Epoch.next()`
     * roda antes das transições, exatamente como em `next_epoch()`.
     */
    fun onEpoch(input: EpochMoodInput): Mood {
        // Capturado ANTES de avançar (automata.py: was_stale/did_miss).
        numMissed += input.misses
        val wasStale = numMissed > config.maxMissesForRecon
        val didMiss = numMissed
        // Rede de suporte acumulada: os peers desta época já contam para
        // a decisão desta época (o original soma peer.encounters na hora).
        totalEncounters += input.peersEncounters

        // Porte de Epoch.next(): atividade = handshake na época.
        val active = input.handshakes > 0
        if (!active) {
            inactiveFor++
            activeFor = 0
        } else {
            activeFor++
            inactiveFor = 0
            sadFor = 0
            boredFor = 0
        }

        // sad e bored são mutuamente exclusivos; sad > bored.
        if (inactiveFor >= config.sadNumEpochs) {
            boredFor = 0
            sadFor++
        } else if (inactiveFor >= config.boredNumEpochs) {
            sadFor = 0
            boredFor++
        } else {
            sadFor = 0
            boredFor = 0
        }

        mood = nextMood(wasStale, didMiss)

        // epoch.py: num_missed é consumido e zerado a cada next().
        numMissed = 0
        return mood
    }

    /** Zera contadores e volta a LONELY (restart de sessão). */
    fun reset() {
        inactiveFor = 0
        activeFor = 0
        sadFor = 0
        boredFor = 0
        numMissed = 0
        totalEncounters = 0
        mood = Mood.LONELY
    }

    /**
     * Transições de `Automata.next_epoch()` na ordem original:
     * stale → sad → bored → excited → grateful.
     */
    private fun nextMood(wasStale: Boolean, didMiss: Int): Mood {
        if (wasStale) {
            val factor = didMiss.toDouble() / config.maxMissesForRecon
            return if (factor >= 2.0) {
                withSupportOr(factor, Mood.ANGRY)
            } else {
                withSupportOr(1.0, Mood.LONELY)
            }
        }
        if (sadFor > 0) {
            val factor = inactiveFor.toDouble() / config.sadNumEpochs
            return if (factor >= 2.0) {
                withSupportOr(factor, Mood.ANGRY)
            } else {
                withSupportOr(factor, Mood.SAD)
            }
        }
        if (boredFor > 0) {
            val factor = inactiveFor.toDouble() / config.boredNumEpochs
            return withSupportOr(factor, Mood.BORED)
        }
        if (activeFor >= config.excitedNumEpochs) {
            return Mood.EXCITED
        }
        if (activeFor >= 5 && hasSupportNetworkFor(5.0)) {
            return Mood.GRATEFUL
        }
        // Sem gatilho → bem-estar padrão (issue #11).
        return Mood.HAPPY
    }

    /**
     * `set_x` do original: cada humor negativo checa a rede de suporte
     * para o fator da transição — amigos suficientes anulam o humor
     * negativo ("grateful instead of lonely/bored/sad/angry").
     */
    private fun withSupportOr(factor: Double, negative: Mood): Mood =
        if (hasSupportNetworkFor(factor)) Mood.GRATEFUL else negative

    /** `_has_support_network_for`: total_encounters / bond_encounters_factor ≥ fator. */
    private fun hasSupportNetworkFor(factor: Double): Boolean =
        totalEncounters.toDouble() / config.bondEncountersFactor >= factor

    /** Estado facial derivado do humor. */
    fun face(): FaceState = when (mood) {
        Mood.LONELY -> FaceState(mood, "(⌒▽⌒)", "Estou sozinho, procurando redes…")
        Mood.BORED -> FaceState(mood, "(-__-)", "Zzz… nada interessante por perto.")
        Mood.SAD -> FaceState(mood, "(╥﹏╥)", "Sem handshakes faz tempo…")
        Mood.ANGRY -> FaceState(mood, "(▼ヘ▼#)", "Tô com raiva! Ninguém aparece!")
        Mood.EXCITED -> FaceState(mood, "ヽ(⌒▽⌒)ノ", "BOA! Peguei um handshake!")
        Mood.GRATEFUL -> FaceState(mood, "(^‿^)", "Tenho amigos por perto!")
        Mood.HAPPY -> FaceState(mood, "(•‿•)", "Satisfeito.")
    }
}
