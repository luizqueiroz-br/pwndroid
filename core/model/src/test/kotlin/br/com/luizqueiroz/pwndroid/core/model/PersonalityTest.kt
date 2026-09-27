package br.com.luizqueiroz.pwndroid.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Defaults devem espelhar pwnagotchi/defaults.toml:
 * recon_time 30, ap_ttl 120, sta_ttl 45, min_rssi -200,
 * max_interactions 3, channels 1..13.
 */
class PersonalityTest {

    @Test
    fun `defaults idênticos ao defaults_toml do pwnagotchi`() {
        val p = Personality()
        assertEquals(30L, p.reconTimeSec)
        assertEquals(120L, p.apTtlSec)
        assertEquals(45L, p.staTtlSec)
        assertEquals(-200, p.minRssi)
        assertEquals(3, p.maxInteractions)
        assertEquals((1..13).toSet(), p.channels)
    }

    @Test
    fun `reconTimeSec não-positivo é rejeitado`() {
        assertThrows(IllegalArgumentException::class.java) {
            Personality(reconTimeSec = 0)
        }
    }

    @Test
    fun `maxInteractions negativo é rejeitado`() {
        assertThrows(IllegalArgumentException::class.java) {
            Personality(maxInteractions = -1)
        }
    }

    @Test
    fun `minRssi positivo é rejeitado`() {
        assertThrows(IllegalArgumentException::class.java) {
            Personality(minRssi = 10)
        }
    }

    @Test
    fun `serialização round-trip preserva valores`() {
        val p = Personality(reconTimeSec = 45, maxInteractions = 5)
        val json = kotlinx.serialization.json.Json.encodeToString(Personality.serializer(), p)
        val back = kotlinx.serialization.json.Json.decodeFromString(Personality.serializer(), json)
        assertEquals(p, back)
    }
}
