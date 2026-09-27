package br.com.luizqueiroz.pwndroid

import android.app.Application
import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.FixedBrain
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.common.PrintAppLogger
import br.com.luizqueiroz.pwndroid.core.common.RealAppClock
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.radio.FakeRadioBackend
import br.com.luizqueiroz.pwndroid.core.radio.RadioBackend
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
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
    single { SessionRegistry() }
}

val radioModule = module {
    // Backends registrados; o passivo real (WifiManager) chega na issue #9.
    // Por ora só o fake: valida o wiring ponta a ponta (issue #8).
    single<List<RadioBackend>> { listOf(FakeRadioBackend(get())) }
    single { BackendSelector(get()) }
}

val brainModule = module {
    // Thompson Sampling chega na issue #12; FixedBrain é o padrão provisório.
    single<Brain> { FixedBrain() }
}

val sessionModule = module {
    // SessionController é montado pelo FGS (exige BackendEnvironment com
    // Context); o estado vive no SessionRegistry para UI e web API.
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
