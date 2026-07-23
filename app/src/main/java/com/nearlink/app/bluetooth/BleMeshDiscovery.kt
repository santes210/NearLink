package com.nearlink.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Descubrimiento automático por BLE (Bluetooth Low Energy).
 *
 * Cada dispositivo ANUNCIA (advertising) un UUID de NearLink y ESCANEA en busca de
 * otros que lo anuncien. Así se descubren automáticamente, SIN emparejar a mano.
 * El transporte real de mensajes va por RFCOMM inseguro (ver BluetoothConnectionManager),
 * que también conecta sin emparejamiento.
 *
 * Esto es lo que hace que la malla "funcione sola": dos dispositivos con la app
 * abierta y cerca se detectan y se conectan automáticamente.
 */
@SuppressLint("MissingPermission")
class BleMeshDiscovery(context: Context) {

    companion object {
        // Marca de presencia NearLink por BLE (distinto del UUID de servicio Classic).
        val NEARLINK_BLE_UUID: UUID = UUID.fromString("7f57b1d0-1b2c-4d5e-9a01-b82a3c4d5e6f")
    }

    private val appContext: Context = context.applicationContext
    private val adapter: BluetoothAdapter? get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val advertiser get() = adapter?.bluetoothLeAdvertiser
    private val scanner get() = adapter?.bluetoothLeScanner

    private val _discovered = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discovered: StateFlow<List<BluetoothDevice>> = _discovered.asStateFlow()

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        startAdvertising()
        startScanning()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private fun startAdvertising() {
        val a = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(NEARLINK_BLE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        runCatching { a.startAdvertising(settings, data, advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private fun startScanning() {
        val s = scanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { s.startScan(emptyList(), settings, scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            // Filtrar resultados que anuncian el UUID de NearLink.
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: return
            if (NEARLINK_BLE_UUID !in uuids) return
            val list = _discovered.value.toMutableList()
            if (list.none { it.address == dev.address }) {
                list.add(dev)
                _discovered.value = list
            }
        }
    }
}
