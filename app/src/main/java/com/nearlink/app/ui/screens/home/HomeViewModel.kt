package com.nearlink.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.domain.model.Conversation
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.NodeIdentity
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.model.TransportStatus
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.TransportRepository
import com.nearlink.app.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    messageRepository: MessageRepository,
    peerRepository: PeerRepository,
    transport: TransportRepository,
    identityRepository: IdentityRepository,
    private val sendMessage: SendMessageUseCase,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        messageRepository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peers: StateFlow<List<Peer>> =
        peerRepository.observePeers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transportStatus: StateFlow<TransportStatus> =
        transport.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportStatus())

    val identity: StateFlow<NodeIdentity?> =
        identityRepository.observeIdentity()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Envia una alerta SOS a todos los nodos conectados y conocidos. */
    fun sendSos() {
        viewModelScope.launch(dispatchers.io) {
            peers.value.forEach { peer ->
                sendMessage(
                    peerId = peer.id,
                    content = "SOS: necesidad de auxilio inmediato",
                    type = MessageType.SOS,
                )
            }
        }
    }
}
