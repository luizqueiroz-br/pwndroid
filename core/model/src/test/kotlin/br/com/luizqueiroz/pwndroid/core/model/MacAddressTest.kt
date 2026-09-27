package br.com.luizqueiroz.pwndroid.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MacAddressTest {

    @Test
    fun `parse aceita formatos com dois-pontos, hífen e hex puro`() {
        val a = MacAddress.parse("AA:BB:CC:DD:EE:FF")
        val b = MacAddress.parse("aa-bb-cc-dd-ee-ff")
        val c = MacAddress.parse("aabbccddeeff")
        assertEquals(a, b)
        assertEquals(b, c)
    }

    @Test
    fun `format devolve lowercase com dois-pontos`() {
        assertEquals("aa:bb:cc:dd:ee:ff", MacAddress.parse("AA-BB-CC-DD-EE-FF").format())
        assertEquals("aa:bb:cc:dd:ee:ff", MacAddress.parse("aabbccddeeff").toString())
    }

    @Test
    fun `equals é case-insensitive`() {
        assertEquals(MacAddress.parse("AA:BB:CC:DD:EE:FF"), MacAddress.parse("aa:bb:cc:dd:ee:ff"))
        assertEquals(MacAddress.parse("AA:BB:CC:DD:EE:FF").hashCode(), MacAddress.parse("aa:bb:cc:dd:ee:ff").hashCode())
    }

    @Test
    fun `parse rejeita mac inválido`() {
        assertThrows(IllegalArgumentException::class.java) { MacAddress.parse("nope") }
        assertThrows(IllegalArgumentException::class.java) { MacAddress.parse("AA:BB:CC:DD:EE") }
        assertThrows(IllegalArgumentException::class.java) { MacAddress.parse("zz:bb:cc:dd:ee:ff") }
    }

    @Test
    fun `macs diferentes não são iguais`() {
        assertNotEquals(MacAddress.parse("AA:BB:CC:DD:EE:FF"), MacAddress.parse("AA:BB:CC:DD:EE:00"))
    }
}
