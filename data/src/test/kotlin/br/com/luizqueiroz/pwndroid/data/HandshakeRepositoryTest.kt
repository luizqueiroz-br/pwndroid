package br.com.luizqueiroz.pwndroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HandshakeRepository (issue #24): dedup por bssid+essid+type, upgrade
 * HALF→FULL e listagem da tela Handshakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandshakeRepositoryTest {

    private lateinit var db: PwnDatabase
    private lateinit var repo: HandshakeRepository

    private fun capture(
        bssid: String = "00:0C:41:82:B2:55",
        essid: String? = "Coherer",
        type: String,
        path: String = "/data/pcap.pcap",
        station: String = "00:0D:93:82:36:3A",
        capturedAtMillis: Long = 1_000,
        sessionId: Long? = null,
    ) = HandshakeEntity(
        bssid = bssid,
        essid = essid,
        type = type,
        pcapPath = path,
        station = station,
        capturedAtMillis = capturedAtMillis,
        sessionId = sessionId,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PwnDatabase::class.java).build()
        repo = HandshakeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `record grava handshake novo`() = runTest {
        val outcome = repo.record(
            capture(type = HandshakeRepository.TYPE_FULL, capturedAtMillis = 1_234),
        )
        assertEquals(HandshakeRepository.RecordOutcome.NEW, outcome)
        val list = repo.observeAll().first()
        assertEquals(1, list.size)
        assertEquals("Coherer", list[0].essid)
        assertEquals(HandshakeRepository.TYPE_FULL, list[0].type)
        assertEquals(1_234, list[0].capturedAtMillis)
    }

    @Test
    fun `mesma captura re-registrada não duplica`() = runTest {
        val cap = capture(type = HandshakeRepository.TYPE_FULL)
        assertEquals(HandshakeRepository.RecordOutcome.NEW, repo.record(cap))
        // Mesma chave, mesmo path: ignorada (dedup).
        assertEquals(
            HandshakeRepository.RecordOutcome.DUPLICATE,
            repo.record(cap.copy(capturedAtMillis = 2_000)),
        )
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test
    fun `full existente ignora half e pmkid do mesmo par`() = runTest {
        repo.record(capture(type = HandshakeRepository.TYPE_FULL))
        assertEquals(
            HandshakeRepository.RecordOutcome.DUPLICATE,
            repo.record(capture(type = HandshakeRepository.TYPE_HALF)),
        )
        assertEquals(
            HandshakeRepository.RecordOutcome.DUPLICATE,
            repo.record(capture(type = HandshakeRepository.TYPE_PMKID)),
        )
        val list = repo.observeAll().first()
        assertEquals(1, list.size)
        assertEquals(HandshakeRepository.TYPE_FULL, list[0].type)
    }

    @Test
    fun `pmkid e half do mesmo par coexistem`() = runTest {
        assertEquals(
            HandshakeRepository.RecordOutcome.NEW,
            repo.record(capture(type = HandshakeRepository.TYPE_PMKID)),
        )
        assertEquals(
            HandshakeRepository.RecordOutcome.NEW,
            repo.record(capture(type = HandshakeRepository.TYPE_HALF)),
        )
        val types = repo.observeAll().first().map { it.type }.toSet()
        assertEquals(setOf(HandshakeRepository.TYPE_PMKID, HandshakeRepository.TYPE_HALF), types)
    }

    @Test
    fun `half re-capturado com path novo atualiza o registro`() = runTest {
        repo.record(capture(type = HandshakeRepository.TYPE_HALF, path = "/old.pcap"))
        val outcome = repo.record(
            capture(type = HandshakeRepository.TYPE_HALF, path = "/new.pcap", capturedAtMillis = 5_000),
        )
        assertEquals(HandshakeRepository.RecordOutcome.UPGRADED, outcome)
        val list = repo.observeAll().first()
        assertEquals(1, list.size)
        assertEquals("/new.pcap", list[0].pcapPath)
        assertEquals(5_000, list[0].capturedAtMillis)
    }

    @Test
    fun `full novo substitui half do mesmo par`() = runTest {
        repo.record(capture(type = HandshakeRepository.TYPE_HALF, path = "/half.pcap"))
        val outcome = repo.record(capture(type = HandshakeRepository.TYPE_FULL, path = "/full.pcap"))
        assertEquals(HandshakeRepository.RecordOutcome.UPGRADED, outcome)
        val list = repo.observeAll().first()
        assertEquals(1, list.size)
        assertEquals(HandshakeRepository.TYPE_FULL, list[0].type)
        assertEquals("/full.pcap", list[0].pcapPath)
    }

    @Test
    fun `pares diferentes não colidem`() = runTest {
        repo.record(capture(type = HandshakeRepository.TYPE_FULL, bssid = "AA:BB:CC:00:00:01"))
        repo.record(capture(type = HandshakeRepository.TYPE_FULL, bssid = "AA:BB:CC:00:00:02"))
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test
    fun `essid null (oculto) e essid string são chaves distintas`() = runTest {
        repo.record(capture(type = HandshakeRepository.TYPE_FULL, essid = null))
        repo.record(capture(type = HandshakeRepository.TYPE_FULL, essid = "Rede"))
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test
    fun `contagem de sessão e marcação de upload`() = runTest {
        repo.record(capture(type = HandshakeRepository.TYPE_FULL, sessionId = 7))
        repo.record(capture(type = HandshakeRepository.TYPE_FULL, bssid = "AA:BB:CC:00:00:02"))
        assertEquals(1, repo.countOfSession(7))
        val saved = repo.observeAll().first().first()
        repo.markUploadedWpaSec(saved.id, uploaded = true)
        org.junit.Assert.assertTrue(repo.byId(saved.id)!!.uploadedWpaSec)
        repo.setUploadedOnlineHashCracking(saved.id, uploaded = true)
        org.junit.Assert.assertTrue(repo.byId(saved.id)!!.uploadedOnlineHashCracking)
    }

    @Test
    fun `byId retorna null para id inexistente`() = runTest {
        assertNull(repo.byId(999))
    }
}
