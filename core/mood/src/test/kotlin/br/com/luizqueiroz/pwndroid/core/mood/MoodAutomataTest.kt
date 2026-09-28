package br.com.luizqueiroz.pwndroid.core.mood

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tabela de transições completa (issue #11): estado + entrada → humor
 * esperado, porte verificado contra `pwnagotchi/automata.py` +
 * `pwnagotchi/ai/epoch.py` do pwnagotchi original (evilsocket).
 *
 * Defaults usados: bored=15, sad=25, excited=10, max_misses=5,
 * bond_encounters_factor=20000 (config customizada quando preciso).
 */
class MoodAutomataTest {

    private fun input(
        handshakes: Int = 0,
        misses: Int = 0,
        peersEncounters: Int = 0,
    ) = EpochMoodInput(
        epoch = 1,
        handshakes = handshakes,
        blind = false,
        misses = misses,
        peersEncounters = peersEncounters,
    )

    @Test
    fun `tabela de transicoes - epocas inativas to humor`() {
        // (épocas inativas, humor esperado) — porte de epoch.py + automata.py.
        val tabela = listOf(
            0 to Mood.LONELY, // inicial
            14 to Mood.HAPPY, // < bored_num_epochs: sem gatilho
            15 to Mood.BORED, // bored_num_epochs
            24 to Mood.BORED, // entre bored e sad continua BORED
            25 to Mood.SAD, // sad_num_epochs
            50 to Mood.ANGRY, // 2x sad_num_epochs: fator 2.0
        )
        tabela.forEach { (epocas, esperado) ->
            val a = MoodAutomata()
            repeat(epocas) { a.onEpoch(input()) }
            assertEquals("após $epocas épocas inativas", esperado, a.current)
        }
    }

    @Test
    fun `epocas ativas to EXCITED`() {
        val tabela = listOf(1 to Mood.HAPPY, 9 to Mood.HAPPY, 10 to Mood.EXCITED, 25 to Mood.EXCITED)
        tabela.forEach { (epocas, esperado) ->
            val a = MoodAutomata()
            repeat(epocas) { a.onEpoch(input(handshakes = 1)) }
            assertEquals("após $epocas épocas ativas", esperado, a.current)
        }
    }

    @Test
    fun `atividade reseta contadores negativos`() {
        // BORED/SAD/ANGRY + 1 época ativa → HAPPY (porte de epoch.py:
        // active_for++ zera inactive/sad/bored).
        listOf(15, 25, 50).forEach { epocasInativas ->
            val a = MoodAutomata()
            repeat(epocasInativas) { a.onEpoch(input()) }
            assertEquals("após $epocasInativas inativas + 1 ativa", Mood.HAPPY, a.onEpoch(input(handshakes = 1)))
        }
    }

    @Test
    fun `stale com fator menor que 2 e LONELY`() {
        val a = MoodAutomata(MoodConfig(maxMissesForRecon = 5))
        // 6 misses numa época: 6/5 < 2 → LONELY.
        assertEquals(Mood.LONELY, a.onEpoch(input(misses = 6)))
    }

    @Test
    fun `stale com fator maior ou igual a 2 e ANGRY`() {
        val a = MoodAutomata(MoodConfig(maxMissesForRecon = 5))
        // 10 misses numa época: 10/5 = 2.0 → ANGRY.
        assertEquals(Mood.ANGRY, a.onEpoch(input(misses = 10)))
    }

    @Test
    fun `misses nao acumulam entre epocas`() {
        val a = MoodAutomata(MoodConfig(maxMissesForRecon = 5))
        // Porte de epoch.py: num_missed é zerado a cada next().
        assertEquals(Mood.LONELY, a.onEpoch(input(misses = 6)))
        // Época seguinte sem misses: não-stale → sem gatilho → HAPPY.
        assertEquals(Mood.HAPPY, a.onEpoch(input()))
    }

    @Test
    fun `rede de suporte anula LONELY para GRATEFUL`() {
        val a = MoodAutomata(MoodConfig(bondEncountersFactor = 10))
        // Misses → stale → LONELY, mas totalEncounters=10 → fator 1.0 ≥ 1.0.
        assertEquals(Mood.GRATEFUL, a.onEpoch(input(misses = 6, peersEncounters = 10)))
    }

    @Test
    fun `rede de suporte anula ANGRY para GRATEFUL`() {
        val a = MoodAutomata(MoodConfig(maxMissesForRecon = 5, bondEncountersFactor = 100))
        // Fator 20/5 = 4.0 → ANGRY sem suporte; com suporte 500/100 = 5.0 ≥ 4.0.
        assertEquals(Mood.GRATEFUL, a.onEpoch(input(misses = 20, peersEncounters = 500)))
    }

    @Test
    fun `rede de suporte anula BORED para GRATEFUL`() {
        val a = MoodAutomata(MoodConfig(boredNumEpochs = 5, bondEncountersFactor = 100))
        repeat(5) { a.onEpoch(input(peersEncounters = 10)) } // 5 inativas → bored_for=1
        // fator = 5/5 = 1.0; suporte 50/100 = 0.5 < 1.0 → BORED.
        assertEquals(Mood.BORED, a.current)
        val b = MoodAutomata(MoodConfig(boredNumEpochs = 5, bondEncountersFactor = 100))
        repeat(5) { b.onEpoch(input(peersEncounters = 20)) } // total 100 → 1.0 ≥ 1.0
        assertEquals(Mood.GRATEFUL, b.current)
    }

    @Test
    fun `10 epocas com peers suficientes viram GRATEFUL`() {
        // Critério da issue: 10 épocas LONELY + peers suficientes → GRATEFUL.
        // bondEncountersFactor = 10_000; 9 épocas × 5_000 = 45_000 acumulado;
        // a 10ª é stale (6 misses → LONELY) e com os peers dela chega a
        // 50_000/10_000 = 5.0 ≥ 1.0 → GRATEFUL em vez de LONELY.
        val a = MoodAutomata(MoodConfig(bondEncountersFactor = 10_000))
        repeat(9) { a.onEpoch(input(peersEncounters = 5_000)) }
        assertEquals(Mood.GRATEFUL, a.onEpoch(input(misses = 6, peersEncounters = 5_000)))
        // Sem a rede de suporte, a mesma entrada fica LONELY.
        val b = MoodAutomata(MoodConfig(bondEncountersFactor = 10_000))
        assertEquals(Mood.LONELY, b.onEpoch(input(misses = 6)))
    }

    @Test
    fun `activeFor maior ou igual a 5 com suporte para fator 5 viram GRATEFUL`() {
        val a = MoodAutomata(MoodConfig(bondEncountersFactor = 100))
        repeat(5) { a.onEpoch(input(handshakes = 1)) }
        // active_for = 5, sem suporte (0/100 < 5.0) → HAPPY.
        assertEquals(Mood.HAPPY, a.current)
        // Época ativa com peers: total 500 → 500/100 = 5.0 ≥ 5.0 → GRATEFUL.
        assertEquals(Mood.GRATEFUL, a.onEpoch(input(handshakes = 1, peersEncounters = 500)))
    }

    @Test
    fun `reset volta a LONELY com contadores zerados`() {
        val a = MoodAutomata()
        repeat(20) { a.onEpoch(input()) }
        assertEquals(Mood.BORED, a.current)
        a.reset()
        assertEquals(Mood.LONELY, a.current)
        // 14 épocas inativas após reset: não chega a BORED (15).
        repeat(14) { a.onEpoch(input()) }
        assertEquals(Mood.HAPPY, a.onEpoch(input(handshakes = 1)))
    }

    @Test
    fun `face tem kaomoji e status coerentes com o humor`() {
        val a = MoodAutomata()
        // 15 inativas → BORED.
        repeat(15) { a.onEpoch(input()) }
        val bored = a.face()
        assertEquals(Mood.BORED, bored.mood)
        assertEquals("(-__-)", bored.kaomoji)
        // 1 ativa → HAPPY.
        a.onEpoch(input(handshakes = 1))
        val happy = a.face()
        assertEquals(Mood.HAPPY, happy.mood)
        assertEquals("(•‿•)", happy.kaomoji)
        // 10 ativas → EXCITED.
        repeat(9) { a.onEpoch(input(handshakes = 1)) }
        assertEquals(Mood.EXCITED, a.face().mood)
    }
}
