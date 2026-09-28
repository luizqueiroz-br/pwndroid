package br.com.luizqueiroz.pwndroid.core.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do [PcapWriter] e [HandshakeSlicer] contra o golden
 * `wpa-Induction.pcap` (Wireshark sample — captura real de um 4-way
 * handshake WPA1, AP 00:0C:41:82:B2:55 "Coherer", linktype 127
 * radiotap): parse e slicing contra um PCAP de verdade.
 */
class HandshakeSlicerTest {

    private fun golden(): File =
        File("src/test/resources/fixtures/wpa-Induction.pcap")

    private fun parsedGolden() = PcapReader.read(golden())
        .mapNotNull { FrameParser.parse(it.data, it.timestampMicros) }

    @Test
    fun `golden pcap parseia com beacon e 4 frames eapol`() {
        val parsed = parsedGolden()
        assertTrue(parsed.size > 4) // golden tem 1093 frames no total

        val apMac = "00:0C:41:82:B2:55"
        val beacons = parsed.filterIsInstance<ParsedFrame.Beacon>()
            .filter { it.bssid == apMac }
        assertTrue(beacons.isNotEmpty())
        assertEquals("Coherer", beacons.first().essid)

        val eapols = parsed.filterIsInstance<ParsedFrame.Eapol>()
        assertEquals(4, eapols.size)
        assertTrue(eapols.all { it.bssid == apMac })
        assertTrue(eapols.all { it.station == "00:0D:93:82:36:3A" })
    }

    @Test
    fun `eapol classify identifica as 4 mensagens do golden`() {
        val eapols = parsedGolden().filterIsInstance<ParsedFrame.Eapol>()

        val msgs = eapols.map { EapolParser.classify(it.eapol) }
        // Ordem de captura real: M1, M2, M3, M4.
        assertEquals(
            listOf(
                EapolParser.Message.M1,
                EapolParser.Message.M2,
                EapolParser.Message.M3,
                EapolParser.Message.M4,
            ),
            msgs,
        )
        // Sanity: keyInfo dos frames batem com o esperado do golden.
        val keyInfoOf = { eapol: ByteArray ->
            ((eapol[5].toInt() and 0xff) shl 8) or (eapol[6].toInt() and 0xff)
        }
        assertEquals(0x008a, keyInfoOf(eapols[0].eapol))
        assertEquals(0x010a, keyInfoOf(eapols[1].eapol))
        assertEquals(0x13ca, keyInfoOf(eapols[2].eapol))
        assertEquals(0x030a, keyInfoOf(eapols[3].eapol))
    }

    @Test
    fun `slice do golden produz pcap com beacon e eapols e tipo FULL`() {
        val outDir = createTempDir("sliced")
        val result = HandshakeSlicer(outDir).slice(golden(), "00:0C:41:82:B2:55")

        assertNotNull(result)
        result!!
        assertEquals("Coherer", result.essid)
        assertEquals("00:0C:41:82:B2:55", result.bssid)
        val best = result.best()
        assertNotNull(best)
        assertEquals(HandshakeType.FULL, best!!.type)
        assertEquals("00:0D:93:82:36:3A", best.station)

        // O sub-pcap existe, é um PCAP válido e contém só frames do alvo.
        val sliced = File(result.pcapPath)
        assertTrue(sliced.exists())
        val outParsed = PcapReader.read(sliced)
            .mapNotNull { FrameParser.parse(it.data, it.timestampMicros) }
        val outBeacons = outParsed.filterIsInstance<ParsedFrame.Beacon>()
        assertTrue(outBeacons.isNotEmpty())
        assertEquals("Coherer", outBeacons.first().essid)
        assertEquals(4, outParsed.filterIsInstance<ParsedFrame.Eapol>().size)
        // Nome no padrão ESSID_BSSID.pcap.
        assertEquals("Coherer_000C4182B255.pcap", sliced.name)

        // Metadata .pcap.eye ao lado do pcap.
        val eye = File(result.pcapPath + ".eye")
        assertTrue(eye.exists())
        val eyeJson = eye.readText()
        assertTrue(eyeJson.contains("\"essid\": \"Coherer\""))
        assertTrue(eyeJson.contains("\"bssid\": \"00:0C:41:82:B2:55\""))
        assertTrue(eyeJson.contains("\"type\": \"handshake\""))

        outDir.deleteRecursively()
    }

    @Test
    fun `slice com bssid alvo ausente retorna null`() {
        val outDir = createTempDir("sliced")
        val slicer = HandshakeSlicer(outDir)
        val result = slicer.slice(golden(), "FF:FF:FF:FF:FF:FF")
        assertNull(result)
        outDir.deleteRecursively()
    }

    @Test
    fun `bssid sem eapol nao gera arquivo`() {
        val outDir = createTempDir("sliced")
        val slicer = HandshakeSlicer(outDir)
        // A STA 00:0D:93:82:36:3A aparece como addr1 do M2/M4 (destino),
        // mas nunca como BSSID — não é um alvo válido.
        val result = slicer.slice(golden(), "00:0D:93:82:36:3A")
        assertNull(result)
        outDir.deleteRecursively()
    }

