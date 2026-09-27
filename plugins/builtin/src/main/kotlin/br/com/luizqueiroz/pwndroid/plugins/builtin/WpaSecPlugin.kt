package br.com.luizqueiroz.pwndroid.plugins.builtin

import br.com.luizqueiroz.pwndroid.plugins.api.Plugin
import br.com.luizqueiroz.pwndroid.plugins.api.PluginContext

/**
 * Plugin wpa-sec: faz upload dos PCAPs para wpa-sec.stanev.org.
 * v0.3 entrega o cliente HTTP real; por ora o plugin é um placeholder
 * com a mesma assinatura de hooks.
 */
class WpaSecPlugin : Plugin {
    override val name: String = "wpa-sec"
    override val version: String = "0.1.0"
    override val description: String = "Envia handshakes para wpa-sec.stanev.org."

    override suspend fun onLoad(context: PluginContext) {
        // TODO(issue #21): cliente Ktor + API key do usuário.
    }
}