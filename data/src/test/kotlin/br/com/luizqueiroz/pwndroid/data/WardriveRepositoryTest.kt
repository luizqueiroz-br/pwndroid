package br.com.luizqueiroz.pwndroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WardriveRepository (issue #18): gravação de sightings, whitelist e
 * export WiGLE/KML sobre o Room da #15.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WardriveRepositoryTest {

    private lateinit var db: PwnDatabase
    private lateinit var repo: WardriveRepository
    private lateinit var whitelist: WhitelistRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PwnDatabase::class.java).build()
        repo = WardriveRepository(db)
        whitelist = WhitelistRepository(db.whitelistDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `recordSighting grava sighting e faz upsert do AP`() = runTest {
        val sessionId = repo.startSession("PASSIVE", "AUTO", startedAt = 1_000)

        repo.recordSighting(
            WardriveRepository.SightingRecord(
                sessionId = sessionId,
                mac = "aa:bb:cc:00:00:01",
                ssid = "casa",
                encryption = "WPA2",
                rssi = -50,
                channel = 6,
                lat = -23.55,
                lon = -46.63,
                seenAtMillis = 1_100,
            ),
        )

        val detail = repo.accessPointDetail("aa:bb:cc:00:00:01")!!
        assertEquals("casa", detail.ap.ssid)
        assertEquals(1, detail.sightings.size)
        assertEquals(-23.55, detail.sightings[0].lat!!, 1e-9)
    }

    @Test
    fun `accessPointDetail de AP inexistente é null`() = runTest {
        assertNull(repo.accessPointDetail("ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun `whitelist adiciona remove e lista`() = runTest {
        whitelist.add("aa:bb:cc:00:00:01", "casa", addedAt = 1_000)
        // Segundo insert é ignorado (IGNORE no conflito).
        whitelist.add("aa:bb:cc:00:00:01", "de novo", addedAt = 2_000)

        assertEquals(listOf("aa:bb:cc:00:00:01"), whitelist.macs())

        whitelist.remove("aa:bb:cc:00:00:01")
        assertTrue(whitelist.macs().isEmpty())
    }

    @Test
    fun `export de sessão inclui SSID do AP no CSV`() = runTest {
        val sessionId = repo.startSession("PASSIVE", "AUTO", startedAt = 1_000)
        repo.recordSighting(
            WardriveRepository.SightingRecord(
                sessionId = sessionId,
                mac = "aa:bb:cc:00:00:01",
                ssid = "café",
                encryption = "WPA2",
                rssi = -50,
                channel = 6,
                lat = -23.55,
                lon = -46.63,
                seenAtMillis = 1_100,
            ),
        )

        val (csv, kml) = repo.exportSessionCsvKml(sessionId) { _ -> "casa" }

        assertTrue(csv.contains("aa:bb:cc:00:00:01,casa,"))
        assertTrue(kml.contains("<name>casa</name>"))
    }

    @Test
    fun `export completo usa ssid e encryption do AP`() = runTest {
        val sessionId = repo.startSession("PASSIVE", "AUTO", startedAt = 1_000)
        repo.recordSighting(
            WardriveRepository.SightingRecord(
                sessionId = sessionId,
                mac = "aa:bb:cc:00:00:01",
                ssid = "rede",
                encryption = "WPA3",
                rssi = -60,
                channel = 1,
                lat = 1.0,
                lon = 2.0,
                seenAtMillis = 1_100,
            ),
        )

        val (csv, _) = repo.exportAllCsvKml()

        assertTrue(csv.contains("rede"))
        assertFalse(csv.contains(",NONE,"))
    }

    @Test
    fun `sessão fecha com totais`() = runTest {
        val id = repo.startSession("PASSIVE", "AUTO", startedAt = 1_000)
        repo.closeSession(id, endedAt = 2_000, apsSeen = 5, stations = 1, handshakes = 0)

        val sessions = db.sessionDao().recent()
        assertEquals(1, sessions.size)
        assertEquals(5, sessions[0].apsSeen)
        assertNotNull(sessions[0].endedAtMillis)
    }
}
