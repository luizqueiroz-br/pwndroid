package br.com.luizqueiroz.pwndroid.feature.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Renderização do rosto em PNG (issue #12): mesma disposição do
 * [FaceRenderer] Compose — fundo escuro, kaomoji central grande,
 * stats abaixo — desenhado com Canvas sobre Bitmap.
 *
 * É uma renderização independente (não captura da View) para funcionar
 * headless: a web API (#43) chama `renderFacePng()` fora da UI.
 */
internal object FacePngRenderer {

    fun render(state: FaceUiState, widthPx: Int, heightPx: Int): ByteArray {
        val frame = state.frame
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.rgb(0x1A, 0x1A, 0x1A))

        // Moldura de 1px.
        val framePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.rgb(0x55, 0x55, 0x55)
        }
        canvas.drawRect(0.5f, 0.5f, widthPx - 0.5f, heightPx - 0.5f, framePaint)

        val expression = if (state.blink) blinkOf(frame.expression) else frame.expression

        val facePaint = textPaint(56f, Color.rgb(0xE0, 0xE0, 0xE0))
        val smallPaint = textPaint(11f, Color.rgb(0x8A, 0x8A, 0x8A))
        val dimPaint = textPaint(12f, Color.rgb(0x8A, 0x8A, 0x8A))

        // Kaomoji central.
        val faceY = heightPx * 0.40f
        canvas.drawText(expression, widthPx / 2f, faceY, facePaint.also { it.textAlign = Paint.Align.CENTER })

        // Linha de meta (mode/peers/handshakes).
        var y = faceY + 40f
        canvas.drawText(
            "mode=${frame.mode} peers=${frame.peers} handshakes=${frame.handshakes}",
            16f,
            y,
            dimPaint,
        )
        y += 18f

        // Linhas de stats da sessão.
        frame.lines.forEach { line ->
            canvas.drawText(line, 16f, y, smallPaint)
            y += 14f
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun textPaint(sizePx: Float, color: Int): Paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = sizePx
        this.color = color
    }
}
