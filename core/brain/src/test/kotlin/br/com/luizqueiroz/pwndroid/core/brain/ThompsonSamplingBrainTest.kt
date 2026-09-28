package br.com.luizqueiroz.pwndroid.core.brain

import br.com.luizqueiroz.pwndroid.core.model.Personality
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da issue #27: determinismo com seed, convergência no ambiente
 * simulado, decay em mudança de ambiente e snapshot com as top armas.
 */
class ThompsonSamplingBrainTest {

    /** ArmStore in-memory: replica a semântica do BrainArmDao (REPLACE). */
    private class FakeArmStore : ArmStore {
        val rows = mutableMapOf<String, ArmCounts>()

        private fun key(brainId: String, param: String, value: String) = "$brainId/$param/$value"

        override suspend fun armsOf(brainId: String, param: String): List<ArmCounts> =
            rows.filterKeys { it.startsWith("$brainId/$param/") }.values.toList()

        override suspend fun upsert(brainId: String, param: String, value: String, alpha: Double, beta: Double) {
            rows[key(brainId, param, value)] = ArmCounts(value, alpha, beta)
        }

        override suspend fun updateCounts(
            brainId: String,
            param: String,
            value: String,
            alpha: Double,
            beta: Double,
        ) {
            rows[key(brainId, param, value)] = ArmCounts(value, alpha, beta)
        }
    }

    /** EpochResult de conveniência: sucesso = handshake capturado. */
    private fun result(success: Boolean) = EpochResult(
        handshakesCaptured = if (success) 1 else 0,
        pmkidCaptured = 0,
        interactionsAttempted = 1,
        reward = if (success) 1.0 else 0.0,
    )

    @Test
    fun `prior uniforme - primeira decisão é determinística com seed`() = runTest {
        val store = FakeArmStore()
        val brain = ThompsonSamplingBrain(store, seed = 42)

        val p1 = brain.nextPersonality()
        val p2 = ThompsonSamplingBrain(FakeArmStore(), seed = 42).nextPersonality()

        // Mesma seed → mesma decisão; e dentro dos valores válidos do espaço.
        assertEquals(p1.reconTimeSec, p2.reconTimeSec)
        assertTrue(ThompsonSamplingBrain.ARM_SPACE.getValue("recon_time")
            .contains(p1.reconTimeSec.toString()))
        assertTrue(p1.minRssi <= 0)
        assertTrue(p1.maxInteractions in 1..6)
    }

    @Test
    fun `convergência - recon de 35s é preferido quando só ele captura`() = runTest {
        val store = FakeArmStore()
        // Decay desligado para convergência limpa.
        val brain = ThompsonSamplingBrain(store, seed = 7, decayFactor = 1.0)

        var chosen35 = 0
        val epochs = 300
        repeat(epochs) {
            val persona = brain.nextPersonality()
            // Ambiente simulado: handshake só quando reconTime=35s (e 20% de
            // chance nos demais — ruído para não virar memorização trivial).
            val success = persona.reconTimeSec == 35L ||
                (persona.reconTimeSec != 35L && kotlin.random.Random.nextInt(5) == 0)
            brain.reportEpochResult(result(success))
            if (persona.reconTimeSec == 35L) chosen35++
        }

        assertTrue(
            "esperava >60% das épocas com recon=35s, foi ${chosen35 * 100 / epochs}%",
            chosen35 * 100 / epochs > 60,
        )
        // As contadores persistiram: 35s tem α > β.
        val arm35 = store.rows["thompson/recon_time/35"]!!
        assertTrue(arm35.alpha > arm35.beta)
    }

    @Test
    fun `decay - mudança de ambiente re-explore a nova melhor arma`() = runTest {
        val store = FakeArmStore()
        val brain = ThompsonSamplingBrain(store, seed = 11, decayFactor = 0.98)

        // Fase 1: 30s sempre tem sucesso (40 épocas) — arma dominante.
        repeat(40) {
            val persona = brain.nextPersonality()
            brain.reportEpochResult(result(persona.reconTimeSec == 30L))
        }
        val arm30 = store.rows["thompson/recon_time/30"]!!
        val arm60 = store.rows["thompson/recon_time/60"]!!
        assertTrue("30s devia acumular α", arm30.alpha > arm30.beta)
        // E 60s, nunca recompensado, ainda está perto do prior (1,1).
        assertTrue("60s devia estar perto do prior: $arm60", arm60.alpha < arm30.alpha)

        // Fase 2: ambiente muda — só 60s tem sucesso (60 épocas). Com decay,
        // os contadores antigos perdem peso e 60s deve ultrapassar 30s.
        repeat(60) {
            val persona = brain.nextPersonality()
            brain.reportEpochResult(result(persona.reconTimeSec == 60L))
        }
        val arm30Depois = store.rows["thompson/recon_time/30"]!!
        val arm60Depois = store.rows["thompson/recon_time/60"]!!
        assertTrue(
            "60s devia superar 30s após a mudança: 60=${arm60Depois} 30=${arm30Depois}",
            arm60Depois.alpha > arm30Depois.alpha,
        )
    }

    @Test
    fun `snapshot - persona vigente, top armas e contagem de épocas`() = runTest {
        val brain = ThompsonSamplingBrain(FakeArmStore(), seed = 3)

        assertEquals("thompson", brain.id)
        assertEquals(null, brain.state.value.personality)

        brain.nextPersonality()
        val snap1 = brain.state.value
        assertTrue(snap1.personality is Personality)
        assertEquals("1", snap1.extra["epochs"])
        assertTrue("recon_time" in snap1.extra.keys)

        // Coletor instalado ANTES da emissão: SharedFlow sem replay descarta
        // emissões sem subscriber. UNDISPATCHED inscreve o coletor na hora
        // (roda até a 1ª suspensão), sem depender do escalonador do runTest.
        val diags = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            brain.diagnostics.take(1).toList(diags)
        }

        brain.nextPersonality()
        brain.reportEpochResult(result(true))
        job.join()

        val snap2 = brain.state.value
        assertEquals("2", snap2.extra["epochs"])
        assertEquals(result(true), snap2.lastEpoch)

        // Diagnostics emitem a decisão da época.
        assertEquals(1, diags.size)
        assertTrue("recon_time=" in diags[0])
    }
}
