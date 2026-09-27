package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import br.com.luizqueiroz.pwndroid.core.radio.BackendEnvironment
import br.com.luizqueiroz.pwndroid.core.radio.BackendUnavailableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassiveBackendTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** WifiManager real do Robolectric com scanResults injetados. */
    private fun wifiManager(vararg results: ScanResult): WifiManager {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wifi).setScanResults(results.toList())
        return wifi
    }

    private fun scanResult(bssid: String, ssid: String, freq: Int, level: Int): ScanResult {
        val r = ScanResult()
        r.BSSID = bssid
        r.SSID = ssid
        r.frequency = freq
        r.level = level
        return r
    }

    @Test
    fun `mapper converte ScanResult em ApSeen com canal correto`() {
        val events = ScanResultMapper.toApSeen(
            listOf(
                scanResult("AA:BB:CC:00:00:01", "rede-a", 2412, -50),
                scanResult("AA:BB:CC:00:00:02", "", 5180, -60),
            ),
        )
        assertEquals(2, events.size)
        assertEquals("aa:bb:cc:00:00:01", events[0].bssid)
        assertEquals("rede-a", events[0].essid)
        assertEquals(1, events[0].channel)
        assertEquals(-50, events[0].rssi)
        // SSID vazio → null (rede oculta), canal 36 (5 GHz).
        assertEquals(null, events[1].essid)
        assertEquals(36, events[1].channel)
    }

    @Test
    fun `backend indisponível sem localização`() = runTest {
        val backend = PassiveBackend(
            deps = PassiveDependencies(context),
            wifiManagerProvider = { wifiManager() },
            checks = FixedChecks(location = false, wifi = true),
        )
        val result = runCatching {
            kotlinx.coroutines.runBlocking { backend.start(env(NoClock())) }
        }
        assertTrue(result.exceptionOrNull() is BackendUnavailableException)
    }

    @Test
    fun `backend indisponível com wifi desligado`() = runTest {
        val backend = PassiveBackend(
            deps = PassiveDependencies(context),
            wifiManagerProvider = { wifiManager() },
            checks = FixedChecks(location = true, wifi = false),
        )
        val result = runCatching {
            kotlinx.coroutines.runBlocking { backend.start(env(NoClock())) }
        }
        assertTrue(result.exceptionOrNull() is BackendUnavailableException)
    }

    @Test
    fun `recon emite ApSeen no bus e para no stopRecon`() = runTest {
        val clock = TestClock(testScheduler)
        val backend = PassiveBackend(
            deps = PassiveDependencies(context),
            wifiManagerProvider = { wifiManager(scanResult("AA:BB:CC:00:00:01", "rede", 2412, -40)) },
            checks = FixedChecks(location = true, wifi = true),
        )
        val started = kotlinx.coroutines.runBlocking { backend.start(env(clock)) }

        started.startRecon(setOf(1), dwellMs = 1_000)
        advanceTimeBy(2_500)
        val seen = started.events().replayCache
            .filterIsInstance<RadioEvent.ApSeen>()
        assertTrue(seen.isNotEmpty())
        assertEquals("aa:bb:cc:00:00:01", seen.first().bssid)

        started.stopRecon()
        val countAfterStop = started.events().replayCache.size
        advanceTimeBy(5_000)
        assertEquals(countAfterStop, started.events().replayCache.size)
        started.shutdown()
    }

    @Test
    fun `operações de injeção falham com UnsupportedOperation`() = runTest {
        val clock = TestClock(testScheduler)
        val backend = PassiveBackend(
            deps = PassiveDependencies(context),
            wifiManagerProvider = { wifiManager() },
            checks = FixedChecks(location = true, wifi = true),
        )
        val started = kotlinx.coroutines.runBlocking { backend.start(env(clock)) }

        val ap = br.com.luizqueiroz.pwndroid.core.model.MacAddress.parse("aa:bb:cc:00:00:01")
        assertTrue(started.assoc(ap).isFailure)
        assertTrue(started.deauth(null, ap).isFailure)
        assertTrue(started.setChannel(6).isFailure)
        started.shutdown()
    }

    private fun env(clock: AppClock) = object : BackendEnvironment {
        override val clock: AppClock get() = clock
        override val captureDir = "/tmp"
    }

    /** Checagens fixas do sistema. */
    private class FixedChecks(private val location: Boolean, private val wifi: Boolean) :
        SystemChecks {
        override fun isLocationEnabled(): Boolean = location
        override fun isWifiEnabled(): Boolean = wifi
    }

    /** Clock ligado ao scheduler do runTest (delay virtual). */
    private class TestClock(scheduler: TestCoroutineScheduler) : AppClock {
        private val sched = scheduler
        override fun nowMillis(): Long = sched.currentTime
        override val io: CoroutineDispatcher = StandardTestDispatcher(scheduler)
        override val default: CoroutineDispatcher = StandardTestDispatcher(scheduler)
    }

    private class NoClock : AppClock {
        override fun nowMillis(): Long = 0
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }
}
