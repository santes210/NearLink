package com.nearlink.app.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.Channel
import com.nearlink.app.domain.model.GroupMessage
import com.nearlink.app.domain.repository.ChannelRepository
import com.nearlink.app.domain.usecase.SendGroupMessageUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupChatViewModel(
    private val channelId: String,
    channelRepository: ChannelRepository,
    private val sendGroupMessage: SendGroupMessageUseCase,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    val channel: StateFlow<Channel?> =
        channelRepository.observeChannels()
            .map { list -> list.firstOrNull { it.id == channelId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<GroupMessage>> =
        channelRepository.observeMessages(channelId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            _isSending.value = true
            when (val result = sendGroupMessage(channelId, content)) {
                is Outcome.Success -> _error.value = null
                is Outcome.Failure -> _error.value = result.message
            }
            _isSending.value = false
        }
    }

    fun consumeError() {
        _error.value = null
    }
}
