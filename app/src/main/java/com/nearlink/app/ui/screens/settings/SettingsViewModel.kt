package com.nearlink.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.domain.model.NodeIdentity
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.model.ThemeMode
import com.nearlink.app.domain.model.UserSettings
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val identityRepository: IdentityRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    val settings: StateFlow<UserSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserSettings(),
        )

    val identity: StateFlow<NodeIdentity?> =
        identityRepository.observeIdentity()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val peers: StateFlow<List<Peer>> =
        peerRepository.observePeers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch(dispatchers.io) { settingsRepository.update(transform) }
    }

    fun setDisplayName(name: String) = update { it.copy(displayName = name) }

    fun setRelay(enabled: Boolean) = update { it.copy(relayEnabled = enabled) }

    fun setAutoDelete(enabled: Boolean) = update { it.copy(autoDeleteEnabled = enabled) }

    fun setTheme(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setDefaultTtl(seconds: Int) = update { it.copy(defaultTtlSeconds = seconds) }

    fun setWifiThreshold(mb: Int) = update { it.copy(wifiDirectThresholdMb = mb) }

    /** Rota la identidad del nodo (nuevas claves P-256). */
    fun regenerateIdentity() {
        viewModelScope.launch(dispatchers.io) { identityRepository.regenerate() }
    }

    /** Borra todos los mensajes y adjuntos guardados. */
    fun wipeMessages() {
        viewModelScope.launch(dispatchers.io) {
            peers.value.forEach { peer -> messageRepository.clearConversation(peer.id) }
        }
    }
}
