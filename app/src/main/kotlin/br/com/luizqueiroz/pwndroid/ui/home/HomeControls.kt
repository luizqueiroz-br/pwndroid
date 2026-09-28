package br.com.luizqueiroz.pwndroid.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.luizqueiroz.pwndroid.core.model.PwnMode
import br.com.luizqueiroz.pwndroid.core.radio.BackendId
import br.com.luizqueiroz.pwndroid.core.session.SessionUiState

/**
 * Controles da tela Home (issue #13): stats da sessão, seletor de modo
 * (MANU/AUTO/AI — AI desabilitado até a #30) e seletor de backend. Em
 * arquivo próprio para manter HomeScreen.kt dentro dos limites do
 * detekt (TooManyFunctions/LongMethod).
 */
/** Stats da sessão em texto terminal (estilo pwnagotchi). */
@Composable
internal fun SessionStats(ui: SessionUiState, modifier: Modifier = Modifier) {
    val session = ui.session
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "backend=${ui.backend?.name?.lowercase() ?: "—"} · fase=${session.phase.name.lowercase()}",
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Text(
            "alvos=${session.candidates.size} · cegas=${session.blindEpochs}",
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        if (session.stopReason != null) {
            Text(
                "parou: ${session.stopReason}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Seletor de modo (issue #13): MANU/AUTO/AI. AI desabilitado até a
 * issue #30 (experimental_ai na config libera, mas o cérebro A2C ainda
 * não existe — v0.1 usa Thompson/Fixed como selector de alvos).
 */
@Composable
internal fun ModeSelector(
    selected: PwnMode,
    onModeSelected: (PwnMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Modo", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeOption(PwnMode.MANUAL, selected, onModeSelected)
            ModeOption(PwnMode.AUTO, selected, onModeSelected)
            ModeOption(PwnMode.AI, selected, onModeSelected, enabled = false)
        }
    }
}

@Composable
private fun ModeOption(
    mode: PwnMode,
    selected: PwnMode,
    onModeSelected: (PwnMode) -> Unit,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = mode == selected,
        onClick = { onModeSelected(mode) },
        enabled = enabled,
        label = { Text(modeChipLabel(mode)) },
    )
}

private fun modeChipLabel(mode: PwnMode): String = when (mode) {
    PwnMode.MANUAL -> "MANU"
    PwnMode.AUTO -> "AUTO"
    PwnMode.AI -> "AI"
    PwnMode.PASSIVE -> "PASSIVO"
}

/**
 * Seletor de backend (issue #13): lista de backends com capacidades.
 * A troca vale apenas na PRÓXIMA sessão (o backend é selecionado só no
 * start — comportamento do SessionController, issue #59).
 */
@Composable
internal fun BackendSelector(
    available: List<BackendId>,
    selected: BackendId?,
    activeBackend: BackendId?,
    onBackendSelected: (BackendId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Backend", style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackendOption(null, "Automático", selected, onBackendSelected)
            available
                .filter { it != BackendId.FAKE }
                .forEach { backend ->
                    BackendOption(backend, backendLabel(backend), selected, onBackendSelected)
                }
        }
        // Indicador de backend ativo + badge de limitação quando passivo.
        when {
            activeBackend == null -> ActiveBackendBadge("sem sessão ativa", false)
            activeBackend == BackendId.PASSIVE -> ActiveBackendBadge("passivo (sem root) — modo limitado", true)
            else -> ActiveBackendBadge("ativo: ${activeBackend.name.lowercase()}", false)
        }
    }
}

@Composable
private fun BackendOption(
    backend: BackendId?,
    label: String,
    selected: BackendId?,
    onBackendSelected: (BackendId?) -> Unit,
) {
    FilterChip(
        selected = backend == selected,
        onClick = { onBackendSelected(backend) },
        label = { Text(label) },
    )
}

/** Rótulo humano do backend (issue #13). */
internal fun backendLabel(backend: BackendId): String = when (backend) {
    BackendId.PASSIVE -> "Passivo (sem root)"
    BackendId.BETTERCAP -> "bettercap (root)"
    BackendId.NEXMON -> "nexmon (root, experimental)"
    BackendId.ESP32 -> "ESP32 via OTG"
    BackendId.FAKE -> "fake (dev)"
}

/** Badge do backend ativo, destacado quando limitado. */
@Composable
private fun ActiveBackendBadge(text: String, limited: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (limited) "⚠ $text" else text,
        fontSize = 12.sp,
        color = if (limited) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}


