package br.com.luizqueiroz.pwndroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import br.com.luizqueiroz.pwndroid.core.mood.FaceState
import br.com.luizqueiroz.pwndroid.core.mood.Mood
import br.com.luizqueiroz.pwndroid.feature.display.FaceRenderer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demoFace = FaceState(Mood.LONELY, "(⌒▽⌒)", "Estou sozinho, procurando redes…")
        setContent {
            val face by kotlinx.coroutines.flow.MutableStateFlow(demoFace).collectAsState()
            FaceRenderer(
                face = face,
                uptimeText = "00:00",
                handshakesCount = 0,
            )
        }
    }
}