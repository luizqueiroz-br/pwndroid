package br.com.luizqueiroz.pwndroid.plugins.builtin

import br.com.luizqueiroz.pwndroid.plugins.api.Plugin
import br.com.luizqueiroz.pwndroid.plugins.api.PluginContext

/**
 * Plugin GPS: anexa a posição atual aos eventos de AP/handshake para os
 * exporters (wigle, wpa-sec com GPS). v0.3 entrega a integração real com
 * o LocationManager via contexto Android.
 */
class GpsPlugin : Plugin {
    override val name: String = "gps"
    override val version: String = "0.1.0"
    override val description: String = "Registra a posição GPS junto às capturas."

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    /** Última posição conhecida (lat, lon) ou null. */
    fun lastKnown(): Pair<Double, Double>? = lastLat?.let { lat -> lastLon?.let { lon -> lat to lon } }

    override suspend fun onLoad(context: PluginContext) {
        // TODO(issue #20): consumir LocationManager via bridge injetada.
    }
}