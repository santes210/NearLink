package com.nearlink.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nearlink.app.transport.MessageFramer
import com.nearlink.app.transport.WireMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Transporte real por Bluetooth Classic (RFCOMM / SPP) con SOPORTE DE MÚLTIPLES
 * CONEXIONES SIMULTÁNEAS, para que un nodo actúe como repetidor (relay) entre
 * varios pares a la vez (clave para la malla multi-salto).
 *
 *  - Descubrimiento: primero dispositivos EMPAREJADOS y luego ACTION_FOUND.
 *  - AcceptThread en bucle: acepta cuantos clientes quieran conectarse.
 *  - connect(): conexión saliente a un par.
 *  - Cada conexión tiene su ConnectedThread de lectura/escritura enmarcada.
 *  - send() difunde a todos; sendToAllExcept() difunde a todos salvo el origen
 *    (para el flooding del relay sin eco).
 *
 * NO cifra nada: solo transporta. La criptografía va en SecureMessenger.
 *
 * Nota: dentro de los inner classes que heredan de [Thread], hay que cualificar el
 * enum propio como [BluetoothConnectionManager.State] porque `State` por sí solo
 * resuelve a `java.lang.Thread.State` (sombreado por herencia).
 */
@SuppressLint("MissingPermission")
class BluetoothConnectionManager(private val context: Context) {

    enum class State { IDLE, DISCOVERING, LISTENING, CONNECTING, CONNECTED }