    @Test
    fun `slice com essidHint sobrescreve beacon`() {
        val outDir = createTempDir("sliced")
        val result = HandshakeSlicer(outDir).slice(golden(), "00:0C:41:82:B2:55", "My-Net")
        assertNotNull(result)
        assertEquals("My-Net", result!!.essid)
        assertEquals("My-Net_000C4182B255.pcap", File(result.pcapPath).name)
        outDir.deleteRecursively()
    }

    @Test
    fun `nomes de arquivo com essid especial e oculto`() {
        // ESSID oculto (null) → fallback <BSSID>.pcap.
        assertEquals(
            "000C4182B255_000C4182B255.pcap",
            PcapWriter.fileNameFor(null, "00:0C:41:82:B2:55"),
        )
        // ESSID vazio → mesmo fallback.
        assertEquals(
            "000C4182B255_000C4182B255.pcap",
            PcapWriter.fileNameFor("", "00:0C:41:82:B2:55"),
        )
        // Caracteres especiais viram underscore (não são removidos).
        assertEquals(
            "Minha_Rede_000C4182B255.pcap",
            PcapWriter.fileNameFor("Minha/Rede", "00:0C:41:82:B2:55"),
        )
        // ESSID que, sanitizado, é 12 hex legítimo: mantido (não é
        // indistinguível de ESSID oculto — o fallback é só para null/blank).
        assertEquals(
            "ab12cd34ef56_000C4182B255.pcap",
            PcapWriter.fileNameFor("ab12cd34ef56", "00:0C:41:82:B2:55"),
        )
    }

    @Test
    fun `pcapwriter grava header e records validos`() {
        val dir = createTempDir("pcap")
        val writer = PcapWriter(dir)
        val pcap = writer.openFor("Teste", "AA:BB:CC:DD:EE:FF")
        val fakeFrame = ByteArray(50) { (it % 256).toByte() }
        pcap.writePacket(fakeFrame, 1_000_000L)
        pcap.writePacket(fakeFrame, 2_500_000L)
        pcap.close()

        val file = File(dir, "Teste_AABBCCDDEEFF.pcap")
        assertTrue(file.exists())
        // Global header: magic LE + v2.4 + snaplen + linktype 105.
        val bytes = file.readBytes()
        assertEquals(0xd4.toByte(), bytes[0])
        assertEquals(0xa1.toByte(), bytes[3])
        assertEquals(105, bytes[20].toInt() and 0xff)
        // 24 (global header) + 2 * (16 record header + 50 data).
        assertEquals(24 + 2 * 16 + 2 * 50, file.length())

        val records = PcapReader.read(file)
        assertEquals(2, records.size)
        assertEquals(1_000_000L, records[0].timestampMicros)
        assertEquals(2_500_000L, records[1].timestampMicros)
        // packetCount conta records corretamente (tamanhos variáveis).
        val pcap2 = writer.openFor("Teste", "AA:BB:CC:DD:EE:FF")
        pcap2.writePacket(ByteArray(10), 3_000_000L)
        pcap2.close()
        assertEquals(3, pcap2.packetCount())

        dir.deleteRecursively()
    }

    @Test
    fun `pmkid kde do m1 e extraido quando presente`() {
        val eapols = parsedGolden().filterIsInstance<ParsedFrame.Eapol>()
        val m1 = eapols[0]
        // O golden é WPA1 (sem PMKID no M1 — PMKID é WPA2-PSK RSNA).
        assertNull(EapolParser.extractPmkid(m1.eapol))

        // M1 sintético com PMKID KDE: header 95 bytes + DD 14 00 0F AC 04 + 16 bytes.
        val pmkid = ByteArray(16) { (0x10 + it).toByte() }
        val kde = byteArrayOf(
            0xdd.toByte(), 0x14.toByte(),
            0x00.toByte(), 0x0f.toByte(), 0xac.toByte(), 0x04.toByte(),
        ) + pmkid
        val m1WithPmkid = ByteArray(95) + kde
        assertNotNull(EapolParser.extractPmkid(m1WithPmkid))
        assertTrue(EapolParser.extractPmkid(m1WithPmkid)!!.contentEquals(pmkid))

        // KDE de outro tipo (00-0F-AC:00 = AKM) não é PMKID.
        val otherKde = byteArrayOf(
            0xdd.toByte(), 0x14.toByte(), 0x00.toByte(), 0x0f.toByte(), 0xac.toByte(), 0x02.toByte(),
        ) + pmkid
        assertNull(EapolParser.extractPmkid(ByteArray(95) + otherKde))
    }

    @Test
    fun `eapol non key ou truncado vira null`() {
        // Frame type não-EAPOL-Key (0x00 = EAP, 0x01 = EAPOL-Start...).
        val notKey = byteArrayOf(1, 0, 0, 0, 0, 0, 0)
        assertNull(EapolParser.classify(notKey))
        // Truncado.
        assertNull(EapolParser.classify(byteArrayOf(1, 3)))
    }
}
