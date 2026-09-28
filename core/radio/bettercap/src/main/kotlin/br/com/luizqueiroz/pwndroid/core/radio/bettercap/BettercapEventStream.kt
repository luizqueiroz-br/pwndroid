package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Consumidor de eventos WS do bettercap com reconexão automática
 * (issue #20): o WS cai (processo reiniciado, rede instável), o
 * [BettercapEventStream] reconecta com backoff (1 s, 2 s, 4 s — até 3
 * tentativas) e re-emite os eventos a partir do recon (o bettercap
 * reenvia o estado da sessão no /api/session — não há replay no WS).
 *
 * O fluxo termina (não reconecta mais) quando [shutdown] é chamado ou
 * depois de esgotadas as tentativas — o caller (BettercapBackend da #21)
 * decide o que fazer com o fim do stream.
 */
class BettercapEventStream(
    private val api: BettercapApi,
    private val pcapDir: String,
    private val logger: AppLogger? = null,
) {

    /**
     * Fluxo frio de [RadioEvent]: conecta ao WS, consome frames, mapeia
     * e reconecta com backoff em queda. Cancelamento do collector
     * encerra o fluxo.
     *
     * O backoff reseta apenas quando a conexão foi produtiva (recebeu
     * ao menos um evento válido) — uma conexão que abre mas fecha sem
     * entregar nada conta como queda. O fluxo encerra quando uma
     * conexão produtiva fecha em ordem (desligamento limpo do
     * bettercap) ou quando as tentativas se esgotam.
     */
    fun radioEvents(): Flow<RadioEvent> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive && attempt <= MAX_RECONNECT) {
            var receivedEvent = false
            try {
                val ws = api.events().getOrThrow()
                try {
                    while (true) {
                        val frame = ws.receive() ?: break
                        // Frame inválido (JSON quebrado, tag sem payload)
                        // é descartado — não derruba o stream.
                        val radioEvent = runCatching {
                            EventMapper.toRadioEvent(frame, pcapDir)
                        }.getOrNull()
                        if (radioEvent != null) {
                            receivedEvent = true
                            attempt = 0 // conexão produtiva reseta o backoff
                            emit(radioEvent)
                        }
                    }
                } finally {
                    runCatching { ws.close() }
                }
                if (receivedEvent) {
                    // A conexão produziu eventos e fechou em ordem:
                    // desligamento limpo do bettercap — o fluxo encerra
                    // (quem coleciona decide reiniciar o ciclo).
                    return@flow
                }
                logger?.d(TAG, "WS fechado sem eventos — reconectando")
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger?.w(TAG, "WS caiu — reconexão ${attempt + 1}/$MAX_RECONNECT", e)
            }
            // Backoff exponencial: 1 s, 2 s, 4 s (até 3 tentativas).
            delay(BACKOFF_BASE_MS * (1L shl attempt))
            attempt++
        }
    }

    companion object {
        /** Tag de log. */
        const val TAG = "BettercapWS"

        /** Tentativas de reconexão antes de desistir (issue #20). */
        const val MAX_RECONNECT = 3

        /** Base do backoff exponencial (1 s). */
        const val BACKOFF_BASE_MS = 1000L

        /** Alias para o limite (nomes: MAX_RECONNECT e MAX_RECONNECT_ATTEMPTS). */
        const val MAX_RECONNECT_ATTEMPTS = MAX_RECONNECT
    }
}
