package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do [BettercapEventStream] (issue #20): reconexão com backoff
 * quando o WS cai, mapeamento 1:1 dos frames, fim após esgotar
 * tentativas.
 */
class BettercapEventStreamTest {

    /** API fake com fila de frames por conexão. */
    private class FakeApi(
        /** Cada entrada é uma conexão: lista de frames (null = fecha). */
        private val connections: List<List<String?>>,
    ) : BettercapApi {

        var connectionsOpened = 0

        override suspend fun run(command: String): Result<Unit> = Result.success(Unit)

        override suspend fun session(): Result<BettercapSession> =
            Result.success(BettercapSession(emptyList(), emptyList()))

        override suspend fun events(): Result<WsSession> {
            val frames = connections.getOrElse(connectionsOpened) { emptyList() }
            connectionsOpened++
            return Result.success(FakeWs(frames))
        }

        override fun close() = Unit
    }

    private class FakeWs(private val frames: List<String?>) : WsSession {
        private var index = 0

        override suspend fun receive(): String? =
            frames.getOrNull(index++)

        override fun close() = Unit
    }

    @Test
    fun `frames de fixture mapeiam para RadioEvent em ordem`() = runTest {
        val api = FakeApi(
            connections = listOf(
                listOf(
                    """{"tag": "wifi.ap.new", "ap": {"mac": "AA:BB:CC:DD:EE:03"}}""",
                    """{"tag": "modem.internet_available"}""",
                ),
            ),
        )
        val events = BettercapEventStream(api, "/tmp/pcap")
            .radioEvents()
            .toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is RadioEvent.ApSeen)
        assertTrue(events[1] is RadioEvent.InternetAvailable)
    }

    @Test
    fun `ws que cai reconecta até 3 vezes`() = runTest {
        val api = FakeApi(
            connections = listOf(
                // Conexão 1: um evento e cai (frames vazios = WS fechou).
                listOf(),
                // Conexão 2: um evento e cai de novo.
                listOf(),
                // Conexão 3: reconexão 2 — emit e cai.
                listOf(),
                // Conexão 4: além do limite de 3 reconexões.
                listOf(),
            ),
        )
        val stream = BettercapEventStream(api, "/tmp/pcap")
        val events = stream.radioEvents().toList()
        // Sem eventos de dados (conexões vazias), mas 4 conexões abertas
        // = 1 inicial + 3 reconexões, depois desiste.
        assertEquals(BettercapEventStream.MAX_RECONNECT + 1, api.connectionsOpened)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `conexão nova reseta backoff`() = runTest {
        val api = FakeApi(
            connections = listOf(
                // Cai 1x (backoff 1s), reconecta e estabiliza.
                listOf(),
                listOf("""{"tag": "modem.internet_available"}""", null),
            ),
        )
        val events = BettercapEventStream(api, "/tmp/pcap")
            .radioEvents()
            .toList()
        assertEquals(1, events.size)
        // 2 conexões: a inicial + 1 reconexão (backoff resetado —
        // se não resetasse, a 2ª queda encerraria o stream).
        assertEquals(2, api.connectionsOpened)
    }

    @Test
    fun `frame inválido não derruba o stream`() = runTest {
        val api = FakeApi(
            connections = listOf(
                listOf(
                    "não é json",
                    """{"tag": "modem.internet_available"}""",
                ),
            ),
        )
        val events = BettercapEventStream(api, "/tmp/pcap")
            .radioEvents()
            .toList()
        // O frame inválido é descartado; o próximo evento passa.
        assertEquals(1, events.size)
    }
}
