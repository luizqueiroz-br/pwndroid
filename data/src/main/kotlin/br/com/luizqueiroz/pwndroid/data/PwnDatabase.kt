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
    ],
    version = 1,
    exportSchema = true,
)
abstract class PwnDatabase : RoomDatabase() {
    abstract fun accessPointDao(): AccessPointDao
    abstract fun sightingDao(): SightingDao
    abstract fun sessionDao(): SessionDao
    abstract fun epochDao(): EpochDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun brainArmDao(): BrainArmDao

    companion object {
        fun build(context: Context): PwnDatabase =
            Room.databaseBuilder(context, PwnDatabase::class.java, "pwndroid.db").build()
    }
}
