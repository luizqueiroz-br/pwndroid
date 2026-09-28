package br.com.luizqueiroz.pwndroid.ui.handshakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import br.com.luizqueiroz.pwndroid.data.db.HandshakeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog do detalhe do handshake (issue #24): dados da captura e export
 * do pcap binário (FileProvider + ACTION_SEND, mime octet-stream).
 */
@Composable
fun HandshakeDetailDialog(
    handshake: HandshakeEntity,
    pcapExists: Boolean,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(handshake.essid ?: "(SSID oculto)") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(handshake.type, style = MaterialTheme.typography.titleMedium)
                Text(handshake.bssid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    "Estação: ${handshake.station}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Capturado em " +
                        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                            .format(Date(handshake.capturedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Arquivo: ${handshake.pcapPath.substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (pcapExists) {
                        "pcap presente no disco; exporte para crackar " +
                            "com hcxpcapngtool + hashcat (issue #25)."
                    } else {
                        "Arquivo do pcap não encontrado no disco " +
                            "(limpeza do sistema ou captura de sessão anterior)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            if (pcapExists) {
                TextButton(onClick = { onExport(); onDismiss() }) { Text("Exportar pcap") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

/** Nome amigável do arquivo (mesma regra do PcapWriter.fileNameFor). */
internal fun HandshakeEntity.fileName(): String =
    pcapPath.substringAfterLast('/').ifBlank { "${bssid.replace(":", "")}.pcap" }
