package com.nearlink.app.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Anuncio BLE en segundo plano: permite que otros nodos NearLink nos descubran
 * sin interaccion del usuario (baliza silenciosa).
 *
 * Requiere BLUETOOTH_ADVERTISE (Android 12+) o BLUETOOTH_ADMIN (versiones
 * anteriores). La comprobacion de permisos se hace en la capa de UI antes de
 * arrancar el servicio.
 */
@SuppressLint("MissingPermission")
class BleAdvertiser(context: Context) {

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser
    private var callback: AdvertiseCallback? = null

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val isSupported: Boolean get() = adapter?.isMultipleAdvertisementSupported ?: false

    fun start(nodeId: ByteArray, relay: Boolean): Boolean {
        if (adapter?.isEnabled != true) {
            _lastError.value = "Bluetooth apagado"
            return false
        }
        val leAdvertiser = advertiser ?: run {
            _lastError.value = "El dispositivo no soporta anuncio BLE"
            return false
        }
        stop()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val payload = ByteArray(nodeId.size + 1)
        System.arraycopy(nodeId, 0, payload, 0, nodeId.size.coerceAtMost(4))
        payload[payload.lastIndex] = if (relay) 1 else 0

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(NearLinkBle.SERVICE_UUID))
            .addServiceData(ParcelUuid(NearLinkBle.SERVICE_UUID), payload)
            .build()

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                _isAdvertising.value = true
                _lastError.value = null
            }

            override fun onStartFailure(errorCode: Int) {
                _isAdvertising.value = false
                _lastError.value = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Datos de anuncio demasiado grandes"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Demasiados anuncios activos"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "El anuncio ya estaba iniciado"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Error interno de Bluetooth"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Funcion no soportada"
                    else -> "No se pudo iniciar el anuncio ($errorCode)"
                }
            }
        }
        callback = advertiseCallback
        leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        return true
    }

    fun stop() {
        callback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        callback = null
        _isAdvertising.value = false
    }
}
