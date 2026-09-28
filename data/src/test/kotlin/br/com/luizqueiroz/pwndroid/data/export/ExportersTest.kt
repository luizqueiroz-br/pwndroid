package br.com.luizqueiroz.pwndroid.data.export

import br.com.luizqueiroz.pwndroid.data.export.SightingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exportadores da #15 validados contra o formato oficial: WiGLE CSV
 * (WigleWifi-1.4) e KML 2.2.
 */
class ExportersTest {

    private val rows = listOf(
        SightingRow(
            mac = "aa:bb:cc:00:00:01",
            ssid = "café, central",
            encryption = "WPA2 PSK",
            channel = 6,
            rssi = -55,
            lat = -23.5505,
            lon = -46.6333,
            seenAtMillis = 1_700_000_000_000,
        ),
        SightingRow(
            mac = "aa:bb:cc:00:00:02",
            ssid = null,
            encryption = null,
            channel = 11,
            rssi = -70,
            lat = null, // sem GPS: CSV inclui com 0,0; KML exclui
            lon = null,
            seenAtMillis = 1_700_000_060_000,
        ),
    )

    @Test
    fun `csv wigle tem header oficial e linhas wifi`() {
        val csv = WigleCsvExporter.export(rows)

        // Header oficial (2 linhas): assinatura + colunas.
        val lines = csv.trim().lines()
        assertTrue(lines[0].startsWith("WigleWifi-1.4,appRelease="))
        assertEquals(
            "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude," +
                "CurrentLongitude,AltitudeMeters,AccuracyMeters,Type",
            lines[1],
        )
        // Linhas ordenadas por MAC.
        assertTrue(lines.size == 4)
        assertTrue(lines[2].startsWith("aa:bb:cc:00:00:01,"))
        assertTrue(lines[3].startsWith("aa:bb:cc:00:00:02,"))
        // Type WIFI no fim da linha.
        assertTrue(lines[2].endsWith(",WIFI"))
    }

    @Test
    fun `csv escapa ssid com vírgula e aspas`() {
        val csv = WigleCsvExporter.export(rows)
        assertTrue(csv.contains("\"café, central\""))
    }

    @Test
    fun `csv sem encryption vira NONE`() {
        val csv = WigleCsvExporter.export(rows)
        assertTrue(csv.contains("aa:bb:cc:00:00:02,,NONE,"))
    }

    @Test
    fun `csv timestamp em iso8601`() {
        val csv = WigleCsvExporter.export(rows)
        assertTrue(csv.contains("2023-11-14T22:13:20Z"))
    }

    @Test
    fun `kml tem placemarks só com posição`() {
        val kml = KmlExporter.export(rows)

        assertTrue(kml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))
        assertEquals(1, Regex("<Placemark>").findAll(kml).count()) // sem GPS fora
        assertTrue(kml.contains("<name>café, central</name>"))
        assertTrue(kml.contains("<coordinates>-46.6333,-23.5505,0</coordinates>"))
    }

    @Test
    fun `kml escapa xml`() {
        val kml = KmlExporter.export(
            listOf(
                SightingRow(
                    mac = "aa:bb:cc:00:00:03",
                    ssid = "a<b>&\"c\"",
                    encryption = "WPA2",
                    channel = 1,
                    rssi = -60,
                    lat = 1.0,
                    lon = 2.0,
                    seenAtMillis = 0,
                ),
            ),
        )
        assertTrue(kml.contains("a&lt;b&gt;&amp;&quot;c&quot;"))
    }
}
