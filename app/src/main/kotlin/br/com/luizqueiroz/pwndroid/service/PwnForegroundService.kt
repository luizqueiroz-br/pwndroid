package br.com.luizqueiroz.pwndroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.session.SessionConfig
import br.com.luizqueiroz.pwndroid.core.session.SessionController
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState
import br.com.luizqueiroz.pwndroid.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.context.GlobalContext

/**
 * Serviço em primeiro plano (issue #17 — versão real, substitui o dummy da
 * #8): monta o grafo de sessão (Selector → Orchestrator) e roda o ciclo
 * com o backend selecionado, mantendo o [SessionRegistry] atualizado.
 *
 * Sobrevivência em longa duração:
 * - `PARTIAL_WAKE_LOCK` durante a sessão ativa (CPU não dorme entre
 *   épocas); liberado em [onDestroy] — auditável no logcat.
 * - `WifiManager.WifiLock` de alta performance quando o backend usa
 *   rádio Wi-Fi; liberado no stop.
 * - Notificação viva: stats (época, APs, handshakes) atualizados a cada
 *   mudança de estado da sessão, com ação "Parar".
 * - Boot: [BootCompletedReceiver] retoma a sessão se `auto_start` estiver
 *   ativo na config.
 */
class PwnForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Construção/atualização da notificação (stats vivos). */
    private lateinit var notifier: NotificationHelper
    private var controller: SessionController? = null
    private var stateWatcher: kotlinx.coroutines.Job? = null

    /** CPU parcialmente acordada enquanto a sessão roda. */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Wi-Fi em alta performance enquanto o backend de rádio atua. */
    private var wifiLock: WifiManager.WifiLock? = null

    /** Medição de bateria (log de drain — risco #6 do plano). */
    private var batteryMonitor: BatteryMonitor? = null

    override fun onCreate() {
        super.onCreate()
        notifier = NotificationHelper(this)
        notifier.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                if (controller == null) {
                    val koin = GlobalContext.get()
                    val configStore = koin.get<ConfigStore>()
                    acquireLocks()
                    val newController = buildController(koin).also { controller = it }
                    // Modo e backend preferido vêm da config (primeira emissão
                    // do fluxo): o backend preferido vale no start (issue #13).
                    scope.launch {
                        val config = configStore.config.first()
                        newController.start(config.mode, preferredBackend = config.backendPreference)
                    }
                    watchState(koin)
                    batteryMonitor = BatteryMonitor(
                        context = this,
                        scope = scope,
                        logger = koin.get(),
                        registryProvider = { koin.get<SessionRegistry>() },
                    ).also { it.start() }
                }
            }
        }
        return START_STICKY
    }

    /** Monta o grafo da sessão a partir do Koin (config wiring da #59). */
    private fun buildController(koin: Koin): SessionController {
        val configStore = koin.get<ConfigStore>()
        return SessionController(
            selector = koin.get<BackendSelector>(),
            environment = AndroidBackendEnvironment(this, koin.get()),
            brain = koin.get<Brain>(),
            bus = koin.get(),
            config = SessionConfig(),
            scope = scope,
            registry = koin.get<SessionRegistry>(),
            // Modo configurado vale a partir da próxima época, sem restart
            // (backendPreference NÃO é observado aqui: exige nova sessão).
            modeUpdates = configStore.config.map { it.mode },
        )
    }

    /** Adquire wake locks; auditados no logcat e liberados no destroy. */
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pwndroid:session").also {
            it.setReferenceCounted(false)
            it.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "pwndroid:wifi",
        ).also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseLocks() {
        // Audit: liberar todos os locks em stop/destroy (nenhum leak).
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    /** Observa o estado da sessão e atualiza a notificação (stats vivos). */
    private fun watchState(koin: Koin) {
        stateWatcher = scope.launch {
            koin.get<SessionRegistry>().state.collect { state ->
                notifier.update(state)
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = notifier.build(GlobalContext.get().get<SessionRegistry>().state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun stopSession() {
        releaseLocks()
        stateWatcher?.cancel()
        stateWatcher = null
        batteryMonitor?.stop()
        batteryMonitor = null
        controller?.stop()
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Wake lock renovado por hora (max legal e suficiente p/ épocas). */
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

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
