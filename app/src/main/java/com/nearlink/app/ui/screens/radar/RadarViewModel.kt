package com.nearlink.app.ui.screens.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.model.ScanState
import com.nearlink.app.domain.model.TransportStatus
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.TransportRepository
import com.nearlink.app.domain.usecase.ConnectToPeerUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RadarViewModel(
    peerRepository: PeerRepository,
    private val transport: TransportRepository,
    private val connectToPeer: ConnectToPeerUseCase,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    val peers: StateFlow<List<Peer>> =
        peerRepository.observePeers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scanState: StateFlow<ScanState> =
        transport.scanState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanState.IDLE)

    val status: StateFlow<TransportStatus> =
        transport.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportStatus())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _connecting = MutableStateFlow<Set<String>>(emptySet())
    val connecting: StateFlow<Set<String>> = _connecting

    fun toggleScan() {
        viewModelScope.launch(dispatchers.io) {
            if (scanState.value == ScanState.SCANNING) {
                transport.stopScan()
            } else {
                val result = transport.startScan()
                _message.value = (result as? Outcome.Failure)?.message
            }
        }
    }

    fun connect(peer: Peer) {
        viewModelScope.launch(dispatchers.io) {
            _connecting.value = _connecting.value + peer.id
            val result = connectToPeer(peer.id)
            _connecting.value = _connecting.value - peer.id
            _message.value = when (result) {
                is Outcome.Success -> null
                is Outcome.Failure -> result.message
            }
        }
    }

    fun isConnected(peer: Peer): Boolean = peer.connectionState == ConnectionState.CONNECTED

    fun consumeMessage() {
        _message.value = null
    }
}
