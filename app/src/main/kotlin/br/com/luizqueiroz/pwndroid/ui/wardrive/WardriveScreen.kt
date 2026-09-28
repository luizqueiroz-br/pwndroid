package br.com.luizqueiroz.pwndroid.ui.wardrive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Tela Wardrive (issue #18): lista de APs vistos — o "troféu" do modo sem
 * root — com meta por AP e atalhos de export. O mapa osmdroid e o detalhe
 * (whitelist) vivem em [WardriveMap] e [AccessPointDetailDialog].
 */
@Composable
fun WardriveScreen(
    aps: List<br.com.luizqueiroz.pwndroid.data.db.AccessPointWithMeta>,
    onSelectAp: (String) -> Unit,
    onExportCsv: () -> Unit,
    onExportKml: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExportCsv) { Text("CSV WiGLE") }
            Button(onClick = onExportKml) { Text("KML") }
        }

        if (aps.isEmpty()) {
            Text(
                "Nenhuma rede vista ainda. Inicie uma sessão na Home — " +
                    "cada AP avistado aparece aqui com RSSI e posição.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(aps, key = { it.mac }) { ap ->
                    AccessPointRow(ap, onClick = { onSelectAp(ap.mac) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AccessPointRow(
    ap: br.com.luizqueiroz.pwndroid.data.db.AccessPointWithMeta,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                ap.ssid ?: "(SSID oculto)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${ap.mac} · ${ap.encryption ?: "aberta"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "visto ${ap.timesSeen}× · ${ap.sightingCount} leituras · " +
                    "RSSI máx ${ap.bestRssi ?: "?"} dBm" +
                    (if (ap.lastLat != null && ap.lastLon != null) {
                        " · pos %.5f, %.5f".format(ap.lastLat, ap.lastLon)
                    } else {
                        " · sem GPS"
                    }),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Detalhe de um AP: histórico de sightings e whitelist ética ("não atacar
 * a própria casa").
 */
@Composable
fun AccessPointDetailDialog(
    ap: br.com.luizqueiroz.pwndroid.data.db.AccessPointEntity,
    sightings: List<br.com.luizqueiroz.pwndroid.data.db.SightingEntity>,
    whitelisted: Boolean,
    onToggleWhitelist: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
        title = { Text(ap.ssid ?: "(SSID oculto)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${ap.mac} · ${ap.encryption ?: "aberta"}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "visto ${ap.timesSeen}× · primeira leitura ${ap.firstSeenMillis} · " +
                        "última ${ap.lastSeenMillis}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("leituras: ${sightings.size}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onToggleWhitelist) {
                    Text(if (whitelisted) "Remover da whitelist" else "Adicionar à whitelist")
                }
            }
        },
    )
}
