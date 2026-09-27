package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent

/**
 * Tradução de resultados de scan do Android em eventos do domínio.
 * Pura (sem Context) — testável em JVM puro.
 */
object ScanResultMapper {

    /**
     * Converte [ScanResult]s em [RadioEvent.ApSeen].
     * Scan passivo não vê clientes: nenhum [RadioEvent.StationSeen].
     */
    fun toApSeen(results: List<ScanResult>): List<RadioEvent.ApSeen> = results.map { r ->
        RadioEvent.ApSeen(
            bssid = r.BSSID.lowercase(),
            essid = r.SSID.takeIf { it.isNotBlank() },
            channel = frequencyToChannel(r.frequency),
            rssi = r.level,
        )
    }

    /** Frequência (MHz) → canal 2.4/5 GHz. */
    fun frequencyToChannel(frequency: Int): Int = when {
        frequency <= 0 -> 0
        frequency == 2484 -> 14
        frequency < 2484 -> (frequency - 2407) / 5
        frequency in 5160..5885 -> (frequency - 5000) / 5
        else -> 0
    }
}

/** Estratégia de scan Wi-Fi (WifiManager throttled; WifiScanner no FGS). */
interface ScanSource {
    /** Dispara um scan; devolve os resultados atuais (pode ser vazio). */
    fun scan(): List<ScanResult>
}
