package br.com.luizqueiroz.pwndroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import br.com.luizqueiroz.pwndroid.core.session.EpochPhase
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState

/**
 * Construção da notificação do FGS (issue #17): stats vivos (época, APs,
 * handshakes, PMKIDs) e ação "Parar". Extraída do serviço para manter a
 * classe enxuta — o canal também vive aqui (criado no onCreate).
 */
internal class NotificationHelper(private val context: Context) {

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sessão pwndroid",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun update(state: SessionUiState) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, build(state))
    }

    fun build(state: SessionUiState): Notification {
        val text = when (state.session.phase) {
            EpochPhase.RECON -> "Escutando redes…"
            EpochPhase.INTERACT -> "Interagindo…"
            EpochPhase.REPORT -> "Aprendendo…"
            EpochPhase.IDLE -> "Preparando…"
        }
        val stats = "época %d · %d AP · HS %d · PMKID %d".format(
            state.session.epoch,
            state.session.candidates.size,
            state.session.handshakes,
            state.session.pmkids,
        )
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, PwnForegroundService::class.java).apply { action = PwnForegroundService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle("pwndroid · ${state.session.mode.name.lowercase()}")
            .setContentText("$text · $stats")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pwn_session"
        const val NOTIFICATION_ID = 1
    }
}
