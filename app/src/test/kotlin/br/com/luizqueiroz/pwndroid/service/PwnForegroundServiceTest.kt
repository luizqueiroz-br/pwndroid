package br.com.luizqueiroz.pwndroid.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import br.com.luizqueiroz.pwndroid.AppGraph
import br.com.luizqueiroz.pwndroid.PwnApplication
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * Testes do FGS real (issue #17): notificação viva com stats e ação
 * "Parar", wake locks adquiridos na sessão e liberados no destroy,
 * ciclo de vida START_STICKY.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PwnApplication::class)
class PwnForegroundServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        // Estáticos (Koin/DataStore) persistem entre testes Robolectric:
        // grafo novo por teste evita "multiple DataStores for the same
        // file". O reset recria o grafo (startKoin registra no
        // GlobalContext — o serviço consulta por lá).
        AppGraph.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        AppGraph.koin // recria o grafo e registra no GlobalContext
        // Permissão de notificação concedida (Robolectric padrão = negada em T+).
        shadowOf(PwnApplication.current() as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Sem AP programado: a sessão roda épocas cegas até maxEpochs.
        // maxEpochs=1 é suficiente para exercitar start/stop/notificação.
    }

    @After
    fun tearDown() {
        // Limpa o rastro estático de wake locks entre testes.
        ShadowPowerManager.clearWakeLocks()
    }

    private fun startIntent() = Intent(context, PwnForegroundService::class.java).apply {
        action = PwnForegroundService.ACTION_START
    }

    private fun stopIntent() = Intent(context, PwnForegroundService::class.java).apply {
        action = PwnForegroundService.ACTION_STOP
    }

    private fun startService(): ServiceController<PwnForegroundService> {
        val controller = Robolectric.buildService(PwnForegroundService::class.java)
        // onCreate precede onStartCommand (no device é sempre assim; o
        // Robolectric exige o create() explícito).
        controller.create().withIntent(startIntent()).startCommand(0, 1)
        // start() é assíncrono no escopo do serviço (Dispatchers.Default):
        // dá um giro no looper para o start da sessão rodar.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return controller
    }

    @Test
    fun `start cria notificação com ação parar`() {
        startService()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = nm.activeNotifications.firstOrNull { it.id == 1 }
        assertNotNull("notificação do FGS não publicada", posted)
        val actions = posted!!.notification.actions.orEmpty()
        assertTrue("ação Parar ausente", actions.any { it.title == "Parar" })
    }

    @Test
    fun `notificação mostra stats da sessão`() {
        startService()

        // Publica um estado no registry — a notificação deve refletir.
        val registry = AppGraph.koin.get<SessionRegistry>()
        registry.publish(
            br.com.luizqueiroz.pwndroid.core.session.SessionUiState(
                session = br.com.luizqueiroz.pwndroid.core.session.SessionState(
                    epoch = 7,
                    handshakes = 2,
                    pmkids = 3,
                ),
            ),
        )
        // O watcher roda em Dispatchers.Default (fora do looper do
        // Robolectric): espera de polling até a notificação refletir. O
        // publish é refeito a cada volta — o collector do SessionController
        // pode emitir o estado real da sessão (época 0) e sobrescrever.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val estadoComStats = br.com.luizqueiroz.pwndroid.core.session.SessionUiState(
            session = br.com.luizqueiroz.pwndroid.core.session.SessionState(
                epoch = 7,
                handshakes = 2,
                pmkids = 3,
            ),
        )
        val deadline = System.currentTimeMillis() + 2_000
        var texto = ""
        while (System.currentTimeMillis() < deadline) {
            registry.publish(estadoComStats)
            texto = nm.activeNotifications
                .firstOrNull { it.id == 1 }
                ?.notification?.extras
                ?.getString(android.app.Notification.EXTRA_TEXT)
                .orEmpty()
            if (texto.contains("época 7")) break
            Thread.sleep(50)
        }
        assertTrue(
            "stats ausentes no texto: $texto",
            texto.contains("época 7") && texto.contains("HS 2") && texto.contains("PMKID 3"),
        )
    }

    @Test
    fun `ação parar derruba a sessão e a notificação`() {
        val controller = startService()

        controller.withIntent(stopIntent()).startCommand(0, 2)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // O ShadowService rastreia stopForeground(STOP_FOREGROUND_REMOVE)
        // como estado interno (não remove do NotificationManager ativo).
        val shadow = shadowOf(controller.get())
        assertTrue(
            "stopForeground(remove) não chamado",
            shadow.isForegroundStopped && shadow.notificationShouldRemoved,
        )
    }

    @Test
    fun `wake lock adquirido no start e liberado no destroy`() {
        val controller = startService()

        val cpuLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("wake lock parcial não adquirido", cpuLock)
        assertTrue("wake lock não está held", cpuLock!!.isHeld)

        controller.destroy()
        assertFalse("wake lock vazou no destroy", cpuLock.isHeld)
    }

    @Test
    fun `stop duplo é seguro`() {
        val controller = startService()
        controller.withIntent(stopIntent()).startCommand(0, 2)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Segundo stop em serviço já parado: não deve lançar.
        controller.withIntent(stopIntent()).startCommand(0, 3)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
