package br.com.luizqueiroz.pwndroid

import android.app.Application
import android.content.pm.ApplicationInfo
import br.com.luizqueiroz.pwndroid.core.brain.Brain
import br.com.luizqueiroz.pwndroid.core.brain.ConfigBrain
import br.com.luizqueiroz.pwndroid.core.brain.ThompsonSamplingBrain
import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.common.PrintAppLogger
import br.com.luizqueiroz.pwndroid.core.common.RealAppClock
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import br.com.luizqueiroz.pwndroid.core.radio.FakeRadioBackend
import br.com.luizqueiroz.pwndroid.core.radio.RadioBackend
import br.com.luizqueiroz.pwndroid.core.radio.bettercap.BettercapBackend
import br.com.luizqueiroz.pwndroid.core.radio.bettercap.BettercapInstaller
import br.com.luizqueiroz.pwndroid.core.radio.bettercap.LibsuRootShell
import br.com.luizqueiroz.pwndroid.core.radio.passive.AndroidSettingsOpener
import br.com.luizqueiroz.pwndroid.core.radio.passive.AndroidSystemChecks
import br.com.luizqueiroz.pwndroid.core.radio.passive.PassiveBackend
import br.com.luizqueiroz.pwndroid.core.radio.passive.PassiveDependencies
import br.com.luizqueiroz.pwndroid.core.session.SessionRegistry
import br.com.luizqueiroz.pwndroid.data.BrainArmRepository
import br.com.luizqueiroz.pwndroid.data.ConfigStore
import br.com.luizqueiroz.pwndroid.data.HandshakeRepository
import br.com.luizqueiroz.pwndroid.data.PwnDatabase
import br.com.luizqueiroz.pwndroid.data.WardriveRepository
import br.com.luizqueiroz.pwndroid.data.WhitelistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
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
    // entra em debug. Bettercap (root, issue #19) entra como candidato:
    // o RootShell falha com RootUnavailableException em devices sem root
    // e o selector degrada para o passivo com a causa no erro da sessão.
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
        // Bettercap root (issue #19): binário via jniLibs, instalado em
        // /data/local/tmp no start (a API REST/WS chega na #20; aqui o
        // candidato valida root + binário e falha rápido sem root, sem
        // crash — o selector degrada para o passivo).
        backends.add(
            BettercapBackend(
                shell = LibsuRootShell(),
                installer = BettercapInstaller(
                    // default: Build.SUPPORTED_ABIS no device real.
                    shell = LibsuRootShell(),
                ),
                apkBinaryProvider = {
                    BettercapInstaller.apkBinary(
                        context = androidApplication(),
                        abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                    )
                },
                clock = get(),
                logger = get(),
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
    // Thompson Sampling (issue #27) é o cérebro padrão: aprende α/β por
    // arma e persiste na tabela brain_arm (BrainArmRepository) — sobrevive
    // a restarts. ConfigBrain (issue #59) sobrepõe a personality configurada
    // pelo usuário; o delegate segue dono do aprendizado.
    single<Brain> {
        val store = get<ConfigStore>()
        ConfigBrain(
            delegate = ThompsonSamplingBrain(
                store = BrainArmRepository(get<PwnDatabase>().brainArmDao()),
            ),
            personalityUpdates = store.config.map { it.personality },
        )
    }
}

val sessionModule = module {
    // SessionController é montado pelo FGS (exige BackendEnvironment com
    // Context); o estado vive no SessionRegistry para UI e web API.
}

val dataModule = module {
    // ConfigStore sobre DataStore Preferences (issue #16, wiring na #59).
    // Scope dedicado: vive enquanto o processo vive.
    single {
        ConfigStore(
            store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    androidApplication().getFileStreamPath("pwndroid_config.preferences_pb")
                },
            ),
            logger = get(),
        )
    }
    // Banco Room da issue #15 + repositórios das telas Wardrive (#18) e
    // Handshakes (#24).
    single { PwnDatabase.build(androidApplication()) }
    single { WardriveRepository(get<PwnDatabase>()) }
    single { WhitelistRepository(get<PwnDatabase>().whitelistDao()) }
    single { HandshakeRepository(get<PwnDatabase>()) }
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
                koinInstance ?: run {
                    // startKoin (não koinApplication): registra no
                    // GlobalContext — o FGS, o MainActivity e o
                    // BootCompletedReceiver consultam GlobalContext.get()
                    // e falhariam com "KoinApplication has not been
                    // started" se o grafo fosse standalone (bug achado
                    // pelo Robolectric na issue #17).
                    org.koin.core.context.startKoin {
                        androidContext(app)
                        modules(coreModule, radioModule, brainModule, sessionModule, dataModule, pluginsModule)
                    }.koin.also { koinInstance = it }
                }
            }
        }

    /** Reset para testes Robolectric: estáticos persistem entre testes. */
    fun resetForTest() {
        synchronized(this) {
            koinInstance?.close()
            koinInstance = null
        }
        runCatching { org.koin.core.context.stopKoin() }
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
