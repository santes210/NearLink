
package com.nearlink.app.domain.repository

import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.PeerDevice
import com.nearlink.app.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForPeer(peerId: String): Flow<List<Message>>
    suspend fun sendMessage(message: Message): Result<Unit>
    suspend fun updateStatus(messageId: String, status: String): Result<Unit>
    suspend fun cleanupExpiredMessages()
}

interface BluetoothRepository {
    suspend fun scanPeers(): Result<List<PeerDevice>>
    suspend fun connectToPeer(device: PeerDevice): Result<Boolean>
    fun disconnect()
}
