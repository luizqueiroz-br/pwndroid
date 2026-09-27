package br.com.luizqueiroz.pwndroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.session.EpochPhase
import br.com.luizqueiroz.pwndroid.core.session.SessionConfig
import br.com.luizqueiroz.pwndroid.core.session.SessionController
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.koin.core.context.GlobalContext

/**
 * Serviço em primeiro plano (dummy da issue #8; o real é a issue #19):
 * monta o grafo de sessão (Selector → Orchestrator) e roda o ciclo com o
 * backend fake, mantendo o [SessionRegistry] atualizado para a UI.
 */
class PwnForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: SessionController? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller?.stop()
                controller = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                if (controller == null) {
                    val koin = GlobalContext.get()
                    controller = buildController(koin).also {
                        it.start(PwnMode.AUTO)
                    }
                }
            }
        }
        return START_STICKY
    }

    /** Monta o grafo da sessão a partir do Koin (dummy: sem injeção pesada). */
    private fun buildController(koin: Koin): SessionController = SessionController(
        selector = koin.get<BackendSelector>(),
        environment = AndroidBackendEnvironment(this, koin.get()),
        brain = koin.get<Brain>(),
        bus = koin.get(),
        config = SessionConfig(),
        scope = scope,
        registry = koin.get<SessionRegistry>(),
    )

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val koin = GlobalContext.get()
        val state = koin.get<SessionRegistry>().state.value
        val text = when (state.session.phase) {
            EpochPhase.RECON -> "Escutando redes…"
            EpochPhase.INTERACT -> "Interagindo…"
            EpochPhase.REPORT -> "Aprendendo…"
            EpochPhase.IDLE -> "Preparando…"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        return builder
            .setContentTitle("pwndroid")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sessão pwndroid",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pwn_session"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "br.com.luizqueiroz.pwndroid.action.START"
        const val ACTION_STOP = "br.com.luizqueiroz.pwndroid.action.STOP"

        /** Helpers para a UI iniciar/parar o serviço. */
        fun start(context: Context) {
            val intent = Intent(context, PwnForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PwnForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
