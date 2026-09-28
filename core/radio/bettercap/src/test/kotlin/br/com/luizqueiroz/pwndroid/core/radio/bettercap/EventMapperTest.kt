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
        // v2.41 (spike #22): ap/station são strings MAC e o payload
        // vive em `data` do frame.
        assertEquals("AA:BB:CC:DD:EE:01", event.bssid)
        assertEquals("11:22:33:44:55:66", event.station)
        // A string MAC não carrega essid — sem objeto AP no payload.
        assertNull(event.essid)
        // O bettercap informa o arquivo do pcap capturado.
        assertEquals(
            "/root/handshakes/CASA-2G_AA-BB-CC-DD-EE-01.pcap",
            event.pcapPath,
        )
        assertEquals(false, event.isPmkid)
    }

    @Test
    fun `handshake com ap aninhado (formato antigo) também mapeia`() {
        val frame = """
            {"tag": "wifi.client.handshake",
             "data": {"ap": {"mac": "AA:BB:CC:DD:EE:01", "hostname": "Casa-2G"},
                      "client": {"mac": "11:22:33:44:55:66"}, "file": "/root/x.pcap"}}
        """.trimIndent()
        val event = EventMapper.toRadioEvent(frame, "/tmp") as RadioEvent.HandshakeDetected
        assertEquals("AA:BB:CC:DD:EE:01", event.bssid)
        assertEquals("Casa-2G", event.essid)
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
    fun `handshake com pmkid marca isPmkid`() {
        // v2.41: pmkid não-null (base64/hex) marca PMKID attack.
        val frame = """
            {"tag": "wifi.client.handshake",
             "data": {"ap": "AA:BB:CC:DD:EE:01", "station": "11:22:33:44:55:66",
                      "pmkid": "abc123def", "half": false, "full": false}}
        """.trimIndent()
        val event = EventMapper.toRadioEvent(frame, "/tmp") as RadioEvent.HandshakeDetected
        assertTrue(event.isPmkid)
    }

    @Test
    fun `handshake com pmkid null não marca isPmkid`() {
        val frame = """
            {"tag": "wifi.client.handshake",
             "data": {"ap": "AA:BB:CC:DD:EE:01", "station": "11:22:33:44:55:66",
                      "pmkid": null, "half": true, "full": false}}
        """.trimIndent()
        val event = EventMapper.toRadioEvent(frame, "/tmp") as RadioEvent.HandshakeDetected
        assertTrue(!event.isPmkid)
    }

    @Test
    fun `handshake v241 com pmkid array de bytes marca isPmkid`() {
        val frame = """
            {"tag": "wifi.client.handshake",
             "data": {"ap": "AA:BB:CC:DD:EE:01", "station": "11:22:33:44:55:66",
                      "pmkid": [1, 2, 3, 4]}}
        """.trimIndent()
        val event = EventMapper.toRadioEvent(frame, "/tmp") as RadioEvent.HandshakeDetected
        assertTrue(event.isPmkid)
    }

    @Test
    fun `handshake sem ap vira null no formato string da v241`() {
        assertNull(
            EventMapper.toRadioEvent(
                """{"tag": "wifi.client.handshake", "data": {"station": "AA", "file": "/x.pcap"}}""",
                "/tmp",
            ),
        )
    }
}
