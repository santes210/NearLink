package com.nearlink.app.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Un nodo visto por el radar. */
data class DiscoveredNode(
    val address: String,
    val name: String?,
    val rssi: Int,
    val relay: Boolean,
    val nodeId: String,
    val seenAt: Long = System.currentTimeMillis(),
)

/**
 * Radar BLE: escanea solo el servicio NearLink para no gastar bateria en
 * dispositivos irrelevantes.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private var callback: ScanCallback? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun start(onResult: (DiscoveredNode) -> Unit): Boolean {
        if (adapter?.isEnabled != true) {
            _lastError.value = "Bluetooth apagado"
            return false
        }
        val leScanner = scanner ?: run {
            _lastError.value = "El dispositivo no soporta escaneo BLE"
            return false
        }
        // Android 6..11 (API < 31) exigen que la localizacion este ACTIVA
        // ademas del permiso, o el escaneo devuelve silenciosamente nada.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            _lastError.value = "Activa la ubicacion del sistema para escanear"
            return false
        }
        stop()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NearLinkBle.SERVICE_UUID))
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.serviceData?.get(ParcelUuid(NearLinkBle.SERVICE_UUID))
                val relay = data?.lastOrNull() == 1.toByte()
                val nodeId = data?.take(4)?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.orEmpty()
                onResult(
                    DiscoveredNode(
                        address = result.device.address,
                        name = runCatching { result.device.name }.getOrNull(),
                        rssi = result.rssi,
                        relay = relay,
                        nodeId = nodeId,
                    ),
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                _lastError.value = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "El escaneo ya estaba iniciado"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Escaneo no soportado"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                        "Sin permisos o sin ubicacion activa (escaneo BLE)"
                    SCAN_FAILED_INTERNAL_ERROR -> "Error interno de Bluetooth"
                    else -> "Escaneo fallido ($errorCode)"
                }
            }
        }
        callback = scanCallback
        leScanner.startScan(listOf(filter), settings, scanCallback)
        _isScanning.value = true
        _lastError.value = null
        return true
    }

    fun stop() {
        callback?.let { runCatching { scanner?.stopScan(it) } }
        callback = null
        _isScanning.value = false
    }

    /** True si la localizacion del sistema esta activa (solo relevante < API 31). */
    private fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = appContext.getSystemService(LocationManager::class.java)
            return manager?.isLocationEnabled == true
        }
        // API 26..27: no existe isLocationEnabled; se asume activa y el fallo
        // de escaneo se reportaria por onScanFailed.
        return true
    }
}
