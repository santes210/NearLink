package com.nearlink.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.usecase.ConnectToPeerUseCase
import com.nearlink.app.domain.usecase.SendAttachmentUseCase
import com.nearlink.app.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(
    private val peerId: String,
    private val messageRepository: MessageRepository,
    peerRepository: PeerRepository,
    private val sendMessage: SendMessageUseCase,
    private val sendAttachment: SendAttachmentUseCase,
    private val connectToPeer: ConnectToPeerUseCase,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    val peer: StateFlow<Peer?> = peerRepository.observePeers()
        .map { list -> list.firstOrNull { it.id == peerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<Message>> =
        messageRepository.observeMessages(peerId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connection: StateFlow<ConnectionState> = peer
        .map { it?.connectionState ?: ConnectionState.DISCONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.DISCONNECTED)

    private val _ttlSeconds = MutableStateFlow(0)
    val ttlSeconds: StateFlow<Int> = _ttlSeconds

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** True cuando ya hubo handshake y por tanto el cifrado es E2E. */
    private val _endToEnd = MutableStateFlow(false)
    val endToEnd: StateFlow<Boolean> = _endToEnd

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    init {
        viewModelScope.launch(dispatchers.io) {
            messageRepository.markConversationRead(peerId)
            _endToEnd.value = peerRepository.find(peerId)?.publicKey != null
        }
    }

    fun setTtl(seconds: Int) {
        _ttlSeconds.value = seconds
    }

    fun connect() {
        viewModelScope.launch(dispatchers.io) {
            when (val result = connectToPeer(peerId)) {
                is Outcome.Success -> {
                    _error.value = null
                    _endToEnd.value = peerRepository.find(peerId)?.publicKey != null
                }

                is Outcome.Failure -> _error.value = result.message
            }
        }
    }

    fun sendText(text: String) {
        val content = text.trim()
        if (content.isBlank()) return
        viewModelScope.launch(dispatchers.io) {
            _isSending.value = true
            when (val result = sendMessage(peerId, content, MessageType.TEXT, _ttlSeconds.value)) {
                is Outcome.Success -> _error.value = null
                is Outcome.Failure -> _error.value = result.message
            }
            _isSending.value = false
        }
    }

    fun sendSos() {
        viewModelScope.launch(dispatchers.io) {
            when (val result = sendMessage(peerId, "SOS: necesidad de auxilio inmediato", MessageType.SOS)) {
                is Outcome.Failure -> _error.value = result.message
                is Outcome.Success -> _error.value = null
            }
        }
    }

    fun sendAttachmentBytes(bytes: ByteArray, name: String, mimeType: String) {
        viewModelScope.launch(dispatchers.io) {
            _isSending.value = true
            when (val result = sendAttachment(peerId, bytes, name, mimeType, _ttlSeconds.value)) {
                is Outcome.Success -> _error.value = null
                is Outcome.Failure -> _error.value = result.message
            }
            _isSending.value = false
        }
    }

    suspend fun openAttachment(attachment: com.nearlink.app.domain.model.Attachment): File? =
        messageRepository.openAttachment(peerId, attachment)

    fun retryFailed() {
        viewModelScope.launch(dispatchers.io) {
            messages.value
                .filter { it.status == MessageStatus.FAILED || it.status == MessageStatus.QUEUED }
                .forEach { message -> sendMessage.transmit(message) }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}