    /** Mensaje entrante con la conexión de origen (para evitar eco en el relay). */
    data class Incoming(val sourceKey: String, val message: WireMessage)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        const val SERVICE_NAME = "NearLink"
    }

    private val sysBt = context.getSystemService(SystemBluetoothManager::class.java)
    val adapter: BluetoothAdapter? get() = sysBt?.adapter

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _connected = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connected.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val connections = ConcurrentHashMap<String, ConnectedThread>()
    private var acceptThread: AcceptThread? = null
    @Volatile private var receiverRegistered = false

    data class DiscoveredDevice(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int)

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    private fun nameOf(dev: BluetoothDevice): String =
        runCatching { @Suppress("MissingPermission") dev.name }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Dispositivo …${dev.address.takeLast(5)}"

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // ---------------- Descubrimiento ----------------
    private val foundReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (dev != null) {
                        val list = _devices.value.toMutableList()
                        if (list.none { it.address == dev.address }) {
                            list.add(DiscoveredDevice(dev, nameOf(dev), dev.address, rssi))
                            _devices.value = list
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_state.value == State.DISCOVERING) _state.value = State.LISTENING
                }
            }
        }
    }

    fun startDiscovery() {
        val a = adapter ?: run { emitError("Bluetooth no disponible"); return }
        if (!hasScanPermission()) { emitError("Falta permiso de Bluetooth/Ubicación para escanear"); return }
        val seed = runCatching { a.bondedDevices }
            .getOrDefault(emptySet<BluetoothDevice>())
            .map { DiscoveredDevice(it, nameOf(it), it.address, 0) }
        registerReceiver()
        _devices.value = seed
        if (a.isDiscovering) a.cancelDiscovery()
        _state.value = State.DISCOVERING
        runCatching { a.startDiscovery() }
        startServer()
    }

    fun cancelDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
        unregisterReceiver()
        if (_state.value == State.DISCOVERING) _state.value = State.LISTENING
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(context, foundReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            emitError("No se pudo registrar el receptor de descubrimiento")
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(foundReceiver) }
        receiverRegistered = false
    }

    // ---------------- Servidor / Cliente ----------------
    fun startServer() {
        if (!hasConnectPermission()) return
        if (acceptThread?.isAlive == true) return
        acceptThread = AcceptThread().also { it.start() }
        if (_state.value == State.IDLE) _state.value = State.LISTENING
    }

    fun connect(device: BluetoothDevice) {
        val a = adapter ?: return
        if (!hasConnectPermission()) { emitError("Falta permiso BLUETOOTH_CONNECT"); return }
        if (connections.containsKey(device.address)) return // ya conectado
        a.cancelDiscovery()
        _state.value = State.CONNECTING
        ConnectThread(device).start()
    }

    /** Difunde a todas las conexiones activas. */
    fun send(msg: WireMessage): Boolean {
        var any = false
        for (t in connections.values) if (t.write(msg)) any = true
        return any
    }

    /** Difunde a todas las conexiones excepto la de origen (flooding sin eco). */
    fun sendToAllExcept(sourceKey: String, msg: WireMessage) {
        for ((key, t) in connections) if (key != sourceKey) t.write(msg)
    }

    private fun onConnected(socket: BluetoothSocket, key: String) {
        connections[key]?.cancel()
        val t = ConnectedThread(socket, key)
        connections[key] = t
        t.start()
        refreshConnected()
        if (_state.value != State.CONNECTED) _state.value = State.CONNECTED
    }

    private fun refreshConnected() {
        _connected.value = connections.keys.toList().sorted()
    }

    fun shutdown() {
        unregisterReceiver()
        runCatching { adapter?.cancelDiscovery() }
        for (t in connections.values) t.cancel()
        connections.clear()
        refreshConnected()
        acceptThread?.cancel(); acceptThread = null
        _state.value = State.IDLE
    }

    private fun emitError(msg: String) { _errors.tryEmit(msg) }

    private inner class AcceptThread : Thread() {
        private var serverSocket: BluetoothServerSocket? = null
        init {
            serverSocket = try {
                adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            } catch (e: IOException) { null }
        }
        override fun run() {
            while (true) {
                val socket = try { serverSocket?.accept() } catch (e: IOException) { break }
                if (socket != null) {
                    val key = runCatching {
                        @Suppress("MissingPermission") socket.remoteDevice?.address
                    }.getOrNull()
                    if (key != null) onConnected(socket, key)
                    else runCatching { socket.close() }
                }
            }
            runCatching { serverSocket?.close() }
        }
        fun cancel() { runCatching { serverSocket?.close() } }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? = try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (e: IOException) { null }
        override fun run() {
            val s = socket ?: run {
                emitError("No se pudo crear el socket Bluetooth")
                backToListening(); return
            }
            try {
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                emitError("Conexión Bluetooth fallida: ${e.message}")
                backToListening(); return
            }
            onConnected(s, device.address)
        }
        private fun backToListening() {
            if (connections.isEmpty() && _state.value != BluetoothConnectionManager.State.DISCOVERING)
                _state.value = BluetoothConnectionManager.State.LISTENING
            startServer()
        }
    }

    private inner class ConnectedThread(socket: BluetoothSocket, val key: String) : Thread() {
        private val inStream = DataInputStream(socket.inputStream)
        private val outStream = DataOutputStream(socket.outputStream)
        private val lock = Any()
        @Volatile private var alive = true

        override fun run() {
            try {
                while (alive) {
                    val msg = MessageFramer.read(inStream) ?: break
                    _incoming.tryEmit(Incoming(key, msg))
                }
            } catch (e: IOException) {
                // desconexión
            } finally {
                handleDisconnect()
            }
        }

        fun write(msg: WireMessage): Boolean = synchronized(lock) {
            if (!alive) return false
            try { MessageFramer.write(outStream, msg); true }
            catch (e: IOException) { alive = false; false }
        }

        private fun handleDisconnect() {
            alive = false
            connections.remove(key)
            runCatching { inStream.close() }
            runCatching { outStream.close() }
            refreshConnected()
            if (connections.isEmpty() && _state.value == BluetoothConnectionManager.State.CONNECTED)
                _state.value = BluetoothConnectionManager.State.LISTENING
            startServer()
        }

        fun cancel() {
            alive = false
            runCatching { inStream.close() }
            runCatching { outStream.close() }
        }
    }
}
