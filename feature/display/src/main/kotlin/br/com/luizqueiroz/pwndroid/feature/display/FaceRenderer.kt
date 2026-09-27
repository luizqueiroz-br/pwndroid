package br.com.luizqueiroz.pwndroid.feature.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.luizqueiroz.pwndroid.core.mood.FaceState
import br.com.luizqueiroz.pwndroid.core.mood.Mood

/**
 * Renderiza o estado facial do tamagotchi em estilo e-ink do pwnagotchi
 * (fundo claro, texto escuro, fonte monoespaçada).
 */
@Composable
fun FaceRenderer(
    face: FaceState,
    uptimeText: String,
    handshakesCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F7F0))
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "pwndroid",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = Color(0xFF222222),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "uptime: $uptimeText",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFF555555),
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = face.kaomoji,
            fontFamily = FontFamily.Monospace,
            fontSize = 72.sp,
            color = Color(0xFF222222),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = face.status,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            color = Color(0xFF444444),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "mood=${face.mood.name.lowercase()} handshakes=$handshakesCount",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFF555555),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "pwndroid v0.1.0",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFF555555),
        )
    }
}
