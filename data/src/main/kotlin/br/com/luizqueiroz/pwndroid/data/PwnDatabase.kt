package br.com.luizqueiroz.pwndroid.data

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import android.content.Context
import androidx.room.Room

/**
 * Entidade de sessão de captura (uma "época" ou janela de wardrive).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val handshakes: Int = 0,
    val apsSeen: Int = 0,
)

/**
 * Entidade de AP visto (dedup por BSSID por sessão).
 */
@Entity(tableName = "sightings")
data class SightingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val bssid: String,
    val essid: String?,
    val channel: Int,
    val rssi: Int,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMillis = :endedAt, handshakes = :handshakes, apsSeen = :apsSeen WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long, handshakes: Int, apsSeen: Int)

    @Query("SELECT * FROM sessions ORDER BY startedAtMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<SessionEntity>
}

@Dao
interface SightingDao {
    @Insert
    suspend fun insert(sighting: SightingEntity): Long

    @Query("SELECT COUNT(*) > 0 FROM sightings WHERE sessionId = :sessionId AND bssid = :bssid")
    suspend fun exists(sessionId: Long, bssid: String): Boolean
}

@Database(entities = [SessionEntity::class, SightingEntity::class], version = 1, exportSchema = false)
abstract class PwnDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sightingDao(): SightingDao

    companion object {
        fun build(context: Context): PwnDatabase =
            Room.databaseBuilder(context, PwnDatabase::class.java, "pwndroid.db").build()
    }
}
