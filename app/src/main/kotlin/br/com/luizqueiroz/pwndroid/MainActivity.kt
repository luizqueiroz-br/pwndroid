package br.com.luizqueiroz.pwndroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.service.PwnForegroundService
import br.com.luizqueiroz.pwndroid.ui.HomeScreen
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val registry = GlobalContext.get().get<SessionRegistry>()
        setContent {
            val ui by registry.state.collectAsState()
            val running = ui.backend != null
            HomeScreen(
                ui = ui,
                running = running,
                onStart = { PwnForegroundService.start(this) },
                onStop = { PwnForegroundService.stop(this) },
            )
        }
    }
}
