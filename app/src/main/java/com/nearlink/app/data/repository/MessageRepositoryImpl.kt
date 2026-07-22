package com.nearlink.app.data.repository

import com.nearlink.app.data.local.MessageDao
import com.nearlink.app.data.local.MessageEntity
import com.nearlink.app.data.security.EncryptionManager
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.Result
import com.nearlink.app.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageRepositoryImpl(
    private val messageDao: MessageDao,
    private val encryptionManager: EncryptionManager
) : MessageRepository {

    override fun getMessagesForPeer(peerId: String): Flow<List<Message>> {
        return messageDao.getMessagesForPeer(peerId).map { entities ->
            entities.map { e ->
                Message(
                    id = e.id,
                    senderId = e.senderId,
                    recipientId = e.recipientId,
                    content = e.content,
                    timestamp = e.timestamp,
                    type = MessageType.valueOf(e.type),
                    status = MessageStatus.valueOf(e.status),
                    isEncrypted = e.isEncrypted,
                    fileName = e.fileName,
                    ttlSeconds = e.ttlSeconds,
                    isSos = e.isSos,
                    localFilePath = e.localFilePath
                )
            }
        }
    }

    override suspend fun sendMessage(message: Message): Result<Unit> {
        return try {
            val entity = MessageEntity(
                id = message.id,
                senderId = message.senderId,
                recipientId = message.recipientId,
                content = message.content,
                timestamp = message.timestamp,
                type = message.type.name,
                status = message.status.name,
                isEncrypted = message.isEncrypted,
                fileName = message.fileName,
                ttlSeconds = message.ttlSeconds,
                isSos = message.isSos,
                localFilePath = message.localFilePath
            )
            messageDao.insertMessage(entity)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, "Error al guardar mensaje en Room")
        }
    }

    override suspend fun updateStatus(messageId: String, status: String): Result<Unit> {
        return try {
            messageDao.updateStatus(messageId, status)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, "Error al actualizar estado")
        }
    }

    override suspend fun cleanupExpiredMessages() {
        messageDao.deleteExpiredMessages(System.currentTimeMillis())
    }
}
