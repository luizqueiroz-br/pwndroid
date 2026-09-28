package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.ContentType.Application.Json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.Base64
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Cliente da API REST/WS do bettercap (issue #20) — a mesma interface
 * HTTP que o pwnagotchi original usa (REST + WebSocket de eventos).
 *
 * O bettercap expõe:
 * - `GET /api/session` — estado da sessão (APs e STAs do módulo wifi).
 * - `POST /api/session` — `run`/`set` de comandos (corpo `{"cmd": ...}`).
 * - `GET /api/events` — WebSocket com eventos (`wifi.ap.new`,
 *   `wifi.client.handshake`, `modem.internet_available`...).
 *
 * Auth: HTTP Basic (`-api-rest` com usuário/senha do bettercap; defaults
 * `user`/`pass` se não configurados — o caller define os reais).
 */
interface BettercapApi : AutoCloseable {

    /** `POST /api/session` com `{"cmd": ...}` — `run`/`set` de comando. */
    suspend fun run(command: String): Result<Unit>

    /** `GET /api/session` — snapshot da sessão (APs + STAs do wifi). */
    suspend fun session(): Result<BettercapSession>

    /** `GET /api/events` — sessão WS de eventos do bettercap. */
    suspend fun events(): Result<WsSession>

    override fun close()
}

/**
 * Sessão WS de eventos do bettercap: cada frame de texto é um evento
 * JSON (`{"tag": "wifi.ap.new", "ap": {...}}`).
 */
interface WsSession : AutoCloseable {
    /** Lê o próximo evento (bloqueante); null quando o WS fechou. */
    suspend fun receive(): String?

    override fun close()
}

/**
 * Snapshot da sessão bettercap (GET /api/session) — APs e STAs do
 * módulo wifi, mapeados para o domínio por [EventMapper] / callers.
 */
data class BettercapSession(
    val accessPoints: List<ApiAccessPoint>,
    val stations: List<ApiStation>,
)

/** JSON `wifi.ap` do bettercap (campos usados pelo pwndroid). */
data class ApiAccessPoint(
    val mac: String,
    val essid: String?,
    val rssi: Int,
    val channel: Int,
    val frequency: Int,
    val encryption: String?,
    val firstSeen: String,
    val lastSeen: String,
)

/** JSON `wifi.sta` do bettercap (campos usados pelo pwndroid). */
data class ApiStation(
    val mac: String,
    val rssi: Int,
    val apMac: String?,
)

/** Erro de comunicação com a API do bettercap (HTTP, WS, parse). */
class BettercapApiException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Implementação Ktor do [BettercapApi] (issue #20). REST com JSON
 * manual (sem ContentNegotiation — parse explícito com fixtures) e WS
 * via plugin WebSockets do Ktor.
 */
class HttpBettercapApi(
    /** URL base do bettercap (ex.: http://127.0.0.1:8081). */
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: HttpClient,
) : BettercapApi {

    override suspend fun run(command: String): Result<Unit> = runCatching {
        // JSON escapado corretamente: o comando vira JsonPrimitive.
        val body = JsonObject(mapOf("cmd" to JsonPrimitive(command))).toString()
        val response = client.post("$baseUrl/api/session") {
            headers.append(
                "Authorization",
                "Basic ${basicAuthCredentials(username, password)}",
            )
            contentType(Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw BettercapApiException(
                "comando '$command' falhou: HTTP ${response.status.value}",
            )
        }
    }

    override suspend fun session(): Result<BettercapSession> = runCatching {
        val response = client.get("$baseUrl/api/session") {
            headers.append(
                "Authorization",
                "Basic ${basicAuthCredentials(username, password)}",
            )
        }
        if (!response.status.isSuccess()) {
            throw BettercapApiException("GET /api/session: HTTP ${response.status.value}")
        }
        BettercapJson.parseSession(response.bodyAsText())
    }

    override suspend fun events(): Result<WsSession> = runCatching {
        KtorWsSession(client.webSocketSession("$baseUrl/api/events"))
    }

    override fun close() {
        runCatching { client.close() }
    }

    /** Header Basic (RFC 7617): Base64 de `user:pass`. */
    private fun basicAuthCredentials(user: String, pass: String): String =
        Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    /** Sessão WS Ktor adaptada para a [WsSession] da interface. */
    private class KtorWsSession(
        private val session: DefaultClientWebSocketSession,
    ) : WsSession {

        /** Frame de texto do bettercap; null quando o WS fechou. */
        override suspend fun receive(): String? = try {
            val frame = session.incoming.receive()
            (frame as? Frame.Text)?.readText()
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }

        override fun close() {
            // cancel() é a variante síncrona de close() (suspend):
            // encerra a sessão WS imediatamente.
            session.cancel()
        }
    }
}
