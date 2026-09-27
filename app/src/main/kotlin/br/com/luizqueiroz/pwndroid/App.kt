package br.com.luizqueiroz.pwndroid

import android.app.Application
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.common.PrintAppLogger
import br.com.luizqueiroz.pwndroid.core.common.RealAppClock
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.radio.RadioBackend
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Módulos Koin por camada. Por ora, singletons de infraestrutura; os
 * backends reais entram conforme as issues do Épico 2/3.
 */
val coreModule = module {
    single<AppClock> { RealAppClock() }
    single<AppLogger> { PrintAppLogger() }
    single { EventBus() }
}

val radioModule = module {
    // Backends registrados; o passivo real (WifiManager) chega na issue #7.
    single<List<RadioBackend>> { emptyList() }
    single { BackendSelector(get()) }
}

val brainModule = module {
    // Thompson Sampling chega na issue #12; FixedBrain é o padrão provisório.
}

val sessionModule = module {
    // EpochOrchestrator exige um StartedBackend — wire junto com radioModule
    // quando o backend passivo existir.
}

val dataModule = module {
    // Room/DataStore (issue #9).
}

val pluginsModule = module {
    // PluginHost + builtin plugins (issue #19).
}

/**
 * Container Koin acessível pela UI/FGS. Um único koinApplication por
 * processo; `KoinComponent` resolve os deps onde necessário.
 */
object AppGraph {
    val koin = koinApplication {
        modules(coreModule, radioModule, brainModule, sessionModule, dataModule, pluginsModule)
    }.koin
}

/**
 * Application do app: monta o grafo Koin.
 */
class PwnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.koin // força a criação do grafo
    }
}
