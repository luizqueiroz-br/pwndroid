package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do [BettercapStarted] (issue #21): amarração da [BettercapApi]
 * na interface [br.com.luizqueiroz.pwndroid.core.radio.StartedBackend] —
 * comandos REST emitidos, snapshot da sessão mapeado para o domínio e
 * eventos WS re-publicados no SharedFlow.
 */
class BettercapBackendTest {

    /** API fake: grava comandos, responde a session() e roteia frames WS. */
    private class FakeApi : BettercapApi {

        val commands = mutableListOf<String>()
        val frames = mutableListOf<String>()

        override suspend fun run(command: String): Result<Unit> {
            commands.add(command)
            return Result.success(Unit)
        }

        override suspend fun session(): Result<BettercapSession> = runCatching {
            val fixture = File("src/test/resources/fixtures/session.json").readText()
            BettercapJson.parseSession(fixture)
        }

        override suspend fun events(): Result<WsSession> = runCatching {
            FakeWs(frames)
        }

        override fun close() = Unit
    }

    /** WS fake: entrega os frames configurados e fecha. */
    private class FakeWs(private val frames: List<String>) : WsSession {
        private var index = 0

        override suspend fun receive(): String? = frames.getOrNull(index++)

        override fun close() = Unit
    }

    private class NoClock : AppClock {
        override fun nowMillis(): Long = 1_700_000_000_000
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun started(api: FakeApi): BettercapStarted = BettercapStarted(
        api = api,
        shell = RecordingShell(),
        pcapDir = "/data/local/tmp/pwndroid/handshakes",
        clock = NoClock(),
        events = eventFlow(),
    )

    /** Flow de eventos com replay — captura tudo, mesmo sem subscriber. */
    private fun eventFlow(): kotlinx.coroutines.flow.MutableSharedFlow<RadioEvent> =
        kotlinx.coroutines.flow.MutableSharedFlow(replay = 64, extraBufferCapacity = 64)

    @Test
    fun `startRecon configura canal hop e liga recon`() = runTest {
        val api = FakeApi()
        val backend = started(api)
        backend.startRecon(channels = setOf(6, 1, 11), dwellMs = 250)

        assertEquals(
            listOf(
                "wifi.recon.channel 1,6,11",
                "set wifi.hop.period 250",
                "wifi.recon on",
            ),
            api.commands,
        )
    }

    @Test
    fun `startRecon sem canais faz hop em todos`() = runTest {
        val api = FakeApi()
        val backend = started(api)
        backend.startRecon(channels = emptySet(), dwellMs = 250)

        assertTrue(api.commands.contains("wifi.recon.channel clear"))
        assertTrue(api.commands.contains("wifi.recon on"))
    }

    @Test
    fun `accessPoints mapeia session para o dominio com clientes`() = runTest {
        val api = FakeApi()
        val backend = started(api)
        val aps = backend.accessPoints()

        assertEquals(2, aps.size)
        val first = aps[0]
        assertEquals("aabbccddee01", first.mac.value)
        assertEquals("Casa-2G", first.ssid)
        assertEquals(6, first.channel)
        assertEquals(-42, first.rssi)
        assertEquals("WPA2", first.encryption)
        // Estação aninhada ao AP pelo ap_mac.
        assertEquals(1, first.clients.size)
        assertEquals("112233445566", first.clients.first().mac.value)
        // AP com essid vazio vira ssid null.
        assertNull(aps[1].ssid)
    }

    @Test
    fun `assoc e deauth emitem comandos com mac formatado`() = runTest {
        val api = FakeApi()
        val backend = started(api)

        val assoc = backend.assoc(MacAddress.parse("AA:BB:CC:DD:EE:01"))
        val deauth = backend.deauth(MacAddress.parse("11:22:33:44:55:66"), MacAddress.parse("AA:BB:CC:DD:EE:01"))
        val deauthBroadcast = backend.deauth(null, MacAddress.parse("AA:BB:CC:DD:EE:01"))

        assertTrue(assoc.isSuccess)
        assertTrue(deauth.isSuccess)
        assertTrue(deauthBroadcast.isSuccess)
        assertEquals(
            listOf(
                "wifi.assoc aa:bb:cc:dd:ee:01",
                "wifi.deauth 11:22:33:44:55:66",
                "wifi.deauth aa:bb:cc:dd:ee:01",
            ),
            api.commands,
        )
    }

    @Test
    fun `applyPersonality reflete nos set do bettercap`() = runTest {
        val api = FakeApi()
        val backend = started(api)
        backend.applyPersonality(
            br.com.luizqueiroz.pwndroid.core.model.Personality(
                apTtlSec = 60,
                staTtlSec = 30,
                minRssi = -80,
            ),
        )

        assertEquals(
            listOf(
                "set wifi.ap.ttl 60",
                "set wifi.sta.ttl 30",
                "set wifi.rssi.min -80",
            ),
            api.commands,
        )
    }

    @Test
    fun `setChannel emite comando e evento ChannelChanged`() = runTest {
        val api = FakeApi()
        val flow = eventFlow()
        val backend = BettercapStarted(
            api = api,
            shell = RecordingShell(),
            pcapDir = "/tmp",
            clock = NoClock(),
            events = flow,
        )

        backend.setChannel(6)

        assertTrue(api.commands.contains("wifi.recon.channel 6"))
        // Replay cache: captura o evento mesmo sem subscriber ativo.
        assertTrue(flow.replayCache.single() is RadioEvent.ChannelChanged)
    }

    @Test
    fun `comando REST falhado volta como Result failure`() = runTest {
        val api = object : BettercapApi by FakeApi() {
            override suspend fun run(command: String): Result<Unit> =
                if (command.startsWith("wifi.assoc")) {
                    Result.failure(BettercapApiException("HTTP 500"))
                } else {
                    Result.success(Unit)
                }

            override suspend fun session(): Result<BettercapSession> = FakeApi().session()

            override suspend fun events(): Result<WsSession> = Result.success(
                FakeWs(emptyList()),
            )
        }
        val backend = BettercapStarted(
            api = api,
            shell = RecordingShell(),
            pcapDir = "/tmp",
            clock = NoClock(),
            events = eventFlow(),
        )
        val result = backend.assoc(MacAddress.parse("AA:BB:CC:DD:EE:01"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
    }

    @Test
    fun `shutdown fecha api e shell`() = runTest {
        val api = FakeApi()
        val shell = RecordingShell()
        val backend = BettercapStarted(
            api = api,
            shell = shell,
            pcapDir = "/tmp",
            clock = NoClock(),
            events = eventFlow(),
        )
        backend.shutdown()

        assertTrue(api.commands.contains("wifi.recon off"))
        assertTrue(shell.closed)
    }

    @Test
    fun `beginEvents publica eventos WS no SharedFlow`() = runTest {
        val api = FakeApi().apply {
            frames.addAll(
                listOf(
                    """{"tag": "wifi.ap.new", "ap": {"mac": "AA:BB:CC:DD:EE:03", "essid": "Vizinho"}}""",
                    """{"tag": "wifi.client.handshake", "ap": {"mac": "AA:BB:CC:DD:EE:01"},""" +
                        """ "client": {"mac": "11:22:33:44:55:66"}, "file": "/root/h.pcap"}""",
                ),
            )
        }
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<RadioEvent>(replay = 64, extraBufferCapacity = 64)
        val backend = BettercapStarted(
            api = api,
            shell = RecordingShell(),
            pcapDir = "/data/local/tmp/pwndroid/handshakes",
            clock = NoClock(),
            events = flow,
        )

        backend.beginEvents()
        backend.shutdown()

        // Replay cache: eventos emitidos antes de qualquer subscriber.
        // 2 eventos WS + BackendError (stream esgotado após shutdown).
        val collected = flow.replayCache
        assertEquals(3, collected.size)
        assertTrue(collected[0] is RadioEvent.ApSeen)
        val handshake = collected[1] as RadioEvent.HandshakeDetected
        assertEquals("AA:BB:CC:DD:EE:01", handshake.bssid)
        assertEquals("/root/h.pcap", handshake.pcapPath)
    }
}

/** Shell fake para os testes do StartedBackend (não executa nada). */
private class RecordingShell :
    br.com.luizqueiroz.pwndroid.core.radio.bettercap.RootShell {
    var closed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun exec(command: String): ShellResult = ShellResult(0, emptyList(), emptyList())

    override fun close() {
        closed = true
    }
}



