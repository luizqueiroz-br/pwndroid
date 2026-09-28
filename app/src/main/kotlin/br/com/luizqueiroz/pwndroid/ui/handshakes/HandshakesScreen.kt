package br.com.luizqueiroz.pwndroid.ui.handshakes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela Handshakes (issue #24): o "estoque de comida" do tamagotchi.
 * Lista as capturas registradas (pcap + tipo + hora) com indicação de
 * upload por plugin; tap abre o detalhe com export do pcap.
 */
@Composable
fun HandshakesScreen(
    handshakes: List<HandshakeEntity>,
    onSelect: (HandshakeEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (handshakes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("(・_・;)", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Nenhum handshake capturado ainda.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "A captura (pcap + PMKID) requer backend com monitor " +
                        "mode — bettercap via root ou driver nativo. O modo " +
                        "wardrive sem root continua coletando redes na aba " +
                        "Wardrive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(handshakes, key = { it.id }) { h ->
                    HandshakeRow(h, onClick = { onSelect(h) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HandshakeRow(
    h: HandshakeEntity,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    h.essid ?: "(SSID oculto)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TypeBadge(type = h.type)
            }
            Text(
                "${h.bssid} · sta ${h.station}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                formatTime(h.capturedAtMillis) + uploadSuffix(h),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Badge colorido por tipo: FULL verde, PMKID roxo, HALF âmbar. */
@Composable
private fun TypeBadge(type: String) {
    val color = when (type) {
        "FULL" -> MaterialTheme.colorScheme.primary
        "PMKID" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Badge(containerColor = color, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Text(type)
    }
}

/** Sufixo de status de upload na linha (flags dos plugins, v0.3). */
private fun uploadSuffix(h: HandshakeEntity): String = buildString {
    if (h.uploadedWpaSec) append(" · wpa-sec ✓")
    if (h.uploadedOnlineHashCracking) append(" · ohc ✓")
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        .format(Date(millis))
