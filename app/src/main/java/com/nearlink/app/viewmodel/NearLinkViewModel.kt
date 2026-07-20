
package com.nearlink.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.data.bluetooth.BluetoothServiceManager
import com.nearlink.app.data.local.NearLinkDatabase
import com.nearlink.app.data.repository.MessageRepositoryImpl
import com.nearlink.app.data.security.EncryptionManager
import com.nearlink.app.domain.model.*
import com.nearlink.app.service.NearLinkForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Screen {
    HOME, DISCOVERY, CHAT, SETTINGS
}

class NearLinkViewModel(application: Application) : AndroidViewModel(application) {
    private val encryptionManager = EncryptionManager()
    private val database = NearLinkDatabase.getDatabase(application)
    private val messageRepository = MessageRepositoryImpl(database.messageDao(), encryptionManager)
    private val bluetoothManager = BluetoothServiceManager(application)

    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _selectedPeer = MutableStateFlow<PeerDevice?>(null)
    val selectedPeer: StateFlow<PeerDevice?> = _selectedPeer.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _temporaryPin = MutableStateFlow("4821")
    val temporaryPin: StateFlow<String> = _temporaryPin.asStateFlow()

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _identityFingerprint = MutableStateFlow("X25519:A7F3:9C21:44E2:8B10")
    val identityFingerprint: StateFlow<String> = _identityFingerprint.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // Start Foreground Service & BLE Beacon
        val serviceIntent = Intent(application, NearLinkForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            application.startForegroundService(serviceIntent)
        } else {
            application.startService(serviceIntent)
        }

        loadPeers()

        // TTL cleanup loop
        viewModelScope.launch {
            while (true) {
                delay(5000)
                messageRepository.cleanupExpiredMessages()
            }
        }
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun selectPeer(peer: PeerDevice) {
        _selectedPeer.value = peer
        navigateTo(Screen.CHAT)
        observeMessages(peer.id)
    }

    fun loadPeers() {
        viewModelScope.launch {
            when (val result = bluetoothManager.scanDevices()) {
                is Result.Success -> {
                    _peers.value = result.data
                }
                is Result.Error -> {
                    _errorMessage.value = result.message
                }
                Result.Loading -> {}
            }
        }
    }

    private fun observeMessages(peerId: String) {
        viewModelScope.launch {
            messageRepository.getMessagesForPeer(peerId).collect { msgList ->
                _messages.value = _messages.value + (peerId to msgList)
            }
        }
    }

    fun sendMessage(content: String, type: MessageType = MessageType.TEXT, fileName: String? = null, ttlSeconds: Int = 0, isSos: Boolean = false) {
        val peer = _selectedPeer.value ?: return
        val msgId = System.currentTimeMillis().toString()

        viewModelScope.launch {
            val message = Message(
                id = msgId,
                senderId = "me",
                recipientId = peer.id,
                content = content,
                timestamp = System.currentTimeMillis(),
                type = type,
                status = MessageStatus.SENT,
                isEncrypted = true,
                fileName = fileName,
                ttlSeconds = ttlSeconds,
                isSos = isSos
            )

            when (val res = messageRepository.sendMessage(message)) {
                is Result.Success -> {
                    delay(1000)
                    messageRepository.updateStatus(msgId, MessageStatus.DELIVERED.name)
                    delay(1500)
                    messageRepository.updateStatus(msgId, MessageStatus.READ.name)
                }
                is Result.Error -> {
                    _errorMessage.value = res.message
                }
                Result.Loading -> {}
            }
        }
    }

    fun sendSosAlert() {
        val sosText = "¡ALERTA SOS! Nodo en situación crítica de emergencia."
        sendMessage(sosText, MessageType.SOS, null, 0, true)
    }

    fun toggleVoiceRecording() {
        _isRecordingVoice.value = !_isRecordingVoice.value
        if (!_isRecordingVoice.value) {
            sendMessage("Nota de voz cifrada (0:04)", MessageType.VOICE, "voicenote.mp3", 0, false)
        }
    }

    fun sendFile(fileName: String) {
        sendMessage("Archivo adjunto Wi-Fi Direct: $fileName", MessageType.FILE, fileName, 0, false)
    }

    fun updatePin(newPin: String) {
        _temporaryPin.value = newPin
        encryptionManager.deriveKeyFromPin(newPin)
    }

    fun regeneratePin() {
        val newPin = (1000..9999).random().toString()
        updatePin(newPin)
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
