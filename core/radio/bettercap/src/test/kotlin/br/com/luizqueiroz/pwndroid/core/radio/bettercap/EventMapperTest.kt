package br.com.luizqueiroz.pwndroid.core.radio.bettercap

import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapeamento de eventos bettercap → [RadioEvent] contra fixtures de
 * JSON reais do bettercap (issue #20).
 */
class EventMapperTest {

    private fun fixture(name: String): String =
        File("src/test/resources/fixtures/$name").readText()

    @Test
    fun `handshake fixture mapeia para HandshakeDetected`() {
        val frame = fixture("event_handshake.json")
        val event = EventMapper.toRadioEvent(frame, "/tmp/pcap")
            as RadioEvent.HandshakeDetected
        assertEquals("AA:BB:CC:DD:EE:01", event.bssid)
        assertEquals("11:22:33:44:55:66", event.station)
        assertEquals("Casa-2G", event.essid)
        // O bettercap informa o arquivo do pcap capturado.
        assertEquals(
            "/root/handshakes/CASA-2G_AA-BB-CC-DD-EE-01.pcap",
            event.pcapPath,
        )
        assertEquals(false, event.isPmkid)
    }

    @Test
    fun `ap new fixture mapeia para ApSeen`() {
        val event = EventMapper.toRadioEvent(
            fixture("event_ap_new.json"),
            "/tmp/pcap",
        ) as RadioEvent.ApSeen
        assertEquals("AA:BB:CC:DD:EE:03", event.bssid)
        assertEquals("Vizinho", event.essid)
        assertEquals(1, event.channel)
        assertEquals(-60, event.rssi)
        assertEquals("WPA3", event.encryption)
    }

    @Test
    fun `client new fixture mapeia para StationSeen`() {
        val event = EventMapper.toRadioEvent(
            fixture("event_client_new.json"),
            "/tmp/pcap",
        ) as RadioEvent.StationSeen
        assertEquals("22:33:44:55:66:77", event.station)
        assertEquals("AA:BB:CC:DD:EE:01", event.bssid)
        assertEquals(-51, event.rssi)
    }

    @Test
    fun `internet available fixture mapeia`() {
        val event = EventMapper.toRadioEvent(
            fixture("event_internet.json"),
            "/tmp/pcap",
        )
        assertEquals(RadioEvent.InternetAvailable(viaPeer = false), event)
    }

    @Test
    fun `evento desconhecido vira null sem erro`() {
        assertNull(EventMapper.toRadioEvent("""{"tag": "bt.new.device"}""", "/tmp"))
    }

    @Test
    fun `handshake sem ap vira null`() {
        assertNull(
            EventMapper.toRadioEvent(
                """{"tag": "wifi.client.handshake", "client": {"mac": "AA"}}""",
                "/tmp",
            ),
        )
    }

    @Test
    fun `handshake com pmkid marca isPmkid`() {
        val frame = """
            {"tag": "wifi.client.handshake", "ap": {"mac": "AA:BB:CC:DD:EE:01"},
             "client": {"mac": "11:22:33:44:55:66"}, "pmkid": "abc123def"}
        """.trimIndent()
        val event = EventMapper.toRadioEvent(frame, "/tmp") as RadioEvent.HandshakeDetected
        assertTrue(event.isPmkid)
    }
}
