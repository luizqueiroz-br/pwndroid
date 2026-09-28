package br.com.luizqueiroz.pwndroid.ui.wardrive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import br.com.luizqueiroz.pwndroid.data.db.AccessPointWithMeta
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Mapa osmdroid (issue #18): pins dos APs com última posição conhecida.
 * Tiles OSM com cache local (offline-first: tiles já vistos continuam
 * renderizando sem internet; MBTiles empacotados entram depois).
 */
@Composable
fun WardriveMap(
    aps: List<AccessPointWithMeta>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Cache de tiles no diretório do app, com user agent próprio (uso
    // justo da API do OSM — configurado antes de qualquer MapView).
    remember {
        Configuration.getInstance().apply {
            userAgentValue = "pwndroid/0.1 (github.com/luizqueiroz-br/pwndroid)"
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid")
        }
        true
    }

    val mapView = remember { MapView(context) }

    AndroidView(
        factory = {
            mapView.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.5)
            }
        },
        update = { map ->
            map.overlays.clear()
            val positioned = aps.filter { it.lastLat != null && it.lastLon != null }
            positioned.forEach { ap ->
                Marker(map).apply {
                    position = org.osmdroid.util.GeoPoint(ap.lastLat!!, ap.lastLon!!)
                    title = ap.ssid ?: "(SSID oculto)"
                    snippet = "${ap.mac} · ${ap.bestRssi?.let { "$it dBm" } ?: "?"}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    map.overlays.add(this)
                }
            }
            // Enquadra o pin mais recente quando o mapa fica vazio.
            if (map.overlays.isEmpty() && positioned.isNotEmpty()) {
                map.controller.setCenter(
                    org.osmdroid.util.GeoPoint(
                        positioned.first().lastLat!!,
                        positioned.first().lastLon!!,
                    ),
                )
            }
        },
        modifier = modifier,
    )

    DisposableEffect(Unit) {
        onDispose {
            mapView.onDetach()
        }
    }
}
