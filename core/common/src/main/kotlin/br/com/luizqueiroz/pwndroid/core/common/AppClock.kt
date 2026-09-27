package br.com.luizqueiroz.pwndroid.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Fonte de tempo e de dispatchers injetável — permite testes determinísticos
 * (relógio virtual + StandardTestDispatcher) sem depender do Android.
 */
interface AppClock {
    /** Milissegundos de éPOCH em UTC. */
    fun nowMillis(): Long
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class RealAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}