package br.com.luizqueiroz.pwndroid

import br.com.luizqueiroz.pwndroid.core.common.AppClock
import br.com.luizqueiroz.pwndroid.core.common.AppLogger
import br.com.luizqueiroz.pwndroid.core.common.EventBus
import br.com.luizqueiroz.pwndroid.core.radio.BackendSelector
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.test.check.checkModules

/**
 * Smoke test do grafo Koin: checkModules valida que cada definição
 * resolve (e cria) suas dependências.
 */
class AppGraphVerifyTest {

    @Test
    fun `grafo koin é válido`() {
        AppGraph.koin.checkModules()
    }

    @Test
    fun `serviços de core são resolvidos`() {
        val koin = org.koin.dsl.koinApplication {
            modules(coreModule, radioModule, brainModule, sessionModule, dataModule, pluginsModule)
        }.koin
        assertNotNull(koin.get<AppClock>())
        assertNotNull(koin.get<AppLogger>())
        assertNotNull(koin.get<EventBus>())
        assertNotNull(koin.get<BackendSelector>())
    }
}
