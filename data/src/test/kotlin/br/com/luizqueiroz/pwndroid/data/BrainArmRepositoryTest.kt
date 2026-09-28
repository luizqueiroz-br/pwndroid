package br.com.luizqueiroz.pwndroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import br.com.luizqueiroz.pwndroid.core.brain.ArmCounts
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistência das armas do Thompson (issue #27): α/β na tabela `brain_arm`
 * sobrevivem ao restart do processo — DB em arquivo real, fechado e
 * reaberto (simula o novo processo lendo o mesmo banco).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainArmRepositoryTest {

    private lateinit var dbFile: File
    private lateinit var db: PwnDatabase
    private lateinit var repo: BrainArmRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("brain_arm_test.db")
        dbFile.parentFile?.mkdirs()
        db = Room.databaseBuilder(context, PwnDatabase::class.java, "brain_arm_test.db").build()
        repo = BrainArmRepository(db.brainArmDao())
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `α e β sobrevivem ao restart do processo`() = runTest {
        // Processo 1: arma criada e treinada.
        repo.upsert("thompson", "recon_time", "35", 1.0, 1.0)
        repo.updateCounts("thompson", "recon_time", "35", 4.0, 1.5)

        // Restart: DB fechado e reaberto, repositório novo.
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(context, PwnDatabase::class.java, "brain_arm_test.db").build()
        val repo2 = BrainArmRepository(db.brainArmDao())

        val arms = repo2.armsOf("thompson", "recon_time")
        assertEquals(1, arms.size)
        assertEquals(ArmCounts("35", 4.0, 1.5), arms.single())
    }

    @Test
    fun `upsert de valor novo cria linha com prior dado`() = runTest {
        repo.upsert("thompson", "min_rssi", "-85", 1.0, 1.0)
        val arms = repo.armsOf("thompson", "min_rssi")
        assertEquals(listOf(ArmCounts("-85", 1.0, 1.0)), arms)
        // Params diferentes não se misturam.
        assertTrue(repo.armsOf("thompson", "recon_time").isEmpty())
    }

    @Test
    fun `updateCounts só mexe na arma alvo`() = runTest {
        repo.upsert("thompson", "recon_time", "30", 1.0, 1.0)
        repo.upsert("thompson", "recon_time", "35", 1.0, 1.0)
        repo.updateCounts("thompson", "recon_time", "35", 2.0, 1.0)

        val porValor = repo.armsOf("thompson", "recon_time").associateBy { it.value }
        assertEquals(ArmCounts("30", 1.0, 1.0), porValor.getValue("30"))
        assertEquals(ArmCounts("35", 2.0, 1.0), porValor.getValue("35"))
    }
}
