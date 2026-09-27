package br.com.luizqueiroz.pwndroid.plugins.api

import br.com.luizqueiroz.pwndroid.core.common.EventBus
import kotlinx.coroutines.CoroutineScope

/**
 * Contrato base dos plugins — porte do sistema de hooks `on_<evento>` do
 * pwnagotchi, mas tipado e resolvido em compile-time via Koin multibinding.
 */
interface Plugin {
    /** Nome único do plugin ("gps", "wpa-sec", "discord"...). */
    val name: String

    /** Versão do plugin. */
    val version: String get() = "1.0.0"

    /** Descrição mostrada na UI de plugins. */
    val description: String get() = ""

    /** Chamado uma vez, quando o host carrega o plugin. */
    suspend fun onLoad(context: PluginContext) {}

    /** Chamado quando o plugin é descarregado. */
    suspend fun onUnload() {}
}

/**
 * Contexto entregue aos plugins: bus de eventos, escopo, acesso a serviços
 * do host (via interfaces, para manter o desacoplamento).
 */
interface PluginContext {
    val bus: EventBus
    val scope: CoroutineScope
    val dataDir: String
}

/**
 * Host dos plugins: carrega os [Plugin] expostos por multibinding do Koin,
 * invoca onLoad/onUnload e distribui hooks de eventos.
 */
class PluginHost(
    private val plugins: List<Plugin>,
    private val context: PluginContext,
) {
    private val loaded = mutableListOf<Plugin>()

    /** Plugins atualmente carregados. */
    val active: List<Plugin> get() = loaded.toList()

    /** Carrega todos os plugins registrados. */
    suspend fun loadAll() {
        for (plugin in plugins) {
            runCatching { plugin.onLoad(context) }
                .onSuccess { loaded.add(plugin) }
        }
    }

    /** Descarrega todos. */
    suspend fun unloadAll() {
        for (plugin in loaded) {
            runCatching { plugin.onUnload() }
        }
        loaded.clear()
    }
}
