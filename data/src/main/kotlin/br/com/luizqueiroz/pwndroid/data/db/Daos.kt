package br.com.luizqueiroz.pwndroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAOs da #15: queries de agregação sustentam a tela Wardrive (lista/mapa)
 * e os exportadores WiGLE/KML.
 */
@Dao
interface AccessPointDao {
    /** Upsert: primeiro sighting insere; seguintes incrementam timesSeen. */
    @Query(
        """
        INSERT INTO access_point (mac, sessionId, ssid, encryption, firstSeenMillis,
            lastSeenMillis, timesSeen)
        VALUES (:mac, :sessionId, :ssid, :encryption, :seenAt, :seenAt, 1)
        ON CONFLICT(mac) DO UPDATE SET
            ssid = COALESCE(:ssid, ssid),
            encryption = COALESCE(:encryption, encryption),
            lastSeenMillis = :seenAt,
            timesSeen = timesSeen + 1
        """,
    )
    suspend fun upsert(
        mac: String,
        sessionId: Long,
        ssid: String?,
        encryption: String?,
        seenAt: Long,
    )

    /** Lista ordenável da tela Wardrive (default: mais recentes primeiro). */
    @Query(
        """
        SELECT mac, ssid, encryption, firstSeenMillis, lastSeenMillis, timesSeen,
            (SELECT MAX(rssi) FROM sighting s WHERE s.apMac = access_point.mac) AS bestRssi,
            (SELECT lat FROM sighting WHERE apMac = access_point.mac AND lat IS NOT NULL
                ORDER BY seenAtMillis DESC LIMIT 1) AS lastLat,
            (SELECT lon FROM sighting WHERE apMac = access_point.mac AND lon IS NOT NULL
                ORDER BY seenAtMillis DESC LIMIT 1) AS lastLon,
            (SELECT COUNT(*) FROM sighting WHERE apMac = access_point.mac) AS sightingCount
        FROM access_point
        ORDER BY lastSeenMillis DESC
        """,
    )
    fun observeAll(): Flow<List<AccessPointWithMeta>>

    @Query("SELECT * FROM access_point WHERE mac = :mac")
    suspend fun byMac(mac: String): AccessPointEntity?

    @Query("SELECT * FROM access_point ORDER BY lastSeenMillis DESC")
    suspend fun all(): List<AccessPointEntity>
}

/** AP enriquecido com agregados da tela Wardrive. */
data class AccessPointWithMeta(
    val mac: String,
    val ssid: String?,
    val encryption: String?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val timesSeen: Int,
    val bestRssi: Int?,
    val lastLat: Double?,
    val lastLon: Double?,
    val sightingCount: Int,
)

@Dao
interface SightingDao {
    @Insert
    suspend fun insert(sightings: List<SightingEntity>)

    /** Série temporal de um AP (detalhe da tela Wardrive + mapa). */
    @Query("SELECT * FROM sighting WHERE apMac = :apMac ORDER BY seenAtMillis ASC")
    suspend fun forAp(apMac: String): List<SightingEntity>

    /** Sightings com posição, para os pins do mapa. */
    @Query("SELECT * FROM sighting WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY seenAtMillis ASC")
    suspend fun withPosition(): List<SightingEntity>

    /** Sightings de uma sessão (export WiGLE por sessão). */
    @Query("SELECT * FROM sighting WHERE sessionId = :sessionId ORDER BY seenAtMillis ASC")
    suspend fun forSession(sessionId: Long): List<SightingEntity>
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query(
        "UPDATE session SET endedAtMillis = :endedAt, apsSeen = :apsSeen, " +
            "stationsSeen = :stations, handshakes = :handshakes WHERE id = :id",
    )
    suspend fun close(id: Long, endedAt: Long, apsSeen: Int, stations: Int, handshakes: Int)

    @Query("SELECT * FROM session ORDER BY startedAtMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<SessionEntity>
}

@Dao
interface EpochDao {
    @Insert
    suspend fun insert(epoch: EpochEntity): Long

    @Query("UPDATE epoch SET durationMs = :durationMs, apsSeen = :apsSeen, " +
        "stationsSeen = :stations, handshakes = :handshakes, blind = :blind WHERE id = :id")
    suspend fun close(
        id: Long,
        durationMs: Long,
        apsSeen: Int,
        stations: Int,
        handshakes: Int,
        blind: Boolean,
    )

    @Query("SELECT * FROM epoch WHERE sessionId = :sessionId ORDER BY number ASC")
    suspend fun forSession(sessionId: Long): List<EpochEntity>
}

@Dao
interface WhitelistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: WhitelistEntryEntity)

    @Query("SELECT mac FROM whitelist")
    suspend fun macs(): List<String>

    @Query("SELECT * FROM whitelist ORDER BY addedAtMillis DESC")
    fun observeAll(): Flow<List<WhitelistEntryEntity>>

    @Query("DELETE FROM whitelist WHERE mac = :mac")
    suspend fun remove(mac: String)
}

@Dao
interface BrainArmDao {
    /** Upsert de arma: cria com α=β=1 ou atualiza contadores. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(arm: BrainArmEntity)

    @Query("SELECT * FROM brain_arm WHERE brainId = :brainId AND param = :param")
    suspend fun armsOf(brainId: String, param: String): List<BrainArmEntity>

    /** Update dos contadores com decay (issue #30). */
    @Query(
        "UPDATE brain_arm SET alpha = :alpha, beta = :beta, " +
            "updatedAtMillis = :now WHERE brainId = :brainId AND param = :param AND value = :value",
    )
    suspend fun updateCounts(
        brainId: String,
        param: String,
        value: String,
        alpha: Double,
        beta: Double,
        now: Long,
    )
}

@Dao
interface HandshakeDao {
    /** Grava o handshake; ignora se o par bssid+essid+type já existe. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(h: HandshakeEntity): Long

    /** Atualiza o registro (path/capturedAt de re-captura do mesmo tipo). */
    @Update
    suspend fun update(h: HandshakeEntity)

    @Query("SELECT * FROM handshake WHERE bssid = :bssid AND essid = :essid AND type = :type LIMIT 1")
    suspend fun byKey(bssid: String, essid: String?, type: String): HandshakeEntity?

    /** Lista da tela Handshakes (mais recentes primeiro). */
    @Query("SELECT * FROM handshake ORDER BY capturedAtMillis DESC")
    fun observeAll(): Flow<List<HandshakeEntity>>

    @Query("SELECT * FROM handshake ORDER BY capturedAtMillis DESC")
    suspend fun all(): List<HandshakeEntity>

    @Query("SELECT * FROM handshake WHERE id = :id")
    suspend fun byId(id: Long): HandshakeEntity?

    @Query("DELETE FROM handshake WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM handshake WHERE sessionId = :sessionId")
    suspend fun countOfSession(sessionId: Long): Int

    /** Marca o upload do plugin wpa-sec (v0.3). */
    @Query("UPDATE handshake SET uploadedWpaSec = :uploaded WHERE id = :id")
    suspend fun setUploadedWpaSec(id: Long, uploaded: Boolean)

    /** Marca o upload do plugin onlinehashcracking (v0.3). */
    @Query("UPDATE handshake SET uploadedOnlineHashCracking = :uploaded WHERE id = :id")
    suspend fun setUploadedOnlineHashCracking(id: Long, uploaded: Boolean)
}

