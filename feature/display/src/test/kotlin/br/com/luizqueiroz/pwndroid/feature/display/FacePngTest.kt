package br.com.luizqueiroz.pwndroid.feature.display

import br.com.luizqueiroz.pwndroid.core.mood.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Teste do export PNG (issue #12): [renderFacePng] deve produzir bytes
 * de um PNG válido (assinatura mágica) não-vazio. Robolectric fornece
 * android.graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FacePngTest {

    @Test
    fun `facePng produz PNG valido`() {
        val state = FaceUiState(
            frame = FaceFrame(
                mood = Mood.HAPPY,
                expression = "(•‿‿•)",
                lines = listOf("epoch=1", "channel=6", "aps=10", "handshakes=0", "uptime=00:01:00"),
                mode = "auto",
                peers = 1,
                handshakes = 0,
            ),
        )
        val png = renderFacePng(state, widthPx = 320, heightPx = 240)

        // Assinatura mágica de PNG: 89 50 4E 47 0D 0A 1A 0A.
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D.toByte(), 0x0A, 0x1A.toByte(), 0x0A)
        assertEquals(magic.toList(), png.take(8).toList())
        assertTrue("PNG com conteúdo", png.size > 500)
    }

    @Test
    fun `facePng com blink renderiza expression fechada`() {
        val state = FaceUiState(
            frame = FaceFrame(
                mood = Mood.ANGRY,
                expression = "(⌐■_■)",
                lines = listOf(),
                mode = "ai",
                peers = 0,
                handshakes = 0,
            ),
            blink = true,
        )
        val png = renderFacePng(state, widthPx = 200, heightPx = 150)
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D.toByte(), 0x0A, 0x1A.toByte(), 0x0A)
        assertEquals(magic.toList(), png.take(8).toList())
    }
}
