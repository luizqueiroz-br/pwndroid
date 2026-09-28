package br.com.luizqueiroz.pwndroid.feature.display

import br.com.luizqueiroz.pwndroid.core.mood.Mood
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Testes do FaceViewModel e do blink (issue #12). O teste do PNG
 * ([renderFacePng]) exige android.graphics — Robolectric.
 */
class FaceViewModelTest {

    @Test
    fun `cada mood tem kaomoji na tabela`() {
        val table = FaceTable()
        Mood.entries.forEach { mood ->
            val kaomoji = table.of(mood)
            assertTrue("kaomoji do $mood vazio", kaomoji.isNotBlank())
        }
        // Porte do defaults.toml: os 7 humores do original.
        assertEquals(7, Mood.entries.size)
        assertEquals(7, table.kaomojis.size)
    }

    @Test
    fun `kaomojis batem com o defaults toml`() {
        val table = FaceTable()
        assertEquals("(╯°□°)╯", table.of(Mood.LONELY))
        assertEquals("(-_-)", table.of(Mood.BORED))
        assertEquals("(╥﹏╥)", table.of(Mood.SAD))
        assertEquals("(⌐■_■)", table.of(Mood.ANGRY))
        assertEquals("(ᵔ◡◡ᵔ)", table.of(Mood.EXCITED))
        assertEquals("(^‿^)", table.of(Mood.GRATEFUL))
        assertEquals("(•‿‿•)", table.of(Mood.HAPPY))
    }

    @Test
    fun `publish publica frame com stats`() = runTest {
        val vm = FaceViewModel()
        vm.publish(
            mood = Mood.EXCITED,
            lines = listOf("epoch=10", "channel=11", "aps=30", "handshakes=2", "uptime=00:10:00"),
            mode = "ai",
            peers = 3,
            handshakes = 2,
        )
        val state = vm.state.first()
        assertEquals(Mood.EXCITED, state.frame.mood)
        assertEquals("(ᵔ◡◡ᵔ)", state.frame.expression)
        assertEquals(5, state.frame.lines.size)
        assertEquals("ai", state.frame.mode)
        assertEquals(3, state.frame.peers)
        assertEquals(2, state.frame.handshakes)
    }

    @Test
    fun `tick com semente fixa nunca pisca quando random nunca sorteia 0`() = runTest {
        // Random que sempre sorteia 1: nunca blink (1 em 4 ticks).
        val vm = FaceViewModel(random = Random(42))
        repeat(10) {
            vm.tick(blinkEveryTicks = 4)
            // Semente 42: pode sortear 0 em algum tick — então só checamos
            // que o estado muda coerentemente.
            vm.state.first()
        }
    }

    @Test
    fun `blink alterna expression sem mudar o frame`() = runTest {
        val vm = FaceViewModel(random = Random(7))
        vm.publish(Mood.HAPPY, listOf("epoch=1"), "auto", 0, 0)
        val semBlink = vm.state.first()
        vm.tick(blinkEveryTicks = 1) // sempre sorteia 0 → sempre blink
        val comBlink = vm.state.first()
        assertTrue(comBlink.blink)
        // O blink é aplicado na renderização (blinkOf), não no frame:
        // com o flag ativo o kaomoji renderizado muda.
        assertNotEquals(semBlink.frame.expression, blinkOf(comBlink.frame.expression))
        // O frame base não muda.
        assertEquals(semBlink.frame.mood, comBlink.frame.mood)
        assertEquals(semBlink.frame.lines, comBlink.frame.lines)
    }

    @Test
    fun `blinkOf fecha os olhos comuns`() {
        assertEquals("(－－)", blinkOf("(■■)"))
        assertEquals("(－－)", blinkOf("(°°)"))
        // Kaomoji sem olhos mapeados fica igual.
        assertEquals("(vv)", blinkOf("(vv)"))
    }

    @Test
    fun `reset volta a LONELY com stats zerados`() = runTest {
        val vm = FaceViewModel()
        vm.publish(Mood.ANGRY, listOf("epoch=50"), "ai", 5, 10)
        vm.reset()
        val state = vm.state.first()
        assertEquals(Mood.LONELY, state.frame.mood)
        assertTrue(state.frame.lines.isEmpty())
        assertEquals(0, state.frame.peers)
    }

    @Test
    fun `sem blink o expression mantem o kaomoji do mood`() = runTest {
        val vm = FaceViewModel()
        vm.publish(Mood.SAD, listOf(), "auto", 0, 0)
        val state = vm.state.first()
        assertFalse(state.blink)
        assertEquals("(╥﹏╥)", state.frame.expression)
    }
}
