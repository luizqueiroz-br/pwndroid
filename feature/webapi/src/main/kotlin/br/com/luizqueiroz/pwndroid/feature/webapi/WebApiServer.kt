package br.com.luizqueiroz.pwndroid.feature.webapi

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * API web local (equivalente ao Flask do pwnagotchi, mas Ktor CIO):
 * - GET /health    — liveness
 * - GET /v1/face   — estado facial atual (texto)
 * - GET /v1/status — contadores da sessão (texto)
 *
 * Basic Auth chega com a issue #23; endpoints v1 respondem texto por ora.
 */
class WebApiServer(
    private val port: Int = 8080,
    // Guardados para a issue #23 (Basic Auth via ktor-server-auth).
    @Suppress("UNUSED_PARAMETER") private val basicUser: String? = null,
    @Suppress("UNUSED_PARAMETER") private val basicPass: String? = null,
    private val faceProvider: () -> String,
    private val statusProvider: () -> String,
) {
    private var engine: ApplicationEngine? = null

    fun start() {
        check(engine == null) { "WebApiServer já está rodando" }
        val json = Json { encodeDefaults = true }
        engine = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) { json(json) }
            module()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }

    internal fun Application.module() {
        routing {
            get("/health") { call.respondText("ok") }
            get("/v1/face") { call.respond(HttpStatusCode.OK, faceProvider()) }
            get("/v1/status") { call.respond(HttpStatusCode.OK, statusProvider()) }
        }
    }
}
