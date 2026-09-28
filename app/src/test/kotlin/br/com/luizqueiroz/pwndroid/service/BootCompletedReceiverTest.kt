package br.com.luizqueiroz.pwndroid.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import br.com.luizqueiroz.pwndroid.AppGraph
import br.com.luizqueiroz.pwndroid.PwnApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * BootCompletedReceiver (issue #17): retoma a sessão após reboot apenas
 * com `auto_start` ativo na config.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PwnApplication::class)
class BootCompletedReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowPowerManager.clearWakeLocks()
    }

    @org.junit.Before
    fun setUp() {
        // Estáticos (Koin/DataStore) persistem entre testes Robolectric.
        // O reset recria o grafo (startKoin registra no GlobalContext).
        AppGraph.resetForTest()
        AppGraph.koin
    }

    private fun bootIntent() = Intent(Intent.ACTION_BOOT_COMPLETED)

    /** Roda onReceive e aguarda a corrotina goAsync consultar a config. */
    private suspend fun receive(intent: Intent) {
        BootCompletedReceiver().onReceive(context, intent)
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun nextStartedService(): Intent? =
        shadowOf(context.applicationContext as Application).nextStartedService

    @Test
    fun `auto_start ativo inicia o serviço`() = kotlinx.coroutines.runBlocking {
        val configStore = AppGraph.koin.get<br.com.luizqueiroz.pwndroid.data.ConfigStore>()
        configStore.setAutoStart(true)

        receive(bootIntent())

        val next = nextStartedService()
        assertNotNull("serviço não iniciado no boot", next)
        assertEquals(PwnForegroundService::class.java.name, next!!.component?.className)
    }

    @Test
    fun `auto_start desativado não inicia o serviço`() = kotlinx.coroutines.runBlocking {
        val configStore = AppGraph.koin.get<br.com.luizqueiroz.pwndroid.data.ConfigStore>()
        configStore.setAutoStart(false)

        receive(bootIntent())

        assertEquals(null, nextStartedService())
    }

    @Test
    fun `ação não-BOOT_COMPLETED é ignorada`() {
        BootCompletedReceiver().onReceive(context, Intent(Intent.ACTION_TIME_TICK))
        // Sem goAsync, sem serviço iniciado.
        assertEquals(null, nextStartedService())
    }
}
