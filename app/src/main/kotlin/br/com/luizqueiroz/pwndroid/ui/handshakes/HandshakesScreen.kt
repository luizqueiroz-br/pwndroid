package br.com.luizqueiroz.pwndroid.ui.handshakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tela Handshakes (stub da issue #18): a captura real (pcap + slicing)
 * chega na v0.2 — Épico 7, issues #23–#25 — e exige root/bettercap ou
 * backend nativo. Enquanto isso, empty state honesto.
 */
@Composable
fun HandshakesScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("(・_・;)", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Nenhum handshake capturado ainda.",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "A captura de handshakes (pcap + PMKID) requer backend com " +
                "monitor mode — bettercap via root ou driver nativo, " +
                "disponíveis a partir da v0.2. O modo wardrive sem root " +
                "continua coletando redes na aba Wardrive.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
