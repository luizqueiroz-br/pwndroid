package br.com.luizqueiroz.pwndroid.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Relógio fake para testes: tempo virtual controlado pelo teste e
 * dispatchers determinísticos (StandardTestDispatcher).
 */
class FakeAppClock(
    initialMillis: Long = 0,
    testDispatcher: CoroutineDispatcher = StandardTestDispatcher(),
) : AppClock {
    private var currentMillis = initialMillis

    override fun nowMillis(): Long = currentMillis

    /** Avança o relógio virtual. */
    fun advanceBy(millis: Long) {
        currentMillis += millis
    }

    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
}
