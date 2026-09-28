package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpMethod
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do [BettercapApi] com Ktor MockEngine (issue #20): cada comando
 * REST gera a requisição correta (método, path, auth Basic, corpo).
 */
class BettercapApiTest {

    /** Captura as requisições e responde com o corpo configurado. */
    private class RecordingEngine(
        private val status: HttpStatusCode = HttpStatusCode.OK,
        private val responseBody: String = "{}",
    ) {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()

        fun engine(): MockEngine = MockEngine { request ->
            requests.add(request)
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }

    /** Cliente com o plugin WS (necessário para a interface da API). */
    private fun clientOf(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(WebSockets)
    }

    private fun api(client: HttpClient) = HttpBettercapApi(
        baseUrl = "http://127.0.0.1:8081",
        username = "user",
        password = "pass",
        client = client,
    )

    @Test
    fun `run envia POST com cmd json e auth basic`() = runTest {
        val rec = RecordingEngine()
        val client = clientOf(rec.engine())
        val bettercap = api(client)
        val result = bettercap.run("wifi.recon.channel 6")

        assertTrue(result.isSuccess)
        val request = rec.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/session", request.url.encodedPath)
        val auth = request.headers[HttpHeaders.Authorization]!!
        assertTrue(auth.startsWith("Basic "))
        // user:pass em base64.
        assertEquals("Basic dXNlcjpwYXNz", auth)
        // O corpo é OutgoingContent.ByteArrayContent — bytes() direto.
        val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
            .bytes()
            .decodeToString()
        assertTrue(body.contains("\"cmd\""))
        assertTrue(body.contains("wifi.recon.channel 6"))
        client.close()
    }

    @Test
    fun `run com HTTP 500 lança erro com status`() = runTest {
        val rec = RecordingEngine(status = HttpStatusCode.InternalServerError)
        val client = clientOf(rec.engine())
        val bettercap = api(client)
        val result = bettercap.run("wifi.assoc AA:BB:CC:DD:EE:01")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
        client.close()
    }

    @Test
    fun `session parseia fixture de APs e STAs`() = runTest {
        val fixture = File("src/test/resources/fixtures/session.json").readText()
        val rec = RecordingEngine(responseBody = fixture)
        val client = clientOf(rec.engine())
        val bettercap = api(client)
        val session = bettercap.session().getOrThrow()

        assertEquals(2, session.accessPoints.size)
        val ap = session.accessPoints.first()
        assertEquals("AA:BB:CC:DD:EE:01", ap.mac)
        assertEquals("Casa-2G", ap.essid)
        assertEquals(6, ap.channel)
        assertEquals(-42, ap.rssi)
        assertEquals("WPA2", ap.encryption)
        assertEquals(1, session.stations.size)
        assertEquals("11:22:33:44:55:66", session.stations.first().mac)
        assertEquals("AA:BB:CC:DD:EE:01", session.stations.first().apMac)
        client.close()
    }

    @Test
    fun `session com 401 lança erro`() = runTest {
        val rec = RecordingEngine(status = HttpStatusCode.Unauthorized)
        val client = clientOf(rec.engine())
        val bettercap = api(client)
        val result = bettercap.session()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
        client.close()
    }

    @Test
    fun `auth header presente no GET session`() = runTest {
        val rec = RecordingEngine(responseBody = "{}")
        val client = clientOf(rec.engine())
        val bettercap = api(client)
        bettercap.session()
        val request = rec.requests.single()
        assertEquals("Basic dXNlcjpwYXNz", request.headers[HttpHeaders.Authorization])
        client.close()
    }
}
