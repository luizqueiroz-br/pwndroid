package br.com.luizqueiroz.pwndroid

import android.app.Application
import android.content.pm.ApplicationInfo
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
import br.com.luizqueiroz.pwndroid.core.radio.passive.AndroidSettingsOpener
import br.com.luizqueiroz.pwndroid.core.radio.passive.AndroidSystemChecks
import br.com.luizqueiroz.pwndroid.core.radio.passive.PassiveBackend
import br.com.luizqueiroz.pwndroid.core.radio.passive.PassiveDependencies
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
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
    // Passivo primeiro (sem root, v0.1). O fake é fallback de desenvolvimento:
    // como declara capacidades completas, sempre venceria o selector — só
    // entra em debug. Backends com root (bettercap/nexmon) chegam no Épico 6/9.
    single<List<RadioBackend>> {
        val app = androidApplication()
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val backends = mutableListOf<RadioBackend>(
            PassiveBackend(
                deps = PassiveDependencies(app),
                checks = AndroidSystemChecks(app),
                settingsOpener = AndroidSettingsOpener(app),
            ),
        )
        // O fake declara capacidades completas e sempre venceria o selector:
        // fallback apenas em builds de desenvolvimento.
        if (debuggable) backends.add(FakeRadioBackend(get()))
        backends
    }
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
 * Container Koin acessível pela UI/FGS. Montagem preguiçosa: exige o
 * [PwnApplication] instanciado (o grafo precisa do Context nos backends).
 */
object AppGraph {
    @Volatile
    private var koinInstance: Koin? = null

    val koin: Koin
        get() {
            koinInstance?.let { return it }
            val app = PwnApplication.current()
            return synchronized(this) {
                koinInstance ?: koinApplication {
                    androidContext(app)
                    modules(coreModule, radioModule, brainModule, sessionModule, dataModule, pluginsModule)
                }.koin.also { koinInstance = it }
            }
        }
}

/**
 * Application do app: monta o grafo Koin.
 */
class PwnApplication : Application() {

    companion object {
        @Volatile
        var instance: PwnApplication? = null
            private set

        /** Acesso de teste para injetar a instância sem onCreate. */
        @Volatile
        var INSTANCE_FOR_TEST: PwnApplication? = null

        /** Instância ativa: a do processo ou a de teste. */
        fun current(): PwnApplication = instance ?: INSTANCE_FOR_TEST
            ?: error("PwnApplication não instanciado")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppGraph.koin // força a criação do grafo
    }
}
