package com.nearlink.app.data.repository

import java.util.Base64
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.core.outcomeOf
import com.nearlink.app.data.crypto.CryptoManager
import com.nearlink.app.data.crypto.MessageCipher
import com.nearlink.app.data.crypto.SealedBox
import com.nearlink.app.data.local.AttachmentStore
import com.nearlink.app.data.local.dao.MessageDao
import com.nearlink.app.data.local.entity.ConversationRow
import com.nearlink.app.data.local.entity.MessageEntity
import com.nearlink.app.data.local.mapper.attachmentOf
import com.nearlink.app.data.local.mapper.toDomain
import com.nearlink.app.data.local.mapper.toEntity
import com.nearlink.app.domain.model.Attachment
import com.nearlink.app.domain.model.Conversation
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.OutgoingFrame
import com.nearlink.app.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * Repositorio de mensajes.
 *
 * Punto clave de seguridad: el contenido NUNCA se guarda en claro. Se cifra con
 * el secreto compartido derivado por ECDH con ese par (cifrado de extremo a
 * extremo real) y, si aun no se ha hecho el handshake, con la clave de reposo
 * del dispositivo hasta que se pueda reenviar cifrado.
 */
class MessageRepositoryImpl(
    private val messageDao: MessageDao,
    private val crypto: CryptoManager,
    private val cipher: MessageCipher,
    private val peerRepository: PeerRepository,
    private val attachmentStore: AttachmentStore,
    private val dispatchers: CoroutineDispatchers,
) : MessageRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        messageDao.observeConversations().mapLatest { rows ->
            rows.mapNotNull { row -> row.toConversation() }
        }

    override fun observeMessages(peerId: String): Flow<List<Message>> =
        messageDao.observeForPeer(peerId).mapLatest { entities ->
            entities.mapNotNull { entity -> entity.toDomainMessage(peerId) }
        }

    override suspend fun persistIncoming(message: Message) {
        withContext(dispatchers.io) {
            val (box, _) = cipher.encrypt(message.peerId, message.content.toByteArray(Charsets.UTF_8))
            messageDao.upsert(
                message.toEntity(
                    sealedContent = box.ciphertext.toBase64() to box.iv.toBase64(),
                    status = message.status,
                ),
            )
        }
    }

    override suspend fun enqueueOutgoing(message: Message): Outcome<OutgoingFrame> =
        withContext(dispatchers.io) {
            outcomeOf {
                val (box, endToEnd) = cipher.encrypt(message.peerId, message.content.toByteArray(Charsets.UTF_8))
                val status = if (endToEnd) MessageStatus.SENDING else MessageStatus.QUEUED
                messageDao.upsert(
                    message.toEntity(
                        sealedContent = box.ciphertext.toBase64() to box.iv.toBase64(),
                        status = status,
                    ),
                )
                val payload = when {
                    message.type == MessageType.VOICE || message.type == MessageType.FILE -> {
                        val attachment = message.attachment
                        if (attachment == null) {
                            WireFormat.seal(box)
                        } else {
                            val attachmentKey = cipher.keysFor(message.peerId).first()
                            val bytes = attachmentStore.read(attachmentKey, attachment) ?: ByteArray(0)
                            WireFormat.fileFrame(
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                                size = attachment.sizeBytes,
                                box = crypto.encrypt(bytes, attachmentKey),
                            )
                        }
                    }

                    else -> WireFormat.seal(box)
                }
                OutgoingFrame(messageId = message.id, payload = payload, endToEnd = endToEnd)
            }
        }

    override suspend fun updateStatus(messageId: String, status: MessageStatus) {
        withContext(dispatchers.io) { messageDao.updateStatus(messageId, status) }
    }

    override suspend fun markConversationRead(peerId: String) {
        withContext(dispatchers.io) { messageDao.markRead(peerId) }
    }

    override suspend fun purgeExpired(now: Long): Int =
        withContext(dispatchers.io) {
            val expired = messageDao.expired(now)
            expired.forEach { entity ->
                entity.attachmentOf()?.let { attachmentStore.delete(it) }
            }
            messageDao.deleteExpired(now)
        }

    override suspend fun deleteMessage(messageId: String) {
        withContext(dispatchers.io) {
            messageDao.find(messageId)?.attachmentOf()?.let { attachmentStore.delete(it) }
            messageDao.delete(messageId)
        }
    }

    override suspend fun clearConversation(peerId: String) {
        withContext(dispatchers.io) {
            messageDao.forPeer(peerId).forEach { entity -> entity.attachmentOf()?.let { attachmentStore.delete(it) } }
            messageDao.deleteConversation(peerId)
        }
    }

    override suspend fun pendingMessages(): List<Message> =
        withContext(dispatchers.io) {
            val entities = messageDao.pending()
            entities.mapNotNull { entity -> entity.toDomainMessage(entity.peerId) }
        }

    override suspend fun getMessage(messageId: String): Message? =
        withContext(dispatchers.io) {
            messageDao.find(messageId)?.let { it.toDomainMessage(it.peerId) }
        }

    // --------------------------------------------------------------- privado

    /**
     * Claves candidatas para un peer: primero el secreto compartido E2E y como
     * respaldo la clave de reposo (mensajes encolados antes del handshake).
     */
    private suspend fun MessageEntity.toDomainMessage(peerId: String): Message? {
        val box = runCatching {
            SealedBox(
                ciphertext = Base64.getDecoder().decode(ciphertext),
                iv = Base64.getDecoder().decode(iv),
            )
        }.getOrNull() ?: return null
        val bytes = cipher.decrypt(peerId, box) ?: return null
        return toDomain(String(bytes, Charsets.UTF_8))
    }

    private suspend fun ConversationRow.toConversation(): Conversation {
        val preview = if (previewCiphertext != null && previewIv != null) {
            runCatching {
                val box = SealedBox(
                    ciphertext = Base64.getDecoder().decode(previewCiphertext),
                    iv = Base64.getDecoder().decode(previewIv),
                )
                cipher.decrypt(peerId, box)?.let { String(it, Charsets.UTF_8) }
            }.getOrNull()
        } else {
            null
        }
        val peer = peerRepository.find(peerId)
        return Conversation(
            peerId = peerId,
            peerName = peer?.name ?: peerName,
            preview = preview?.takeIf { it.isNotBlank() } ?: "",
            lastActivity = lastActivity,
            unreadCount = unread,
            isConnected = peer?.connectionState?.name == "CONNECTED",
            rssi = peer?.rssi ?: rssi,
            fingerprint = peer?.fingerprint ?: fingerprint,
        )
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    /** Guarda un adjunto recibido: siempre cifrado en reposo. */
    override suspend fun storeAttachment(
        peerId: String,
        messageId: String,
        bytes: ByteArray,
        name: String,
        mimeType: String,
        durationMs: Long?,
    ): Attachment = withContext(dispatchers.io) {
        attachmentStore.save(
            key = cipher.keysFor(peerId).first(),
            id = messageId,
            bytes = bytes,
            name = name,
            mimeType = mimeType,
            durationMs = durationMs,
        )
    }

    /** Descifra un adjunto a la cache para reproducirlo o compartirlo. */
    override suspend fun openAttachment(peerId: String, attachment: Attachment): java.io.File? =
        withContext(dispatchers.io) {
            cipher.keysFor(peerId).firstNotNullOfOrNull { key -> attachmentStore.export(key, attachment) }
        }
}
