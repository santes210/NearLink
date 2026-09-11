package com.nearlink.app.ui.screens.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.Channel
import com.nearlink.app.domain.repository.ChannelRepository
import com.nearlink.app.domain.usecase.JoinChannelUseCase
import com.nearlink.app.domain.usecase.LeaveChannelUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupsViewModel(
    channelRepository: ChannelRepository,
    private val joinChannel: JoinChannelUseCase,
    private val leaveChannel: LeaveChannelUseCase,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    val channels: StateFlow<List<Channel>> =
        channelRepository.observeChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Crea el grupo o se une a él con el código común. */
    fun join(code: String, name: String) {
        viewModelScope.launch(dispatchers.io) {
            when (val result = joinChannel(code, name)) {
                is Outcome.Success -> _message.value = null
                is Outcome.Failure -> _message.value = result.message
            }
        }
    }

    fun leave(channelId: String) {
        viewModelScope.launch(dispatchers.io) { leaveChannel(channelId) }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
