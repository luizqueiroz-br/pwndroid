package br.com.luizqueiroz.pwndroid.ui.home

import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.mood.Mood
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.session.EpochOrchestrator
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.core.session.SessionState
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do HomeState (issue #13): época no bus → mood → frame facial.
 * O blink é determinístico via Random injetável.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateTest {

    private fun registryWithSession(): SessionRegistry = SessionRegistry()

    @Test
    fun `estado inicial é LONELY sem blink`() {
        val state = HomeState(SessionRegistry(), EventBus(), random = Random(1))
        val face = state.face.value
        assertEquals(Mood.LONELY, face.frame.mood)
        assertFalse(face.blink)
        assertEquals("(╯°□°)╯", face.frame.expression)
    }

    @Test
    fun `época com handshakes muda o mood para HAPPY`() = runTest {
        val bus = EventBus()
        val state = HomeState(registryWithSession(), bus)
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        state.observe(scope)
        bus.publish(EpochOrchestrator.EpochFinished(epoch = 1, handshakes = 1, pmkids = 0, blind = false))
        assertEquals(Mood.HAPPY, state.mood.value)
        assertEquals("(•‿‿•)", state.face.value.frame.expression)
    }

    @Test
    fun `épocas cegas acumulam misses e viram BORED na 15`() = runTest {
        val bus = EventBus()
        val state = HomeState(registryWithSession(), bus)
        state.observe(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        repeat(15) { n ->
            bus.publish(
                EpochOrchestrator.EpochFinished(epoch = (n + 1).toLong(), handshakes = 0, pmkids = 0, blind = true),
            )
        }
        assertEquals(Mood.BORED, state.mood.value)
        assertEquals("(-_-)", state.face.value.frame.expression)
    }

    @Test
    fun `linhas de stats refletem a sessão do registry`() = runTest {
        val bus = EventBus()
        val registry = SessionRegistry()
        val state = HomeState(registry, bus)
        registry.publish(
            SessionUiState(
                session = SessionState(
                    epoch = 7,
                    handshakes = 3,
                    pmkids = 1,
                    candidates = emptyList(),
                ),
            ),
        )
        state.observe(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val lines = state.face.value.frame.lines
        assertEquals(5, lines.size)
        assertTrue(lines[0] == "epoch=7")
        assertTrue(lines.any { it.contains("handshakes=3") })
    }

    @Test
    fun `tick sorteia blink e mantém o frame`() = runTest {
        val state = HomeState(registryWithSession(), EventBus(), random = Random(7))
        state.tick(blinkEveryTicks = 1) // sempre sorteia 0 → blink
        assertTrue(state.face.value.blink)
        // O frame não muda no tick (blink é aplicado na renderização
        // por blinkOf, testado no feature/display).
        assertEquals("(╯°□°)╯", state.face.value.frame.expression)
    }

    @Test
    fun `modeLabel porta os modos`() {
        val state = HomeState(registryWithSession(), EventBus())
        assertEquals("manu", state.modeLabel(PwnMode.MANUAL))
        assertEquals("auto", state.modeLabel(PwnMode.AUTO))
        assertEquals("ai", state.modeLabel(PwnMode.AI))
        assertEquals("passive", state.modeLabel(PwnMode.PASSIVE))
    }

    @Test
    fun `dispose volta ao estado inicial`() = runTest {
        val bus = EventBus()
        val state = HomeState(registryWithSession(), bus)
        state.observe(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bus.publish(EpochOrchestrator.EpochFinished(epoch = 1, handshakes = 1, pmkids = 0, blind = false))
        assertEquals(Mood.HAPPY, state.mood.value)
        state.dispose()
        assertEquals(Mood.LONELY, state.mood.value)
        assertFalse(state.face.value.blink)
    }
}

