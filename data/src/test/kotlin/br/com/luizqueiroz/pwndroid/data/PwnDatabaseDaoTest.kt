package br.com.luizqueiroz.pwndroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.luizqueiroz.pwndroid.data.db.AccessPointEntity
import br.com.luizqueiroz.pwndroid.data.db.EpochEntity
import br.com.luizqueiroz.pwndroid.data.db.SightingEntity
import br.com.luizqueiroz.pwndroid.data.db.SessionEntity
import br.com.luizqueiroz.pwndroid.data.db.WhitelistEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testes de DAO da #15 (Room in-memory): upsert de AP com timesSeen,
 * agregações da tela Wardrive, FK cascade e whitelist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PwnDatabaseDaoTest {

    private lateinit var db: PwnDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PwnDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun novaSessao(backend: String = "PASSIVE"): Long =
        db.sessionDao().insert(
            SessionEntity(
                startedAtMillis = 1_000_000,
                backendId = backend,
                mode = "AUTO",
            ),
        )

    @Test
    fun `upsert de AP incrementa timesSeen e atualiza lastSeen`() = runTest {
        val sessionId = novaSessao()

        db.accessPointDao().upsert("aa:bb:cc:00:00:01", sessionId, "casa", "WPA2", 1_000)
        db.accessPointDao().upsert("aa:bb:cc:00:00:01", sessionId, "casa", "WPA2", 2_000)
        db.accessPointDao().upsert("aa:bb:cc:00:00:01", sessionId, null, null, 3_000)

        val ap = db.accessPointDao().byMac("aa:bb:cc:00:00:01")!!
        assertEquals(3, ap.timesSeen)
        assertEquals(3_000, ap.lastSeenMillis)
        // COALESCE: primeiro SSID não-NULL vence, nunca é apagado.
        assertEquals("casa", ap.ssid)
        assertEquals(1_000, ap.firstSeenMillis)
    }

    @Test
    fun `observeAll enriquece AP com meta dos sightings`() = runTest {
        val sessionId = novaSessao()
        db.accessPointDao().upsert("aa:bb:cc:00:00:01", sessionId, "casa", "WPA2", 1_000)
        db.sightingDao().insert(
            listOf(
                SightingEntity(
                    sessionId = sessionId,
                    apMac = "aa:bb:cc:00:00:01",
                    lat = -23.55,
                    lon = -46.63,
                    rssi = -55,
                    channel = 6,
                    seenAtMillis = 1_500,
                ),
                SightingEntity(
                    sessionId = sessionId,
                    apMac = "aa:bb:cc:00:00:01",
                    lat = null,
                    lon = null,
                    rssi = -40,
                    channel = 6,
                    seenAtMillis = 1_600,
                ),
            ),
        )

        val aps = db.accessPointDao().observeAll().first()
        assertEquals(1, aps.size)
        val ap = aps.first()
        assertEquals("casa", ap.ssid)
        assertEquals(-40, ap.bestRssi) // MAX(rssi)
        assertEquals(-23.55, ap.lastLat!!, 1e-9)
        assertEquals(2, ap.sightingCount)
    }

    @Test
    fun `sightings por AP e por sessão`() = runTest {
        val s1 = novaSessao()
        val s2 = novaSessao(backend = "FAKE")

        db.accessPointDao().upsert("aa:bb:cc:00:00:01", s1, "a", "WPA2", 1_000)
        db.accessPointDao().upsert("aa:bb:cc:00:00:02", s1, "b", "WPA3", 2_000)

        db.sightingDao().insert(
            listOf(
                SightingEntity(
                    sessionId = s1,
                    apMac = "aa:bb:cc:00:00:01",
                    rssi = -50,
                    channel = 6,
                    seenAtMillis = 1_100,
                ),
                SightingEntity(
                    sessionId = s1,
                    apMac = "aa:bb:cc:00:00:01",
                    rssi = -52,
                    channel = 6,
                    seenAtMillis = 1_200,
                ),
                SightingEntity(
                    sessionId = s2,
                    apMac = "aa:bb:cc:00:00:01",
                    rssi = -60,
                    channel = 11,
                    seenAtMillis = 3_000,
                ),
            ),
        )

        assertEquals(2, db.sightingDao().forAp("aa:bb:cc:00:00:01").filter { it.sessionId == s1 }.size)
        assertEquals(1, db.sightingDao().forSession(s2).size)
        // Nenhum sighting tem GPS neste teste: withPosition() deve vir vazio.
        assertTrue(db.sightingDao().withPosition().isEmpty())
    }

    @Test
    fun `epoch grava e fecha com métricas`() = runTest {
        val sessionId = novaSessao()
        val epochId = db.epochDao().insert(
            EpochEntity(
                sessionId = sessionId,
                number = 1,
                startedAtMillis = 1_000,
                personalityJson = """{"channel":6}""",
            ),
        )
        db.epochDao().close(epochId, durationMs = 5_000, apsSeen = 3, stations = 1, handshakes = 1, blind = false)

        val epochs = db.epochDao().forSession(sessionId)
        assertEquals(1, epochs.size)
        assertEquals(5_000L, epochs[0].durationMs)
        assertEquals(1, epochs[0].handshakes)
        assertEquals(false, epochs[0].blind)
    }

    @Test
    fun `whitelist insere sem duplicar e remove`() = runTest {
        db.whitelistDao().insert(WhitelistEntryEntity("aa:bb:cc:00:00:01", 1_000, "casa"))
        db.whitelistDao().insert(WhitelistEntryEntity("aa:bb:cc:00:00:01", 2_000, "de novo"))

        assertEquals(listOf("aa:bb:cc:00:00:01"), db.whitelistDao().macs())

        db.whitelistDao().remove("aa:bb:cc:00:00:01")
        assertTrue(db.whitelistDao().macs().isEmpty())
    }

    @Test
    fun `session fecha com totais`() = runTest {
        val id = novaSessao()
        db.sessionDao().close(id, endedAt = 9_999, apsSeen = 12, stations = 4, handshakes = 2)

        val s = db.sessionDao().recent().first()
        assertEquals(9_999L, s.endedAtMillis)
        assertEquals(12, s.apsSeen)
        assertEquals(2, s.handshakes)
    }

    @Test
    fun `apaga sessão apaga sightings e epochs em cascata`() = runTest {
        val sessionId = novaSessao()
        db.accessPointDao().upsert("aa:bb:cc:00:00:01", sessionId, "a", null, 1_000)
        db.sightingDao().insert(
            listOf(
                SightingEntity(
                    sessionId = sessionId,
                    apMac = "aa:bb:cc:00:00:01",
                    rssi = -50,
                    channel = 6,
                    seenAtMillis = 1_100,
                ),
            ),
        )
        db.epochDao().insert(
            EpochEntity(sessionId = sessionId, number = 1, startedAtMillis = 1_000),
        )

        // FK do sighting aponta para access_point: apagar o AP remove os
        // sightings em cascata.
        db.openHelper.writableDatabase.execSQL("DELETE FROM access_point WHERE mac = 'aa:bb:cc:00:00:01'")
        assertTrue(db.sightingDao().forSession(sessionId).isEmpty())

        // FK do epoch aponta para session: apagar a sessão remove as épocas.
        db.openHelper.writableDatabase.execSQL("DELETE FROM session WHERE id = $sessionId")
        assertTrue(db.epochDao().forSession(sessionId).isEmpty())
    }
}
