package br.com.luizqueiroz.pwndroid.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Barramento de eventos em processo: publicadores emitem, assinantes filtram
 * por tipo. Todo o tráfego interno entre rádio → sessão → cérebro → humores →
 * UI passa por aqui.
 */
class EventBus {

    private val bus = MutableSharedFlow<Any>(extraBufferCapacity = 256)

    /** Emite um evento (sem suspensão; buffer extra absorve picos). */
    fun publish(event: Any) {
        bus.tryEmit(event)
    }

    /** Fluxo de todos os eventos, para quem quiser filtrar com `filterIsInstance`. */
    val events: Flow<Any> get() = bus

    /**
     * Assina eventos de um tipo específico, lançando a coleta no [scope].
     * Retorna o Job para cancelamento pelo dono do escopo.
     */
    inline fun <reified T : Any> subscribe(scope: CoroutineScope, noinline handler: suspend (T) -> Unit): Job =
        scope.launch {
            events.collect { event ->
                if (event is T) handler(event)
            }
        }
}
