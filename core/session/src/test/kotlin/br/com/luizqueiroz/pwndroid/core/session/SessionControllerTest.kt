package br.com.luizqueiroz.pwndroid.core.session

import br.com.luizqueiroz.pwndroid.core.brain.FixedBrain
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.radio.FakeRadioBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    private class ManualClock(private var now: Long = 1_000_000) : AppClock {
        override fun nowMillis(): Long = now
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        fun advanceBy(millis: Long) {
            now += millis
        }
    }

    private fun env() = object : br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment {
        override val clock: AppClock get() = ManualClock()
        override val captureDir: String get() = "/tmp/pwndroid-test/captures"
    }

    private fun newController(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        candidates: List<FakeRadioBackend> = listOf(FakeRadioBackend(ManualClock())),
    ) = SessionController(
        selector = BackendSelector(candidates),
        environment = env(),
        brain = FixedBrain(),
        bus = EventBus(),
        scope = CoroutineScope(StandardTestDispatcher(scheduler)),
        waitFor = { /* tempo virtual: janela de recon instantânea */ },
    )

    @Test
    fun `start duplo é idempotente e stop limpa o estado`() = runTest {
        val controller = newController(testScheduler)

        controller.start(maxEpochs = 3)
        testScheduler.advanceUntilIdle()
        val epochAfterFirst = controller.state.value.session.epoch
        assertEquals(BackendId.FAKE, controller.state.value.backend)
        assertTrue(epochAfterFirst > 0)

        // Segundo start é ignorado: a sessão não recomeça.
        controller.start(maxEpochs = 3)
        testScheduler.advanceUntilIdle()
        assertEquals(epochAfterFirst, controller.state.value.session.epoch)

        controller.stop()
        assertEquals(SessionUiState(), controller.state.value)
    }

    @Test
    fun `sem backend disponível expõe erro e não inicia sessão`() = runTest {
        val controller = newController(testScheduler, candidates = emptyList())

        controller.start(maxEpochs = 1)
        testScheduler.advanceUntilIdle()

        assertEquals(null, controller.state.value.backend)
        assertTrue(controller.state.value.error.orEmpty().contains("nenhum backend"))
        assertEquals(0, controller.state.value.session.epoch)
    }

    @Test
    fun `stop antes do start processar não deixa sessão órfã`() = runTest {
        val controller = newController(testScheduler)

        controller.start(maxEpochs = 5)
        controller.stop()
        testScheduler.advanceUntilIdle()

        // O launch do start vê running=false e sai sem criar orquestrador.
        assertEquals(SessionUiState(), controller.state.value)
    }

    @Test
    fun `restart após stop funciona`() = runTest {
        val controller = newController(testScheduler)

        controller.start(maxEpochs = 1)
        testScheduler.advanceUntilIdle()
        controller.stop()
        controller.start(maxEpochs = 2)
        testScheduler.advanceUntilIdle()

        assertEquals(BackendId.FAKE, controller.state.value.backend)
        assertEquals(2, controller.state.value.session.epoch)
    }
}
