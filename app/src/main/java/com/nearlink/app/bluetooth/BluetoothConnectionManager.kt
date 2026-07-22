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
import java.util.concurrent.atomic.AtomicReference

/**
 * Transporte real por Bluetooth Classic (RFCOMM / SPP). Maneja:
 *  - Descubrimiento de dispositivos: primero los ya EMPAREJADOS (bonded, lo más
 *    confiable en Android 12+) y luego los visibles por ACTION_FOUND (con RSSI).
 *  - Hilo servidor (accept) que escucha conexiones entrantes.
 *  - Hilo cliente que conecta a un par.
 *  - Hilo conectado con lectura/escritura enmarcada de [WireMessage].
 *
 * NO cifra nada: solo transporta el protocolo. La criptografía va en SecureMessenger.
 *
 * Nota: dentro de los inner classes que heredan de [Thread], hay que cualificar el enum
 * propio como [BluetoothConnectionManager.State] porque `State` por sí solo resuelve a
 * `java.lang.Thread.State` (sombreado por herencia).
 */
@SuppressLint("MissingPermission")
class BluetoothConnectionManager(private val context: Context) {

    enum class State { IDLE, DISCOVERING, LISTENING, CONNECTING, CONNECTED }

    companion object {
        // UUID fijo (SPP). Debe ser IGUAL en ambos dispositivos para que RFCOMM conecte.
        val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        const val SERVICE_NAME = "NearLink"
    }

    private val sysBt = context.getSystemService(SystemBluetoothManager::class.java)
    val adapter: BluetoothAdapter? get() = sysBt?.adapter

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _incoming = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<WireMessage> = _incoming.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val connectedThread = AtomicReference<ConnectedThread?>(null)
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    @Volatile private var receiverRegistered = false

    /** Un dispositivo descubierto con su nombre, MAC y nivel de señal. */
    data class DiscoveredDevice(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int)

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    /** Nombre legible; muchos dispositivos descubiertos vienen sin nombre en Android 12+. */
    private fun nameOf(dev: BluetoothDevice): String =
        runCatching { @Suppress("MissingPermission") dev.name }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Dispositivo …${dev.address.takeLast(5)}"

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_SCAN)
        else
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        else true
    }

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // ---------------- Descubrimiento ----------------
    private val foundReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (dev != null) {
                        val name = nameOf(dev)
                        val list = _devices.value.toMutableList()
                        if (list.none { it.address == dev.address }) {
                            list.add(DiscoveredDevice(dev, name, dev.address, rssi))
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
        val a = adapter ?: run { emitError("Bluetooth no disponible en este dispositivo"); return }
        if (!hasScanPermission()) { emitError("Falta permiso de Bluetooth/Ubicación para escanear"); return }
        // Sembrar con los dispositivos ya EMPAREJADOS (la vía más confiable en Android 12+).
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
        a.cancelDiscovery()
        connectThread?.cancel()
        _state.value = State.CONNECTING
        connectThread = ConnectThread(device).also { it.start() }
    }

    fun send(msg: WireMessage): Boolean = connectedThread.get()?.write(msg) ?: false

    fun disconnect() {
        connectedThread.getAndSet(null)?.cancel()
        connectThread?.cancel(); connectThread = null
        if (_state.value == State.CONNECTED) _state.value = State.LISTENING
        startServer()
    }

    fun shutdown() {
        unregisterReceiver()
        runCatching { adapter?.cancelDiscovery() }
        connectedThread.getAndSet(null)?.cancel()
        connectThread?.cancel(); connectThread = null
        acceptThread?.cancel(); acceptThread = null
        _state.value = State.IDLE
    }

    private fun onConnected(socket: BluetoothSocket) {
        connectThread = null
        connectedThread.getAndSet(null)?.cancel()
        val t = ConnectedThread(socket)
        connectedThread.set(t)
        t.start()
        _state.value = State.CONNECTED
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
                    onConnected(socket)
                    break
                }
            }
            runCatching { serverSocket?.close() }
        }
        fun cancel() { runCatching { serverSocket?.close() } }
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? = try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (e: IOException) { null }
        override fun run() {
            val s = socket ?: run {
                emitError("No se pudo crear el socket Bluetooth")
                _state.value = BluetoothConnectionManager.State.LISTENING
                return
            }
            try {
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                emitError("Conexión Bluetooth fallida: ${e.message}")
                _state.value = BluetoothConnectionManager.State.LISTENING
                startServer()
                return
            }
            onConnected(s)
        }
        fun cancel() { runCatching { socket?.close() } }
    }

    private inner class ConnectedThread(socket: BluetoothSocket) : Thread() {
        private val inStream = DataInputStream(socket.inputStream)
        private val outStream = DataOutputStream(socket.outputStream)
        private val lock = Any()
        @Volatile private var alive = true

        override fun run() {
            try {
                while (alive) {
                    val msg = MessageFramer.read(inStream) ?: break
                    _incoming.tryEmit(msg)
                }
            } catch (e: IOException) {
                // desconexión
            } finally {
                handleDisconnect()
            }
        }

        fun write(msg: WireMessage): Boolean = synchronized(lock) {
            if (!alive) return false
            try {
                MessageFramer.write(outStream, msg); true
            } catch (e: IOException) {
                alive = false; false
            }
        }

        private fun handleDisconnect() {
            alive = false
            connectedThread.compareAndSet(this, null)
            runCatching { inStream.close() }
            runCatching { outStream.close() }
            if (_state.value == BluetoothConnectionManager.State.CONNECTED) {
                _state.value = BluetoothConnectionManager.State.LISTENING
                startServer()
            }
        }

        fun cancel() {
            alive = false
            runCatching { inStream.close() }
            runCatching { outStream.close() }
        }
    }
}
