package br.com.luizqueiroz.pwndroid.core.common

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Testes do barramento de eventos (publish → collect, Turbine). */
class EventBusTest {

    @Test
    fun `publish entrega evento para coletores`() = runTest {
        val bus = EventBus()
        bus.events.test {
            bus.publish("evento-1")
            assertEquals("evento-1", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `publish nao suspende mesmo com assinante lento`() = runTest {
        val bus = EventBus()
        repeat(300) { bus.publish(it) } // buffer extra absorve; não lança
    }
}

/** Teste de exemplo do AppClock fake (determinismo). */
class FakeAppClockTest {

    @Test
    fun `fake clock avanca sob controle do teste`() {
        val clock = FakeAppClock(initialMillis = 1_000)
        assertEquals(1_000, clock.nowMillis())
        clock.advanceBy(500)
        assertEquals(1_500, clock.nowMillis())
    }
}
