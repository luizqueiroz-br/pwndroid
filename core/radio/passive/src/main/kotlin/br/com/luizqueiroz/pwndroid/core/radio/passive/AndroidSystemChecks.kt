package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager

/**
 * Checagens reais do sistema: localização ligada e Wi-Fi ligado.
 * Scan Wi-Fi em API 26+ exige localização ativa (política do SO).
 */
class AndroidSystemChecks(private val context: Context) : SystemChecks {

    override fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { manager.isLocationEnabled }.getOrDefault(false)
    }

    override fun isWifiEnabled(): Boolean {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return runCatching { wifi.isWifiEnabled }.getOrDefault(false)
    }
}
