package br.com.luizqueiroz.pwndroid.data

import br.com.luizqueiroz.pwndroid.data.db.AccessPointDao
import br.com.luizqueiroz.pwndroid.data.db.AccessPointEntity
import br.com.luizqueiroz.pwndroid.data.db.AccessPointWithMeta
import br.com.luizqueiroz.pwndroid.data.db.EpochDao
import br.com.luizqueiroz.pwndroid.data.db.EpochEntity
import br.com.luizqueiroz.pwndroid.data.db.SightingDao
import br.com.luizqueiroz.pwndroid.data.db.SightingEntity
import br.com.luizqueiroz.pwndroid.data.db.SessionDao
import br.com.luizqueiroz.pwndroid.data.db.SessionEntity
import br.com.luizqueiroz.pwndroid.data.db.WhitelistDao
import br.com.luizqueiroz.pwndroid.data.db.WhitelistEntryEntity
import br.com.luizqueiroz.pwndroid.data.export.SightingRow
import br.com.luizqueiroz.pwndroid.data.export.WigleCsvExporter
import br.com.luizqueiroz.pwndroid.data.export.KmlExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Repositório de wardriving (issue #18): face de leitura para a tela
 * Wardrive e face de gravação para a sessão. Única porta de entrada da
 * UI para o banco da #15.
 */
class WardriveRepository(private val db: PwnDatabase) {

    /** APs vistos com meta (RSSI máximo, última posição, contagem). */
    fun observeAccessPoints() = db.accessPointDao().observeAll()

    /** Detalhe de um AP: entidade + série temporal de sightings. */
    suspend fun accessPointDetail(mac: String): AccessPointDetail? {
        val ap = db.accessPointDao().byMac(mac) ?: return null
        return AccessPointDetail(ap, db.sightingDao().forAp(mac))
    }

    data class AccessPointDetail(
        val ap: AccessPointEntity,
        val sightings: List<SightingEntity>,
    )

    /** Leitura de um AP vindo do rádio (agrupa campos do sighting). */
    data class SightingRecord(
        val sessionId: Long,
        val mac: String,
        val ssid: String?,
        val encryption: String?,
        val rssi: Int,
        val channel: Int,
        val lat: Double?,
        val lon: Double?,
        val seenAtMillis: Long,
    )

    /** Grava sighting geolocalizado e faz upsert do AP (dedup por BSSID). */
    suspend fun recordSighting(rec: SightingRecord) {
        // Upsert do AP primeiro: a FK do sighting aponta para access_point.
        db.accessPointDao().upsert(rec.mac, rec.sessionId, rec.ssid, rec.encryption, rec.seenAtMillis)
        db.sightingDao().insert(
            listOf(
                SightingEntity(
                    sessionId = rec.sessionId,
                    apMac = rec.mac,
                    lat = rec.lat,
                    lon = rec.lon,
                    rssi = rec.rssi,
                    channel = rec.channel,
                    seenAtMillis = rec.seenAtMillis,
                ),
            ),
        )
    }

    /** Inicia uma sessão de wardriving (linha `session`). */
    suspend fun startSession(backendId: String, mode: String, startedAt: Long): Long =
        db.sessionDao().insert(
            SessionEntity(startedAtMillis = startedAt, backendId = backendId, mode = mode),
        )

    /** Fecha a sessão com totais. */
    suspend fun closeSession(id: Long, endedAt: Long, apsSeen: Int, stations: Int, handshakes: Int) =
        db.sessionDao().close(id, endedAt, apsSeen, stations, handshakes)

    /** Export de uma sessão: (CSV WiGLE, KML) dos sightings. */
    suspend fun exportSessionCsvKml(sessionId: Long, ssidOf: (String) -> String? = { null }): Pair<String, String> {
        val rows = db.sightingDao().forSession(sessionId).map { s ->
            SightingRow(
                mac = s.apMac,
                ssid = ssidOf(s.apMac),
                encryption = null,
                channel = s.channel,
                rssi = s.rssi,
                lat = s.lat,
                lon = s.lon,
                seenAtMillis = s.seenAtMillis,
            )
        }
        return WigleCsvExporter.export(rows) to KmlExporter.export(rows)
    }

    /** Export de todos os sightings com posição (histórico completo). */
    suspend fun exportAllCsvKml(): Pair<String, String> {
        val aps = db.accessPointDao().all().associateBy { it.mac }
        val rows = db.sightingDao().withPosition().map { s ->
            val ap = aps[s.apMac]
            SightingRow(
                mac = s.apMac,
                ssid = ap?.ssid,
                encryption = ap?.encryption,
                channel = s.channel,
                rssi = s.rssi,
                lat = s.lat,
                lon = s.lon,
                seenAtMillis = s.seenAtMillis,
            )
        }
        return WigleCsvExporter.export(rows) to KmlExporter.export(rows)
    }
}
