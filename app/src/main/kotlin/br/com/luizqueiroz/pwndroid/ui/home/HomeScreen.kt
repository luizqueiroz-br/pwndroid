package br.com.luizqueiroz.pwndroid.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.luizqueiroz.pwndroid.core.brain.BrainSnapshot
import br.com.luizqueiroz.pwndroid.core.mood.Mood
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState
import br.com.luizqueiroz.pwndroid.feature.display.FaceRenderer
import br.com.luizqueiroz.pwndroid.feature.display.FaceUiState

/**
 * Tela Home do tamagotchi (issue #13): rosto e-ink grande (~60% da
 * altura), stats da sessão, seletor de modo (MANU/AUTO/AI) e de backend,
 * chip de humor com cor semântica e start/stop da sessão.
 *
 * Sem Navigation type-safe ainda: a navegação por tabs vive no
 * MainActivity (v0.1); roteamento real entra junto com onboarding (#14).
 */
@Composable
fun HomeScreen(
    face: FaceUiState,
    mood: Mood,
    ui: SessionUiState,
    running: Boolean,
    config: HomeConfig,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FacePanel(face)
        MoodChip(mood)
        SessionStats(ui)
        BrainCard(config.brain)
        ModeSelector(config.mode, actions.onModeSelected)
        BackendSelector(
            available = config.availableBackends,
            selected = config.backendPreference,
            activeBackend = ui.backend,
            onBackendSelected = actions.onBackendSelected,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onStart, enabled = !running) { Text("Iniciar") }
            OutlinedButton(onClick = actions.onStop, enabled = running) { Text("Parar") }
        }
        ui.error?.let { error ->
            Text("Erro: $error", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Rosto do tamagotchi ocupando ~60% da tela. */
@Composable
private fun FacePanel(face: FaceUiState, modifier: Modifier = Modifier) {
    // contentDescription por estado do rosto: o tamagotchi é "lido" por
    // leitores de tela com a mesma informação visual.
    val description = faceDescription(face)
    FaceRenderer(
        state = face,
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .semantics { contentDescription = description },
    )
}

/** Descrição acessível por estado do rosto (issue #13). */
internal fun faceDescription(face: FaceUiState): String {
    val moodLabel = moodLabel(face.frame.mood)
    val eyes = if (face.blink) "com os olhos fechados" else "com os olhos abertos"
    return "Tamagotchi $moodLabel, $eyes. " +
        "Modo ${face.frame.mode}, ${face.frame.peers} peers, " +
        "${face.frame.handshakes} handshakes."
}

internal fun moodLabel(mood: Mood): String = when (mood) {
    Mood.LONELY -> "solitário, procurando redes"
    Mood.BORED -> "entediado, nenhuma rede nova"
    Mood.SAD -> "triste, sem atividade há muito tempo"
    Mood.ANGRY -> "irritado, muitas falhas seguidas"
    Mood.EXCITED -> "animado, capturando handshakes"
    Mood.GRATEFUL -> "grato, com uma rede de pares ativa"
    Mood.HAPPY -> "feliz, explorando"
}

/** Chip de humor com cor semântica (verde/cinza/vermelho). */
@Composable
private fun MoodChip(mood: Mood, modifier: Modifier = Modifier) {
    val (label, color) = moodChipOf(mood)
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
        ),
        modifier = modifier.semantics {
            contentDescription = "Humor atual: $label"
        },
    )
}

/** Rótulo + cor do chip por humor (issue #13: verde/cinza/vermelho). */
internal fun moodChipOf(mood: Mood): Pair<String, Color> = when (mood) {
    Mood.EXCITED, Mood.GRATEFUL, Mood.HAPPY -> "👍 ${mood.name.lowercase()}" to Color(0xFF43A047)
    Mood.SAD, Mood.BORED, Mood.LONELY -> "😐 ${mood.name.lowercase()}" to Color(0xFF9E9E9E)
    Mood.ANGRY -> "😠 ${mood.name.lowercase()}" to Color(0xFFE53935)
}

/**
 * Cartão do cérebro em operação (issue #26): id, personalidade vigente e
 * o desfecho da última época — sem expor a implementação.
 */
@Composable
private fun BrainCard(snapshot: BrainSnapshot?, modifier: Modifier = Modifier) {
    if (snapshot == null) return
    Column(modifier = modifier.semantics {
        contentDescription = "Cérebro ${snapshot.id} em operação"
    }) {
        Text("Cérebro", style = MaterialTheme.typography.labelMedium)
        Text(
            "id=${snapshot.id}",
            fontSize = 12.sp,
        )
        snapshot.personality?.let { persona ->
            Text(
                "recon=${persona.reconTimeSec}s max_interactions=${persona.maxInteractions}" +
                    " deauth=${persona.deauthCount}",
                fontSize = 12.sp,
            )
        }
        snapshot.lastEpoch?.let { last ->
            Text(
                "última época: hs=${last.handshakesCaptured} pmkid=${last.pmkidCaptured}" +
                    " interações=${last.interactionsAttempted} reward=${last.reward}",
                fontSize = 12.sp,
            )
        }
    }
}



