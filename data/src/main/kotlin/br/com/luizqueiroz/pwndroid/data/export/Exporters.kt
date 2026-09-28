package br.com.luizqueiroz.pwndroid.data.export

import br.com.luizqueiroz.pwndroid.data.db.SightingEntity

/**
 * Exportadores da #15: CSV no formato WiGLE (WigleWifi-1.4, aberto pelo
 * app WiGLE Wi-Fi e aceito no upload deles) e KML (Google Earth/maps).
 * Puros — escrevem em [Appendable], testáveis sem device.
 */

/**
 * CSV WiGLE `WigleWifi-1.4`: header fixo de 12 colunas + 1 coluna por
 * campo, linhas ordenadas por MAC+hora (requisito do formato deles).
 */
object WigleCsvExporter {

    /** Header oficial do formato WiGLE Wi-Fi 1.4. */
    const val HEADER =
        "WigleWifi-1.4,appRelease=pwndroid,model=Android,release=1.0,device=Android," +
            "display=Android,channel=Android,mac=Android\n" +
            "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
            "AltitudeMeters,AccuracyMeters,Type\n"

    /**
     * Exporta sightings de wardriving em CSV WiGLE. Linhas por sighting
     * (não por AP): o WiGLE dedup por MAC+posição+hora.
     */
    fun export(sightings: List<SightingRow>): String = buildString {
        append(HEADER)
        sightings
            .sortedWith(compareBy({ it.mac }, { it.seenAtMillis }))
            .forEach { row ->
                val auth = row.encryption?.uppercase()?.replace(",", "+") ?: "NONE"
                val lat = row.lat ?: 0.0
                val lon = row.lon ?: 0.0
                append(
                    "${row.mac},${csv(row.ssid)},$auth,${iso8601(row.seenAtMillis)}," +
                        "${row.channel},${row.rssi},$lat,$lon,0.0,0.0,WIFI\n",
                )
            }
    }
}

private fun csv(value: String?): String =
    if (value != null && (value.contains(',') || value.contains('"'))) {
        '"' + value.replace("\"", "\"\"") + '"'
    } else {
        value.orEmpty()
    }

private fun iso8601(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).toString().replace(".000Z", "Z")

/** Linha de sighting para export (view da UI/repositório, não a entidade). */
data class SightingRow(
    val mac: String,
    val ssid: String?,
    val encryption: String?,
    val channel: Int,
    val rssi: Int,
    val lat: Double?,
    val lon: Double?,
    val seenAtMillis: Long,
)

/**
 * KML 2.2: um `<Placemark>` por sighting com posição, nome do SSID e
 * descrição com metadados. Abrível no Google Earth/Maps.
 */
object KmlExporter {

    private fun esc(value: String?): String =
        value.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun export(sightings: List<SightingRow>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sightings.filter { it.lat != null && it.lon != null }.forEach { row ->
            append("<Placemark>\n")
            append("<name>${esc(row.ssid ?: "(hidden)")}</name>\n")
            append(
                "<description>MAC ${esc(row.mac)} · ch ${row.channel} · " +
                    "${row.rssi} dBm · ${esc(row.encryption ?: "open")}</description>\n",
            )
            append("<Point><coordinates>${row.lon},${row.lat},0</coordinates></Point>\n")
            append("</Placemark>\n")
        }
        append("</Document>\n</kml>\n")
    }
}
