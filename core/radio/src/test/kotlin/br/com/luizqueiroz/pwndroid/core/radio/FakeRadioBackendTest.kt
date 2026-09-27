package br.com.luizqueiroz.pwndroid.core.radio

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.MacAddress
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRadioBackendTest {

    private class FixedClock(private var now: Long) : AppClock {
        override fun nowMillis(): Long = now
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        fun advanceBy(millis: Long) {
            now += millis
        }
    }

    private fun newBackend(): FakeRadioBackend = FakeRadioBackend(FixedClock(1_000_000))

    private fun env() = object : BackendEnvironment {
        override val clock: AppClock get() = FixedClock(1_000_000)
        override val captureDir: String get() = "/tmp/pwndroid-test/captures"
    }

    @Test
    fun `script emite eventos na ordem programada`() = runTest {
        val backend = newBackend()
        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "cafe", channel = 6, rssi = -40)
            .station("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02")
            .handshake("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02")
            .peer("fp-123", "vizinho")
            .internet(viaPeer = true)

        val started = backend.start(env())

        started.startRecon(setOf(1, 6, 11), dwellMs = 100)
        started.stopRecon()
        started.shutdown()

        // O bus do fake tem replay: o cache contém todos os eventos emitidos,
        // na ordem de emissão — não precisa de collector.
        val events = started.events().replayCache

        assertEquals(
            listOf(
                "ApSeen", "StationSeen", "HandshakeDetected", "PeerSeen", "InternetAvailable",
                "ReconStarted", "ReconFinished",
            ),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun `assoc falha quando programada e depois volta a funcionar`() = runTest {
        val backend = newBackend()
        val mac = "AA:BB:CC:00:00:01"
        backend.script.failNextAssoc(mac)

        val started = backend.start(env())

        val first = started.assoc(MacAddress.parse(mac))
        assertTrue(first.isFailure)
        val second = started.assoc(MacAddress.parse(mac))
        assertTrue(second.isSuccess)
    }

    @Test
    fun `accessPoints devolve os APs programados`() = runTest {
        val backend = newBackend()
        backend.script
            .ap("AA:BB:CC:00:00:01", ssid = "casa", channel = 1)
            .ap("AA:BB:CC:00:00:02", ssid = "vizinho", channel = 6, rssi = -60)

        val started = backend.start(env())
        started.startRecon(setOf(1, 6), dwellMs = 100)

        val aps = started.accessPoints()
        assertEquals(2, aps.size)
        assertEquals("casa", aps.first { it.mac.value == "aabbcc000001" }.ssid)
        assertEquals(-60, aps.first { it.mac.value == "aabbcc000002" }.rssi)
    }

    @Test
    fun `setChannel e deauth registrados em operations`() = runTest {
        val backend = newBackend()
        val started = backend.start(env())

        started.setChannel(11)
        started.deauth(
            MacAddress.parse("AA:BB:CC:00:00:02"),
            MacAddress.parse("AA:BB:CC:00:00:01"),
        )
        assertTrue(backend.operations.any { it.startsWith("channel:11") })
        assertTrue(backend.operations.any { it.startsWith("deauth:") })
        assertFalse(backend.operations.any { it.startsWith("assoc") })
    }
}
