package com.nearlink.app.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestión de Wi-Fi Direct (Wi-Fi P2P) para transferencia de archivos grandes.
 *
 * Flujo:
 *   discoverPeers() -> lista de pares (state [peers])
 *   connect(device)  -> [connectionInfo] con la IP del group owner
 *   El group owner abre un ServerSocket; el cliente conecta a esa IP.
 *   La transferencia real la hace [FileTransferService].
 */
class WifiDirectManager(private val context: Context) {

    data class Peer(val deviceAddress: String, val name: String, val isGroupOwner: Boolean)
    data class ConnInfo(val isGroupOwner: Boolean, val groupOwnerAddress: String?)

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _connection = MutableStateFlow<ConnInfo?>(null)
    val connection: StateFlow<ConnInfo?> = _connection.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _enabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed) {
                            _connection.value = ConnInfo(info.isGroupOwner, info.groupOwnerAddress?.hostAddress)
                        } else if (info != null && !info.groupFormed) {
                            _connection.value = null
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (manager == null || channel == null) {
            _errors.value = "Wi-Fi Direct no disponible en este dispositivo"
            return
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            runCatching {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
        }
        discoverPeers()
    }

    fun discoverPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!hasLocationPermission()) {
            _errors.value = "Falta permiso de ubicación para Wi-Fi Direct"
            return
        }
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                _errors.value = "No se pudo iniciar descubrimiento Wi-Fi Direct (código $reason)"
            }
        })
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peerList ->
            val list = peerList.deviceList.map { Peer(it.deviceAddress, it.deviceName, it.isGroupOwner) }
            _peers.value = list
        }
    }

    fun connect(deviceAddress: String) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                _errors.value = "Conexión Wi-Fi Direct fallida (código $reason)"
            }
        })
    }

    fun disconnect() {
        val m = manager ?: return
        val ch = channel ?: return
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
        _connection.value = null
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            return fine || nearby
        }
        return fine
    }
}
