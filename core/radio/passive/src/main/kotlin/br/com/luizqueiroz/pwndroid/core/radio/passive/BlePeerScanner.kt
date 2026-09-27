package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import br.com.luizqueiroz.pwndroid.core.model.RadioEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scanner BLE para o Épico 3: detecta outros pwndroids/pwnagotchis por
 * advertise próprio e registra sightings BLE genéricos (wardriving).
 *
 * O anúncio de peer usa service UUID próprio com service data
 * `pwndroid://<fingerprint-curto>`; scan em [ScanSettings.SCAN_MODE_LOW_POWER]
 * para poupar bateria — para quando a sessão termina (o dono do flow cancela).
 */
class BlePeerScanner(private val context: Context) {

    companion object {
        /** UUID do service GATT do pwndroid (v1, fixo — peers se reconhecem por ele). */
        val PWNDROID_SERVICE_UUID: java.util.UUID =
            java.util.UUID.fromString("b46a1b0f-8f5a-4c9e-9d3a-2f0e1d7c6a55")

        /** Prefixo do service data no anúncio de peer. */
        const val ADVERTISEMENT_PREFIX = "pwndroid://"

        /**
         * Parse puro (JVM-testável) de um anúncio BLE: [RadioEvent.PeerSeen]
         * quando o service data carrega `pwndroid://<fingerprint>`.
         */
        fun parseAdvertisement(serviceData: ByteArray?, deviceName: String?): RadioEvent? {
            val text = serviceData?.toString(Charsets.UTF_8) ?: return null
            if (!text.startsWith(ADVERTISEMENT_PREFIX)) return null
            val fingerprint = text.removePrefix(ADVERTISEMENT_PREFIX)
            if (fingerprint.isBlank()) return null
            return RadioEvent.PeerSeen(fingerprint = fingerprint, name = deviceName)
        }
    }

    /** Fábrica de BluetoothLeScanner (substituível em teste). */
    fun interface LeScannerProvider {
        fun get(context: Context): android.bluetooth.le.BluetoothLeScanner?
    }

    /** Checagem de permissão BLE (substituível em teste). */
    fun interface PermissionCheck {
        fun hasScanPermission(): Boolean
    }

    /**
     * Fluxo de anúncios de peer. Emite [RadioEvent.PeerSeen] para cada
     * advertise pwndroid detectado; fecha se não houver scanner/permissão
     * (BLE ausente não é erro — é device sem rádio BLE).
     */
    fun scan(
        scannerProvider: LeScannerProvider = LeScannerProvider { c ->
            (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner
        },
        permissionCheck: PermissionCheck = PermissionCheck { true },
    ): Flow<RadioEvent> = callbackFlow {
        val scanner = scannerProvider.get(context)
        if (scanner == null || !permissionCheck.hasScanPermission()) {
            close()
            return@callbackFlow
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(ParcelUuid(PWNDROID_SERVICE_UUID))
                parseAdvertisement(data, result.scanRecord?.deviceName)?.let { trySend(it) }
            }
        }
        val ok = runCatching {
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(PWNDROID_SERVICE_UUID))
                        .build(),
                ),
                settings,
                callback,
            )
        }.isSuccess
        if (!ok) {
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
