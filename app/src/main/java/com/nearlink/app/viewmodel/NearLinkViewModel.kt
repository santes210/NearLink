package com.nearlink.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.bluetooth.BluetoothConnectionManager
import com.nearlink.app.data.local.NearLinkDatabase
import com.nearlink.app.data.repository.MessageRepositoryImpl
import com.nearlink.app.data.security.EncryptionManager
import com.nearlink.app.data.security.IdentityManager
import com.nearlink.app.data.security.SecureMessenger
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.PeerDevice
import com.nearlink.app.service.NearLinkForegroundService
import com.nearlink.app.transport.WireMessage
import com.nearlink.app.wifidirect.FileTransferService
import com.nearlink.app.wifidirect.WifiDirectManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.ServerSocket

enum class Screen {
    HOME, DISCOVERY, CHAT, SETTINGS
}

class NearLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val identity = IdentityManager(application)
    private val encryption = EncryptionManager()
    private val database = NearLinkDatabase.getDatabase(application)
    private val messageRepository = MessageRepositoryImpl(database.messageDao(), encryption)

    private val pinPrefs = application.getSharedPreferences("nearlink_pairing", Context.MODE_PRIVATE)
    private val secureMessenger = SecureMessenger(identity) { _temporaryPin.value }

    private val bluetooth = BluetoothConnectionManager(application)
    private val wifi = WifiDirectManager(application)

    // ---------------- Estado de UI ----------------
    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _selectedPeer = MutableStateFlow<PeerDevice?>(null)
    val selectedPeer: StateFlow<PeerDevice?> = _selectedPeer.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _temporaryPin = MutableStateFlow(pinPrefs.getString("pin", "4821") ?: "4821")
    val temporaryPin: StateFlow<String> = _temporaryPin.asStateFlow()

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _identityFingerprint = MutableStateFlow(identity.fingerprint)
    val identityFingerprint: StateFlow<String> = _identityFingerprint.asStateFlow()

    private val _peerFingerprint = MutableStateFlow<String?>(null)
    val peerFingerprint: StateFlow<String?> = _peerFingerprint.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _transferStatus = MutableStateFlow<String?>(null)
    val transferStatus: StateFlow<String?> = _transferStatus.asStateFlow()

    private var activePeerId: String? = null
    private var wifiReceiveServer: ServerSocket? = null
    private val observedPeers = mutableSetOf<String>()

    init {
        // Servicio en primer plano (mantén la escucha Bluetooth viva)
        runCatching {
            val serviceIntent = Intent(application, NearLinkForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                application.startForegroundService(serviceIntent)
            else
                application.startService(serviceIntent)
        }

        bluetooth.startServer()
        observeBluetooth()
        observeSecureLayer()
        observeWifi()
        startTtlCleanup()
    }

    // ---------------- Observación del Bluetooth ----------------
    private fun observeBluetooth() {
        viewModelScope.launch {
            bluetooth.state.collect { st ->
                val mapped = when (st) {
                    BluetoothConnectionManager.State.IDLE,
                    BluetoothConnectionManager.State.LISTENING -> ConnectionState.DISCONNECTED
                    BluetoothConnectionManager.State.DISCOVERING -> ConnectionState.SCANNING
                    BluetoothConnectionManager.State.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothConnectionManager.State.CONNECTED -> ConnectionState.CONNECTED
                }
                _connectionState.value = mapped
                if (st == BluetoothConnectionManager.State.CONNECTED) {
                    // Inicia el handshake seguro enviando nuestra llave pública
                    bluetooth.send(secureMessenger.beginHandshake())
                }
                refreshSelectedPeerState(mapped)
            }
        }
        viewModelScope.launch {
            bluetooth.discoveredDevices.collectLatest { list ->
                _peers.value = list.map { d ->
                    PeerDevice(
                        id = d.address,
                        name = d.name,
                        address = d.address,
                        rssi = d.rssi,
                        connectionState = if (d.address == activePeerId && bluetooth.state.value == BluetoothConnectionManager.State.CONNECTED)
                            ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                        pin = _temporaryPin.value,
                        publicKeyFingerprint = if (d.address == activePeerId) _peerFingerprint.value ?: "—" else "—"
                    )
                }
            }
        }
        viewModelScope.launch {
            bluetooth.incoming.collect { msg ->
                when (msg) {
                    is WireMessage.KeyExchange -> secureMessenger.handleIncoming(msg)
                    is WireMessage.Encrypted -> {
                        secureMessenger.handleIncoming(msg)
                        bluetooth.send(WireMessage.Ack(msg.id)) // confirmamos recepción
                    }
                    is WireMessage.Ack -> markDelivered(msg.id)
                }
            }
        }
        viewModelScope.launch {
            bluetooth.errors.collect { _errorMessage.value = it }
        }
    }

    private fun observeSecureLayer() {
        viewModelScope.launch {
            secureMessenger.decrypted.collect { app ->
                val peerId = activePeerId ?: return@collect
                val incoming = Message(
                    id = app.id,
                    senderId = peerId,
                    recipientId = "me",
                    content = app.content,
                    timestamp = app.timestamp,
                    type = parseType(app.kind),
                    status = MessageStatus.DELIVERED,
                    isEncrypted = true,
                    fileName = app.fileName,
                    ttlSeconds = app.ttlSeconds,
                    isSos = app.kind == "SOS"
                )
                messageRepository.sendMessage(incoming)
                refreshMessages(peerId)
            }
        }
    }

    private fun observeWifi() {
        viewModelScope.launch {
            wifi.connection.collect { info ->
                if (info != null && info.isGroupOwner) startWifiReceiveLoop()
            }
        }
    }

    private fun startWifiReceiveLoop() {
        viewModelScope.launch {
            if (wifiReceiveServer != null) return@launch
            try {
                val server = ServerSocket(FileTransferService.PORT)
                wifiReceiveServer = server
                while (true) {
                    val received = FileTransferService.receive(server, secureMessenger.fileKey()) ?: continue
                    val peerId = activePeerId ?: "wifi"
                    val msg = Message(
                        id = "f" + System.currentTimeMillis(),
                        senderId = peerId,
                        recipientId = "me",
                        content = "Archivo recibido por Wi-Fi Direct",
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.FILE,
                        status = MessageStatus.DELIVERED,
                        isEncrypted = true,
                        fileName = received.name,
                        ttlSeconds = 0,
                        isSos = false
                    )
                    messageRepository.sendMessage(msg)
                    refreshMessages(peerId)
                    _transferStatus.value = "Archivo recibido: ${received.name} (${received.data.size} bytes)"
                }
            } catch (e: Exception) {
                _transferStatus.value = "Recepción Wi-Fi Direct detenida"
            } finally {
                wifiReceiveServer = null
            }
        }
    }

    private fun startTtlCleanup() {
        viewModelScope.launch {
            while (true) {
                delay(5000)
                messageRepository.cleanupExpiredMessages()
                activePeerId?.let { refreshMessages(it) }
            }
        }
    }

    // ---------------- Acciones de UI ----------------
    fun navigateTo(screen: Screen) { _currentScreen.value = screen }

    fun selectPeer(peer: PeerDevice) {
        activePeerId = peer.id
        _selectedPeer.value = peer
        navigateTo(Screen.CHAT)
        observeMessages(peer.id)
        secureMessenger.reset()
        bluetooth.connect(toRawDevice(peer) ?: return)
    }

    fun startScan() {
        wifi.start()
        bluetooth.startDiscovery()
    }

    fun stopScan() {
        bluetooth.cancelDiscovery()
    }

    fun sendMessage(
        content: String,
        type: MessageType = MessageType.TEXT,
        fileName: String? = null,
        ttlSeconds: Int = 0,
        isSos: Boolean = false
    ) {
        val peer = _selectedPeer.value ?: return
        val msgId = "m" + System.currentTimeMillis()
        viewModelScope.launch {
            val message = Message(
                id = msgId,
                senderId = "me",
                recipientId = peer.id,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = type,
                status = MessageStatus.SENDING,
                isEncrypted = true,
                fileName = fileName,
                ttlSeconds = ttlSeconds,
                isSos = isSos
            )
            messageRepository.sendMessage(message)
            refreshMessages(peer.id)

            val encrypted = secureMessenger.encryptAppMessage(type.name, content, msgId, ttlSeconds, fileName)
            if (encrypted != null && bluetooth.send(encrypted)) {
                messageRepository.updateStatus(msgId, MessageStatus.SENT.name)
            } else {
                messageRepository.updateStatus(msgId, MessageStatus.FAILED.name)
                _errorMessage.value = "No hay canal seguro conectado. Conecta un par primero."
            }
            refreshMessages(peer.id)
        }
    }

    fun sendSosAlert() {
        sendMessage("¡ALERTA SOS! Nodo en situación crítica de emergencia.", MessageType.SOS, null, 0, true)
    }

    fun toggleVoiceRecording() {
        _isRecordingVoice.value = !_isRecordingVoice.value
        if (!_isRecordingVoice.value) {
            sendMessage("Nota de voz cifrada (0:04)", MessageType.VOICE, "voicenote.mp3", 0, false)
        }
    }

    fun sendFile(fileName: String) {
        sendMessage("Archivo adjunto: $fileName", MessageType.FILE, fileName, 0, false)
    }

    /** Si hay grupo Wi-Fi Direct activo, transfiere por ahí (más rápido); si no, por Bluetooth. */
    fun sendWifiDirectFile(fileName: String) {
        val conn = wifi.connection.value
        viewModelScope.launch {
            if (conn != null && !conn.isGroupOwner && conn.groupOwnerAddress != null) {
                _transferStatus.value = "Transfiriendo '$fileName' por Wi-Fi Direct…"
                val payload = ("Contenido de ejemplo de $fileName generado por NearLink.\n" +
                    "X25519 + AES-256-GCM — transferencia P2P segura.").toByteArray()
                val ok = FileTransferService.send(
                    conn.groupOwnerAddress, secureMessenger.fileKey(), fileName, payload
                )
                _transferStatus.value = if (ok) "✓ '$fileName' enviado por Wi-Fi Direct (${payload.size} bytes)"
                else "✗ Falló la transferencia Wi-Fi Direct; enviando por Bluetooth."
                if (!ok) sendFile(fileName)
            } else {
                _transferStatus.value = "Sin grupo Wi-Fi Direct — enviando por Bluetooth."
                sendFile(fileName)
            }
        }
    }

    fun updatePin(newPin: String) {
        _temporaryPin.value = newPin
        pinPrefs.edit().putString("pin", newPin).apply()
        encryption.deriveKeyFromPin(newPin)
    }

    fun regeneratePin() {
        updatePin((1000..9999).random().toString())
    }

    fun clearError() { _errorMessage.value = null }
    fun clearTransferStatus() { _transferStatus.value = null }

    // ---------------- Helpers ----------------
    private fun observeMessages(peerId: String) {
        if (!observedPeers.add(peerId)) return // ya hay un colector reactivo para este par
        viewModelScope.launch {
            messageRepository.getMessagesForPeer(peerId).collect { list ->
                _messages.value = _messages.value + (peerId to list)
            }
        }
    }

    private suspend fun refreshMessages(peerId: String) {
        // Room es reactivo: el colector abierto en observeMessages mantiene el mapa al día.
        if (peerId !in observedPeers) observeMessages(peerId)
    }

    private suspend fun markDelivered(messageId: String) {
        messageRepository.updateStatus(messageId, MessageStatus.DELIVERED.name)
        delay(800)
        messageRepository.updateStatus(messageId, MessageStatus.READ.name)
    }

    private fun refreshSelectedPeerState(state: ConnectionState) {
        val cur = _selectedPeer.value ?: return
        _selectedPeer.value = cur.copy(
            connectionState = state,
            publicKeyFingerprint = _peerFingerprint.value ?: cur.publicKeyFingerprint
        )
    }

    private fun parseType(kind: String): MessageType = try {
        MessageType.valueOf(kind)
    } catch (e: Exception) {
        MessageType.TEXT
    }

    private fun toRawDevice(peer: PeerDevice): android.bluetooth.BluetoothDevice? {
        val adapter = bluetooth.adapter ?: return null
        return runCatching { adapter.getRemoteDevice(peer.address) }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        bluetooth.shutdown()
        wifi.stop()
        runCatching { wifiReceiveServer?.close() }
    }
}
