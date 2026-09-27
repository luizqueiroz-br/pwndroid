package br.com.luizqueiroz.pwndroid

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.test.check.checkModules
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.koin.android.ext.koin.androidContext

/**
 * Smoke test do grafo Koin: checkModules valida que cada definição
 * resolve (e cria) suas dependências. O radioModule exige Context —
 * roda com Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PwnApplication::class)
class AppGraphVerifyTest {

    private fun app(): PwnApplication =
        PwnApplication.current()

    @Test
    fun `grafo koin é válido`() {
        val koin = koinApplication {
            androidContext(app())
            modules(coreModule, radioModule, brainModule, sessionModule, dataModule, pluginsModule)
        }.koin
        koin.checkModules()
    }

    @Test
    fun `serviços de core são resolvidos`() {
        val koin = koinApplication {
            modules(coreModule, brainModule, sessionModule, dataModule, pluginsModule)
        }.koin
        assertNotNull(koin.get<AppClock>())
        assertNotNull(koin.get<AppLogger>())
        assertNotNull(koin.get<EventBus>())
    }

    @Test
    fun `radioModule produz selector com backends`() {
        val koin = koinApplication {
            androidContext(app())
            modules(coreModule, radioModule)
        }.koin
        val selector = koin.get<BackendSelector>()
        assertNotNull(selector)
        // O passivo está sempre registrado (fake só em debug).
        val backends = koin.get<List<br.com.luizqueiroz.pwndroid.core.radio.RadioBackend>>()
        assertEquals(true, backends.any { it.id == br.com.luizqueiroz.pwndroid.core.radio.BackendId.PASSIVE })
    }
}
