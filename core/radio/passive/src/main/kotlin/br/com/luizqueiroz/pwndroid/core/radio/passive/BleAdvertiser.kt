package br.com.luizqueiroz.pwndroid.core.radio.passive

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid

/**
 * Advertise BLE do pwndroid (issue #10, modo opcional — default off):
 * anuncia o service UUID do pwndroid com service data
 * `pwndroid://<fingerprint>` para que outros pwndroids vejam este device.
 */
class BleAdvertiser(private val context: Context) {

    /** Fábrica de BluetoothLeAdvertiser (substituível em teste). */
    fun interface AdvertiserProvider {
        fun get(context: Context): android.bluetooth.le.BluetoothLeAdvertiser?
    }

    private var callback: AdvertiseCallback? = null

    /** Inicia o advertise; no-op se o rádio BLE não suportar advertise. */
    fun start(
        fingerprint: String,
        advertiserProvider: AdvertiserProvider = AdvertiserProvider { c ->
            (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeAdvertiser
        },
    ) {
        stop()
        val advertiser = advertiserProvider.get(context) ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BlePeerScanner.PWNDROID_SERVICE_UUID))
            .addServiceData(
                ParcelUuid(BlePeerScanner.PWNDROID_SERVICE_UUID),
                (BlePeerScanner.ADVERTISEMENT_PREFIX + fingerprint).toByteArray(Charsets.UTF_8),
            )
            .build()
        val cb = object : AdvertiseCallback() {}
        callback = cb
        runCatching { advertiser.startAdvertising(settings, data, cb) }
    }

    /** Para o advertise (idempotente). */
    fun stop() {
        callback?.let { cb ->
            runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            }
        }
        callback = null
    }
}
