package br.com.luizqueiroz.pwndroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.luizqueiroz.pwndroid.core.mood.FaceState
import br.com.luizqueiroz.pwndroid.core.mood.Mood
import br.com.luizqueiroz.pwndroid.core.session.EpochPhase
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState
import br.com.luizqueiroz.pwndroid.feature.display.FaceRenderer

/**
 * Home mínima da issue #8 (stub; a real é a issue #15): start/stop do
 * serviço + estado da sessão em texto simples.
 */
@Composable
fun HomeScreen(
    ui: SessionUiState,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FaceRenderer(
                face = FaceState(Mood.LONELY, "(⌒▽⌒)", "Estou sozinho, procurando redes…"),
                uptimeText = "época ${ui.session.epoch}",
                handshakesCount = ui.session.handshakes,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !running) { Text("Iniciar") }
                Button(onClick = onStop, enabled = running) { Text("Parar") }
            }

            SessionSummary(ui)
        }
    }
}

@Composable
private fun SessionSummary(ui: SessionUiState) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        val session = ui.session
        Text("Backend: ${ui.backend ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Text("Fase: ${phaseLabel(session.phase)}", style = MaterialTheme.typography.bodyMedium)
        Text("Época: ${session.epoch}", style = MaterialTheme.typography.bodyMedium)
        Text("Alvos vistos: ${session.candidates.size}", style = MaterialTheme.typography.bodyMedium)
        Text("Handshakes: ${session.handshakes}", style = MaterialTheme.typography.bodyMedium)
        Text("PMKIDs: ${session.pmkids}", style = MaterialTheme.typography.bodyMedium)
        if (ui.error != null) {
            Text("Erro: ${ui.error}", color = MaterialTheme.colorScheme.error)
        }
        if (session.stopReason != null) {
            Text("Parou: ${session.stopReason}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun phaseLabel(phase: EpochPhase): String = when (phase) {
    EpochPhase.IDLE -> "ocioso"
    EpochPhase.RECON -> "recon"
    EpochPhase.INTERACT -> "interação"
    EpochPhase.REPORT -> "report"
}
