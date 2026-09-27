package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Provedores de contexto Android exigidos pelo [PassiveBackend], montados
 * no :app — mantém o backend instanciável em Robolectric.
 */
class PassiveDependencies(
    val appContext: Context,
)

/** Fábrica de WifiManager (substituível em teste). */
fun interface WifiManagerProvider {
    fun get(context: Context): WifiManager
}

/** Checagens de sistema substituíveis em teste (localização, Wi-Fi). */
interface SystemChecks {
    fun isLocationEnabled(): Boolean
    fun isWifiEnabled(): Boolean
}

/** Abrir a tela de configurações (user-actionable para localização). */
fun interface SettingsOpener {
    fun openLocationSettings()
}

/** Abre as configurações de localização do Android (user-actionable). */
class AndroidSettingsOpener(private val context: Context) : SettingsOpener {
    override fun openLocationSettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * Observa conectividade via [ConnectivityManager.NetworkCallback] e emite
 * [RadioEvent.InternetAvailable] quando uma rede com INTERNET fica válida.
 */
class ConnectivityWatcher(private val context: Context) {

    /** Emite quando uma rede validada com internet fica disponível. */
    fun internetEvents(): Flow<RadioEvent> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    trySend(RadioEvent.InternetAvailable(viaPeer = false))
                }
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
}
