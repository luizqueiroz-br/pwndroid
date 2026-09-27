package br.com.luizqueiroz.pwndroid.plugins.api

import br.com.luizqueiroz.pwndroid.core.common.EventBus
import kotlinx.coroutines.CoroutineScope

/**
 * Implementação padrão de [PluginContext] montada pelo :app com os
 * serviços do host.
 */
class SimplePluginContext(
    override val bus: EventBus,
    override val scope: CoroutineScope,
    override val dataDir: String,
) : PluginContext
