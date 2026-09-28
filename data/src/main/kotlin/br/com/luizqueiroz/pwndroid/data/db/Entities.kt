package br.com.luizqueiroz.pwndroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema Room da #15: o "diário de campo" do pwndroid — APs, sightings
 * geolocalizados, épocas, sessões, whitelist ética e armas do Thompson.
 */

/**
 * AP visto, dedup global por BSSID dentro da sessão: `lastSeen`/`timesSeen`
 * crescem a cada leitura na mesma sessão.
 */
@Entity(tableName = "access_point")
data class AccessPointEntity(
    /** BSSID (MAC do AP), chave natural. */
    @PrimaryKey val mac: String,
    val sessionId: Long,
    val ssid: String?,
    val encryption: String?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val timesSeen: Int = 1,
)

/**
 * Um avistamento geolocalizado de um AP (uma leitura de RSSI + posição).
 * Índice por (apMac, seenAt) para série temporal por AP.
 */
@Entity(
    tableName = "sighting",
    indices = [Index("apMac"), Index("seenAtMillis")],
    foreignKeys = [ForeignKey(
        entity = AccessPointEntity::class,
        parentColumns = ["mac"],
        childColumns = ["apMac"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SightingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val apMac: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val rssi: Int,
    val channel: Int,
    val seenAtMillis: Long,
)

/**
 * Sessão de wardriving/captura (o que a tela Wardrive lista e exporta).
 */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val backendId: String,
    val mode: String,
    /** Totais fechados no fim da sessão. */
    val apsSeen: Int = 0,
    val stationsSeen: Int = 0,
    val handshakes: Int = 0,
)

/**
 * Uma época executada. `personalityJson` preserva a personalidade que
 * dirigiu a época (para o Brain futuro e análise offline).
 */
@Entity(
    tableName = "epoch",
    indices = [Index("sessionId")],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class EpochEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val number: Long,
    val startedAtMillis: Long,
    val durationMs: Long? = null,
    val apsSeen: Int = 0,
    val stationsSeen: Int = 0,
    val handshakes: Int = 0,
    val blind: Boolean = false,
    val personalityJson: String? = null,
)

/**
 * Arma do Thompson Sampling (issue #30): beta-binomial por (cérebro, param,
 * value). `alpha`/`beta` são os contadores de sucesso/falha.
 */
@Entity(
    tableName = "brain_arm",
    indices = [Index(value = ["brainId", "param", "value"], unique = true)],
)
data class BrainArmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brainId: String,
    val param: String,
    val value: String,
    val alpha: Double = 1.0,
    val beta: Double = 1.0,
    val updatedAtMillis: Long = 0,
)

/**
 * AP excluído por ética (não atacar a própria casa) — respeitado pelos
 * filtros do orquestrador.
 */
@Entity(tableName = "whitelist")
data class WhitelistEntryEntity(
    @PrimaryKey val mac: String,
    val addedAtMillis: Long,
    val note: String? = null,
)
