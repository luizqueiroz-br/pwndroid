package br.com.luizqueiroz.pwndroid.core.radio.passive

import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do parse de anúncio BLE — puro, sem Android (o parse foi
 * extraído para companhion object exatamente por isso).
 */
class BlePeerScannerTest {

    private fun data(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `anuncio pwndroid vira PeerSeen`() {
        val event = BlePeerScanner.parseAdvertisement(
            data("pwndroid://aabbccdd"),
            deviceName = "pwn-alice",
        )
        assertTrue(event is RadioEvent.PeerSeen)
        event as RadioEvent.PeerSeen
        assertEquals("aabbccdd", event.fingerprint)
        assertEquals("pwn-alice", event.name)
    }

    @Test
    fun `anuncio sem nome emite PeerSeen com name nulo`() {
        val event = BlePeerScanner.parseAdvertisement(data("pwndroid://ff00"), null)
        assertTrue(event is RadioEvent.PeerSeen)
        assertEquals(null, (event as RadioEvent.PeerSeen).name)
    }

    @Test
    fun `service data de outro servico é ignorado`() {
        assertNull(BlePeerScanner.parseAdvertisement(data("outro://xyz"), "dev"))
        assertNull(BlePeerScanner.parseAdvertisement(data("pwndroid:"), "dev"))
    }

    @Test
    fun `fingerprint vazio é ignorado`() {
        assertNull(BlePeerScanner.parseAdvertisement(data("pwndroid://"), "dev"))
    }

    @Test
    fun `service data nulo é ignorado`() {
        assertNull(BlePeerScanner.parseAdvertisement(null, "dev"))
    }
}
