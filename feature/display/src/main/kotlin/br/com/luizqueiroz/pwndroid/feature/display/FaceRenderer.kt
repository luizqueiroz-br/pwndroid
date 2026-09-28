package br.com.luizqueiroz.pwndroid.feature.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paleta e-ink: fundo escuro, texto claro (homenagem ao Waveshare do
// original — render em preto sobre cinza-escuro).
private val InkBackground = Color(0xFF1A1A1A)
private val InkForeground = Color(0xFFE0E0E0)
private val InkDim = Color(0xFF8A8A8A)
private val InkFrame = Color(0xFF555555)

/** Largura da moldura de 1px ao redor da tela. */
private val FrameWidth = 1.dp

/**
 * Renderiza o estado facial do tamagotchi em estilo terminal e-ink
 * (issue #12): fundo escuro, monospace grande, kaomoji central + linhas
 * de stats abaixo, moldura de 1px.
 *
 * O ticker de 1 FPS ([faceTicker]) controla recomposição de blink —
 * entre ticks nada renderiza (fidelidade + bateria).
 */
@Composable
fun FaceRenderer(
    state: FaceUiState,
    modifier: Modifier = Modifier,
) {
    val frame = state.frame
    // O blink troca o kaomoji por uma versão "de olhos fechados" no tick.
    val expression = if (state.blink) blinkOf(frame.expression) else frame.expression

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(InkBackground)
            .border(FrameWidth, InkFrame)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Rosto: kaomoji central, monospace grande.
            Text(
                text = expression,
                fontFamily = FontFamily.Monospace,
                fontSize = 64.sp,
                color = InkForeground,
            )
            Spacer(Modifier.height(16.dp))
            // Status: modo + peers + handshakes em uma linha.
            Text(
                text = "mode=${frame.mode} peers=${frame.peers} handshakes=${frame.handshakes}",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = InkDim,
            )
            // Linhas de stats da sessão (epoch, canal, APs, uptime...).
            Spacer(Modifier.height(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                frame.lines.forEach { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = InkDim,
                    )
                }
            }
        }
    }
}

/**
 * Versão "de olhos fechados" do kaomoji para o blink: troca os pares de
 * olhos comuns (■ ○ □ 〇 ◡ ° ●) por um traço fechado (－).
 */
internal fun blinkOf(expression: String): String {
    val closed = expression
        .replace("■", "－")
        .replace("●", "－")
        .replace("○", "－")
        .replace("〇", "－")
        .replace("◡", "－")
        .replace("°", "－")
        .replace("‿", "＿")
        .replace("_", "＿")
    return closed
}

/**
 * Rosto completo (moldura + kaomoji + stats) renderizado em
 * [android.graphics.Bitmap] e exportado como PNG — usado pela web API
 * (issue #43, `GET /face.png`).
 */
fun renderFacePng(
    state: FaceUiState,
    widthPx: Int = 320,
    heightPx: Int = 240,
): ByteArray = FacePngRenderer.render(state, widthPx, heightPx)
