package br.com.luizqueiroz.pwndroid.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Room
import android.content.Context
import br.com.luizqueiroz.pwndroid.data.db.AccessPointDao
import br.com.luizqueiroz.pwndroid.data.db.AccessPointEntity
import br.com.luizqueiroz.pwndroid.data.db.BrainArmDao
import br.com.luizqueiroz.pwndroid.data.db.BrainArmEntity
import br.com.luizqueiroz.pwndroid.data.db.EpochDao
import br.com.luizqueiroz.pwndroid.data.db.EpochEntity
import br.com.luizqueiroz.pwndroid.data.db.HandshakeDao
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import br.com.luizqueiroz.pwndroid.data.db.SightingDao
import br.com.luizqueiroz.pwndroid.data.db.SightingEntity
import br.com.luizqueiroz.pwndroid.data.db.SessionDao
import br.com.luizqueiroz.pwndroid.data.db.SessionEntity
import br.com.luizqueiroz.pwndroid.data.db.WhitelistDao
import br.com.luizqueiroz.pwndroid.data.db.WhitelistEntryEntity

/**
 * Banco principal (schema v1 da #15). `exportSchema = true`: o JSON do
 * schema é publicado em `data/schemas` para migrations planejadas e
 * testes de migration (MigrationTestHelper).
 */
@Database(
    entities = [
        AccessPointEntity::class,
        SightingEntity::class,
        SessionEntity::class,
        EpochEntity::class,
        BrainArmEntity::class,
        WhitelistEntryEntity::class,
        HandshakeEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PwnDatabase : RoomDatabase() {
    abstract fun accessPointDao(): AccessPointDao
    abstract fun sightingDao(): SightingDao
    abstract fun sessionDao(): SessionDao
    abstract fun epochDao(): EpochDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun brainArmDao(): BrainArmDao
    abstract fun handshakeDao(): HandshakeDao

    companion object {
        /**
         * v2 (issue #24): tabela `handshake` com índice único
         * bssid+essid+type para o dedup do repositório.
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `handshake` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bssid` TEXT NOT NULL, `essid` TEXT, `type` TEXT NOT NULL, " +
                        "`pcapPath` TEXT NOT NULL, `station` TEXT NOT NULL, " +
                        "`capturedAtMillis` INTEGER NOT NULL, `sessionId` INTEGER, " +
                        "`lat` REAL, `lon` REAL, `uploadedWpaSec` INTEGER NOT NULL, " +
                        "`uploadedOnlineHashCracking` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_handshake_bssid_essid_type` ON `handshake` (bssid, essid, type)",
                )
            }
        }

        fun build(context: Context): PwnDatabase =
            Room.databaseBuilder(context, PwnDatabase::class.java, "pwndroid.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
