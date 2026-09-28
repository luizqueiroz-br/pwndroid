package br.com.luizqueiroz.pwndroid.core.brain

import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.Target
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBrainTest {

    /** Delegate mínimo para verificar pass-through. */
    private class DelegateBrain : Brain {
        override val id: String = "delegate"
        override suspend fun nextPersonality(): Personality = error("não deve ser chamado")

        override suspend fun selectTarget(candidates: List<Target>): Target? =
            candidates.lastOrNull()

        val reported = mutableListOf<EpochResult>()
        override suspend fun reportEpochResult(result: EpochResult) {
            reported.add(result)
        }

        override val diagnostics: Flow<String> = MutableSharedFlow()

        private val _state = MutableStateFlow(BrainSnapshot(id = id))
        override val state: StateFlow<BrainSnapshot> = _state.asStateFlow()
    }

    @Test
    fun `nextPersonality devolve a config mais recente na próxima época`() = runTest {
        val updates = MutableStateFlow(Personality(reconTimeSec = 30))
        val brain = ConfigBrain(DelegateBrain(), updates)

        // Época 1: config corrente.
        assertEquals(30L, brain.nextPersonality().reconTimeSec)

        // Usuário muda a config com a sessão rodando → próxima época usa a nova.
        updates.value = Personality(reconTimeSec = 99)
        assertEquals(99L, brain.nextPersonality().reconTimeSec)
    }

    @Test
    fun `selectTarget e reportEpochResult passam pelo delegate`() = runTest {
        val delegate = DelegateBrain()
        val brain = ConfigBrain(delegate, MutableStateFlow(Personality()))
        val target = Target(bssid = "aa:bb:cc:00:00:01", essid = "ap", channel = 1, rssi = -50)

        assertEquals(target, brain.selectTarget(listOf(target)))
        assertEquals("config(delegate)", brain.id)
        assertTrue(brain.id.contains("delegate"))

        val result = EpochResult(1, 0, 1, 1.0)
        brain.reportEpochResult(result)
        assertEquals(listOf(result), delegate.reported)
    }

    @Test
    fun `diagnostics vem do delegate`() = runTest {
        val delegate = DelegateBrain()
        val brain = ConfigBrain(delegate, MutableStateFlow(Personality()))
        assertTrue(brain.diagnostics === delegate.diagnostics)
    }

    @Test
    fun `snapshot mostra persona configurada e desfecho da última época`() = runTest {
        val updates = MutableStateFlow(Personality(reconTimeSec = 42, deauthCount = 7))
        val brain = ConfigBrain(DelegateBrain(), updates)

        // Antes de qualquer época: snapshot com id do wrapper e sem persona.
        assertEquals("config(delegate)", brain.state.value.id)
        assertEquals(null, brain.state.value.personality)

        // nextPersonality cacheia a persona configurada.
        brain.nextPersonality()
        assertEquals(42L, brain.state.value.personality?.reconTimeSec)
        assertEquals(7, brain.state.value.personality?.deauthCount)

        // reportEpochResult espelha o lastEpoch do delegate com a persona sobreposta.
        val result = EpochResult(2, 1, 3, 2.5)
        brain.reportEpochResult(result)
        assertEquals(result, brain.state.value.lastEpoch)
        assertEquals(42L, brain.state.value.personality?.reconTimeSec)

        // Persona muda na config → report seguinte publica a nova.
        updates.value = Personality(reconTimeSec = 77)
        brain.nextPersonality()
        brain.reportEpochResult(EpochResult(0, 0, 0, 0.0))
        assertEquals(77L, brain.state.value.personality?.reconTimeSec)
        assertEquals(EpochResult(0, 0, 0, 0.0), brain.state.value.lastEpoch)
    }
}


