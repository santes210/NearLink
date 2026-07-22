package com.nearlink.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.bluetooth.BluetoothConnectionManager
import com.nearlink.app.data.audio.AudioRecorder
import com.nearlink.app.data.local.NearLinkDatabase
import com.nearlink.app.data.repository.MessageRepositoryImpl
import com.nearlink.app.data.security.ContactStore
import com.nearlink.app.data.security.EncryptionManager
import com.nearlink.app.data.security.IdentityManager
import com.nearlink.app.data.security.SecureMessenger
import com.nearlink.app.data.storage.MediaStorage
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.PeerDevice
import com.nearlink.app.service.NearLinkForegroundService
import com.nearlink.app.transport.WireMessage
import com.nearlink.app.wifidirect.WifiDirectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    HOME, DISCOVERY, CHAT, SETTINGS
}

class NearLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val identity = IdentityManager(application)
    private val encryption = EncryptionManager()
    private val database = NearLinkDatabase.getDatabase(application)
    private val messageRepository = MessageRepositoryImpl(database.messageDao(), encryption)

    private val contactStore = ContactStore(application)
    private val secureMessenger = SecureMessenger(identity, contactStore, application) { _temporaryPin.value }
    private val bluetooth = BluetoothConnectionManager(application)
    private val wifi = WifiDirectManager(application)
    private val audioRecorder = AudioRecorder(application)

    // ---------------- Estado de UI ----------------
    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _selectedPeer = MutableStateFlow<PeerDevice?>(null)
    val selectedPeer: StateFlow<PeerDevice?> = _selectedPeer.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val pinPrefs = application.getSharedPreferences("nearlink_pairing", Context.MODE_PRIVATE)
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
    private val observedPeers = mutableSetOf<String>()

    init {
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
        observeDeliveryAcks()
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
                    bluetooth.send(secureMessenger.beginHandshake())
                    bluetooth.send(secureMessenger.buildContacts())
                    flushOutbox()
                }
                refreshSelectedPeerState(mapped)
            }
        }
        viewModelScope.launch {
            secureMessenger.peerFingerprint.collect { fp ->
                if (fp != null) {
                    activePeerId = fp
                    _selectedPeer.value?.let { cur ->
                        _selectedPeer.value = cur.copy(id = fp, publicKeyFingerprint = fp)
                    }
                    observeMessages(fp)
                }
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
                        publicKeyFingerprint = "—"
                    )
                }
            }
        }
        viewModelScope.launch {
            bluetooth.incoming.collect { msg ->
                secureMessenger.handleIncoming(msg)
                if (secureMessenger.consumeFlushRequest()) {
                    bluetooth.send(secureMessenger.buildContacts())
                    flushOutbox()
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
                val peerId = app.senderFp
                val (displayContent, localPath) = when (app.kind) {
                    "FILE", "VOICE" -> {
                        val decoded = runCatching { Base64.decode(app.content, Base64.NO_WRAP) }.getOrNull()
                        if (decoded != null) {
                            val name = app.fileName ?: if (app.kind == "VOICE") "voz.m4a" else "archivo"
                            val mime = MediaStorage.mimeFromName(name)
                            val uri = withContext(Dispatchers.IO) {
                                MediaStorage.saveToSharedStorage(getApplication<Application>(), decoded, name, mime)
                            }
                            val label = when {
                                app.kind == "VOICE" -> "Nota de voz"
                                mime.startsWith("image") -> "Imagen"
                                else -> "Archivo"
                            }
                            ("$label (${decoded.size} bytes)") to uri?.toString()
                        } else {
                            app.content to null
                        }
                    }
                    else -> app.content to null
                }
                val incoming = Message(
                    id = app.id,
                    senderId = peerId,
                    recipientId = "me",
                    content = displayContent,
                    timestamp = app.timestamp,
                    type = parseType(app.kind),
                    status = MessageStatus.DELIVERED,
                    isEncrypted = true,
                    fileName = app.fileName,
                    ttlSeconds = app.ttlSeconds,
                    isSos = app.kind == "SOS",
                    localFilePath = localPath
                )
                messageRepository.sendMessage(incoming)
                observeMessages(peerId)
                bluetooth.send(secureMessenger.buildAck(app.id))
            }
        }
    }

    private fun observeDeliveryAcks() {
        viewModelScope.launch {
            secureMessenger.deliveryAcks.collect { msgId ->
                messageRepository.updateStatus(msgId, MessageStatus.DELIVERED.name)
            }
        }
    }

    private fun startTtlCleanup() {
        viewModelScope.launch {
            while (true) {
                delay(5000)
                messageRepository.cleanupExpiredMessages()
            }
        }
    }

    private fun flushOutbox() {
        for (env in secureMessenger.outboxSnapshot()) bluetooth.send(env)
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

    fun stopScan() { bluetooth.cancelDiscovery() }

    fun sendMessage(
        content: String,
        type: MessageType = MessageType.TEXT,
        fileName: String? = null,
        ttlSeconds: Int = 0,
        isSos: Boolean = false
    ) {
        val recipient = secureMessenger.peerFingerprint.value ?: run {
            _errorMessage.value = "Aún conectando… espera a que se complete el enlace seguro."
            return
        }
        val envelope = secureMessenger.sealToRecipient(recipient, type.name, content, ttlSeconds, fileName) ?: run {
            _errorMessage.value = "No se conoce la llave del destinatario aún (espera el handshake)."
            return
        }
        viewModelScope.launch {
            val message = Message(
                id = envelope.msgId,
                senderId = "me",
                recipientId = recipient,
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
            observeMessages(recipient)
            if (bluetooth.send(envelope)) {
                messageRepository.updateStatus(envelope.msgId, MessageStatus.SENT.name)
            } else {
                messageRepository.updateStatus(envelope.msgId, MessageStatus.FAILED.name)
                _errorMessage.value = "No hay conexión Bluetooth activa; el mensaje quedó en cola y se reenviará."
            }
        }
    }

    fun sendSosAlert() {
        sendMessage("¡ALERTA SOS! Nodo en situación crítica de emergencia.", MessageType.SOS, null, 0, true)
    }

    fun toggleVoiceRecording() {
        if (!_isRecordingVoice.value) {
            runCatching { audioRecorder.start() }
                .onSuccess { _isRecordingVoice.value = true }
                .onFailure { _errorMessage.value = "No se pudo iniciar la grabación de audio." }
        } else {
            _isRecordingVoice.value = false
            val file = audioRecorder.stop()
            if (file != null && file.exists()) {
                viewModelScope.launch {
                    val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                    file.delete()
                    if (bytes != null) sendVoiceBytes(bytes)
                    else _errorMessage.value = "No se pudo procesar la nota de voz."
                }
            }
        }
    }

    private suspend fun sendVoiceBytes(bytes: ByteArray) {
        val recipient = secureMessenger.peerFingerprint.value ?: run {
            _errorMessage.value = "Aún conectando… espera al enlace seguro."; return
        }
        val name = "voz_${System.currentTimeMillis()}.m4a"
        val savedUri = withContext(Dispatchers.IO) {
            MediaStorage.saveToSharedStorage(getApplication<Application>(), bytes, name, "audio/mp4")
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val envelope = withContext(Dispatchers.IO) {
            secureMessenger.sealToRecipient(recipient, MessageType.VOICE.name, b64, 0, name)
        } ?: run { _errorMessage.value = "Destinatario desconocido."; return }
        persistMedia(envelope, recipient, MessageType.VOICE, "Nota de voz (${bytes.size} bytes)", name, savedUri?.toString())
    }

    fun sendFileUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val recipient = secureMessenger.peerFingerprint.value ?: run {
                _errorMessage.value = "Aún conectando… espera al enlace seguro."; return@launch
            }
            val picked: Triple<ByteArray, String, Uri?>? = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val m = runCatching { resolver.getType(uri) }.getOrNull() ?: MediaStorage.mimeFromName(displayName)
                val b = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                Triple(b, m, MediaStorage.saveToSharedStorage(getApplication<Application>(), b, displayName, m))
            }
            if (picked == null) { _errorMessage.value = "No se pudo leer el archivo."; return@launch }
            val (bytes, mime, savedUri) = picked
            if (bytes.size > MAX_FILE_BYTES) {
                _errorMessage.value = "Archivo demasiado grande (máx ${MAX_FILE_BYTES / 1_000_000} MB por la malla)."
                return@launch
            }
            _transferStatus.value = "Enviando '$displayName' (${bytes.size} bytes) cifrado…"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val envelope = withContext(Dispatchers.IO) {
                secureMessenger.sealToRecipient(recipient, MessageType.FILE.name, b64, 0, displayName)
            } ?: run { _errorMessage.value = "Destinatario desconocido."; return@launch }
            val label = if (mime.startsWith("image")) "Imagen" else "Archivo"
            persistMedia(envelope, recipient, MessageType.FILE, "$label (${bytes.size} bytes)", displayName, savedUri?.toString())
        }
    }

    private suspend fun persistMedia(
        envelope: com.nearlink.app.transport.WireMessage.Envelope,
        recipient: String,
        type: MessageType,
        displayContent: String,
        fileName: String,
        localPath: String?
    ) {
        val message = Message(
            id = envelope.msgId,
            senderId = "me",
            recipientId = recipient,
            content = displayContent,
            timestamp = System.currentTimeMillis(),
            type = type,
            status = MessageStatus.SENDING,
            isEncrypted = true,
            fileName = fileName,
            ttlSeconds = 0,
            isSos = false,
            localFilePath = localPath
        )
        messageRepository.sendMessage(message)
        observeMessages(recipient)
        if (bluetooth.send(envelope)) {
            messageRepository.updateStatus(envelope.msgId, MessageStatus.SENT.name)
        } else {
            messageRepository.updateStatus(envelope.msgId, MessageStatus.FAILED.name)
            _errorMessage.value = "Sin conexión Bluetooth; el archivo quedó en cola para reenviar."
        }
    }

    fun updatePin(newPin: String) {
        val clean = newPin.trim()
        if (clean.isEmpty()) return
        _temporaryPin.value = clean
        pinPrefs.edit().putString("pin", clean).apply()
        encryption.deriveKeyFromPin(clean)
    }

    fun regeneratePin() {
        updatePin((1000..9999).random().toString())
    }

    fun clearError() { _errorMessage.value = null }
    fun clearTransferStatus() { _transferStatus.value = null }

    // ---------------- Helpers ----------------
    private fun observeMessages(peerId: String) {
        if (!observedPeers.add(peerId)) return
        viewModelScope.launch {
            messageRepository.getMessagesForPeer(peerId).collect { list ->
                _messages.value = _messages.value + (peerId to list)
            }
        }
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
    }

    companion object {
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024 // 8 MB por la malla Bluetooth
    }
}
