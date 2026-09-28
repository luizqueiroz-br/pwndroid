package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.BrainSnapshot
import br.com.luizqueiroz.pwndroid.core.brain.EpochResult
import br.com.luizqueiroz.pwndroid.core.brain.FixedBrain
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.model.Personality
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.model.Target
import br.com.luizqueiroz.pwndroid.core.radio.FakeRadioBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochOrchestratorTest {

    /** Clock avançável manualmente (nenhum tempo real no teste). */
    private class ManualClock(private var now: Long = 1_000_000) : AppClock {
        override fun nowMillis(): Long = now
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        fun advanceBy(millis: Long) {
            now += millis
        }
    }

    /** Cérebro determinístico: personalidade fixa + alvo por prioridade. */
    private class ScriptedBrain(
        private val personality: Personality = Personality(),
    ) : Brain {
        override val id: String = "scripted"
        override suspend fun nextPersonality(): Personality = personality

        override suspend fun selectTarget(candidates: List<Target>): Target? = candidates.firstOrNull()

        val reported = mutableListOf<EpochResult>()
        override suspend fun reportEpochResult(result: EpochResult) {
            reported.add(result)
        }

        override val diagnostics: Flow<String> = MutableSharedFlow()

        private val _state = MutableStateFlow(BrainSnapshot(id = "scripted"))
        override val state: StateFlow<BrainSnapshot> = _state.asStateFlow()
    }

    private fun newOrchestrator(
        backend: FakeRadioBackend,
        brain: Brain,
        clock: ManualClock,
        config: SessionConfig = SessionConfig(),
    ): EpochOrchestrator = run {
        val started = kotlinx.coroutines.runBlocking { backend.start(env()) }
        EpochOrchestrator(started, brain, EventBus(), config, waitFor = { reconMs ->
            // Avança o relógio manual pela janela de recon toda.
            clock.advanceBy(reconMs)
        })
    }

    private fun env() = object : br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment {
        override val clock: AppClock get() = object : AppClock {
            override fun nowMillis(): Long = 0
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
        }
        override val captureDir: String get() = "/tmp/pwndroid-test/captures"
    }

    @Test
    fun `ciclo golden - recon filtra whitelist e min_rssi, depois interage`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain(Personality(minRssi = -80, maxInteractions = 2))
        val config = SessionConfig(whitelist = setOf("minha-rede"))
        val orch = newOrchestrator(backend, brain, clock, config)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "alvo", channel = 1, rssi = -50)
            .station("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:09")
            .ap("AA:BB:CC:00:00:02", ssid = "minha-rede", channel = 6, rssi = -40)
            .ap("AA:BB:CC:00:00:03", ssid = "fraco", channel = 11, rssi = -95)

        orch.start(scope, PwnMode.AUTO, maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        println("DEBUG2 ops=$${backend.operations} state=${orch.state.value}")
        // Whitelist e min_rssi excluíram os outros; alvo restante elegível.
        val state = orch.state.value
        assertEquals(1, state.epoch)
        assertEquals(listOf("aa:bb:cc:00:00:01"), state.candidates.map { it.bssid })
        assertTrue(backend.operations.any { it.startsWith("assoc:aa:bb:cc:00:00:01") })
        assertTrue(backend.operations.any { it.startsWith("deauth:") })
        // A rede autorizada nunca recebe assoc.
        assertFalse(backend.operations.any { it.contains("aa:bb:cc:00:00:02") })
        orch.stop()
    }

    @Test
    fun `modo manual nao interage - so recon`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain()
        val orch = newOrchestrator(backend, brain, clock)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script.ap("AA:BB:CC:00:00:01", ssid = "qualquer", channel = 1)

        orch.start(scope, PwnMode.MANUAL, maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        assertFalse(backend.operations.any { it.startsWith("assoc") })
        assertFalse(backend.operations.any { it.startsWith("deauth") })
        assertTrue(backend.operations.any { it.startsWith("recon:") })
        orch.stop()
    }

    @Test
    fun `epoca cega estende recon e para a sessao no limite`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain()
        val config = SessionConfig(maxBlindEpochs = 2)
        val orch = newOrchestrator(backend, brain, clock, config)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        // Nenhum AP programado → épocas cegas.
        orch.start(scope, PwnMode.AUTO, maxEpochs = 2)
        testScheduler.advanceUntilIdle()

        assertEquals(2, orch.state.value.epoch)
        assertEquals(2, orch.state.value.blindEpochs)
        assertTrue(orch.state.value.stopped)
        assertTrue(orch.state.value.stopReason.orEmpty().contains("épocas cegas"))
        orch.stop()
    }

    @Test
    fun `alvo já capturado não recebe segunda interação`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain(Personality(maxInteractions = 3))
        val orch = newOrchestrator(backend, brain, clock)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "alvo", channel = 1)
            .handshake("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02")

        orch.start(scope, PwnMode.AUTO, maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        // O handshake veio no recon → BSSID já capturado → sem assoc/deauth.
        assertFalse(backend.operations.any { it.startsWith("assoc:aa:bb:cc:00:00:01") })
        assertFalse(backend.operations.any { it.startsWith("deauth") })
        orch.stop()
    }

    @Test
    fun `handshakes acumulam na sessão`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain(Personality(maxInteractions = 0))
        val orch = newOrchestrator(backend, brain, clock)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "a", channel = 1)
            .handshake("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02", pmkid = true)

        orch.start(scope, PwnMode.AUTO, maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        assertEquals(1, orch.state.value.epoch)
        assertEquals(0, orch.state.value.handshakes)
        assertEquals(1, orch.state.value.pmkids)
        orch.stop()
    }

    @Test
    fun `setMode em sessão rodando vale na próxima época`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain()
        // Gate: a ÉPOCA 2 trava no waitFor até o teste trocar o modo —
        // garante que a troca acontece ENTRE as épocas (issue #59).
        val releaseEpoch2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        var waitCalls = 0
        val started = kotlinx.coroutines.runBlocking { backend.start(env()) }
        val orch = EpochOrchestrator(started, brain, EventBus(), SessionConfig(), waitFor = { _ ->
            if (waitCalls == 1) {
                // waitFor da época 2: aguarda a liberação do teste.
                releaseEpoch2.await()
            } else {
                waitCalls++
                clock.advanceBy(30_000)
            }
        })
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script.ap("AA:BB:CC:00:00:01", ssid = "alvo", channel = 1)
        orch.start(scope, PwnMode.MANUAL, maxEpochs = 2)
        testScheduler.runCurrent()
        // Época 1 completou em MANUAL (a 2ª está travada no gate).
        testScheduler.advanceUntilIdle()
        assertEquals(1, orch.state.value.epoch)
        assertFalse(backend.operations.any { it.startsWith("assoc") })

        // Troca em sessão rodando (issue #59) e libera a época 2.
        orch.setMode(PwnMode.AUTO)
        backend.script
            .ap("AA:BB:CC:00:00:02", ssid = "novo", channel = 6)
            .station("AA:BB:CC:00:00:02", "AA:BB:CC:00:00:09")
        releaseEpoch2.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(2, orch.state.value.epoch)
        // A época 2 interage (AUTO) — a 1 em MANUAL não interagiu.
        assertTrue(backend.operations.any { it.startsWith("assoc:") })
        orch.stop()
    }

    /** Spy sobre FixedBrain: registra as chamadas do orquestrador (issue #26). */
    private class SpyBrain(private val delegate: FixedBrain) : Brain by delegate {
        var nextPersonalityCalls = 0
            private set
        var selectTargetCalls = 0
            private set
        var reportedResults = mutableListOf<EpochResult>()
            private set

        override suspend fun nextPersonality(): Personality {
            nextPersonalityCalls++
            return delegate.nextPersonality()
        }

        override suspend fun selectTarget(candidates: List<Target>): Target? {
            selectTargetCalls++
            return delegate.selectTarget(candidates)
        }

        override suspend fun reportEpochResult(result: EpochResult) {
            reportedResults.add(result)
            delegate.reportEpochResult(result)
        }
    }

    @Test
    fun `orquestrador consome o Brain sem conhecer a implementação - spy de FixedBrain`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val spy = SpyBrain(FixedBrain())
        val orch = newOrchestrator(backend, spy, clock)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "alvo", channel = 1)
            .station("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:09")

        orch.start(scope, PwnMode.AUTO, maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        // O orquestrador usou apenas a interface Brain.
        assertEquals(1, spy.nextPersonalityCalls)
        // selectTarget é consultado a cada tentativa de interação da época —
        // pode ser chamado mais de uma vez; o que importa é que foi consultado.
        assertTrue(spy.selectTargetCalls >= 1)
        assertEquals(1, spy.reportedResults.size)
        assertTrue(spy.reportedResults[0].interactionsAttempted > 0)
        orch.stop()
    }

    @Test
    fun `modo AI interage como AUTO`() = runTest {
        val clock = ManualClock()
        val backend = FakeRadioBackend(clock)
        val brain = ScriptedBrain()
        val orch = newOrchestrator(backend, brain, clock)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))

        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "alvo", channel = 1)
            .station("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:09")

        orch.start(scope, PwnMode.AI, maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        assertTrue(backend.operations.any { it.startsWith("assoc:aa:bb:cc:00:00:01") })
        assertTrue(backend.operations.any { it.startsWith("deauth:") })
        orch.stop()
    }
}

