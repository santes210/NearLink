package com.nearlink.app.domain.usecase

import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.FrameType
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.SettingsRepository
import com.nearlink.app.domain.repository.TransportRepository
import kotlinx.coroutines.withContext
import java.util.UUID

/** Crea un mensaje de dominio con id estable y TTL calculado. */
fun buildMessage(
    peerId: String,
    content: String,
    type: MessageType,
    ttlSeconds: Int,
    attachment: com.nearlink.app.domain.model.Attachment? = null,
): Message {
    val now = System.currentTimeMillis()
    return Message(
        id = UUID.randomUUID().toString(),
        peerId = peerId,
        outgoing = true,
        content = content,
        timestamp = now,
        type = type,
        status = MessageStatus.QUEUED,
        attachment = attachment,
        ttlSeconds = ttlSeconds,
        expiresAt = if (ttlSeconds > 0) now + ttlSeconds * 1_000L else null,
    )
}

/**
 * Envia un mensaje de texto (o SOS).
 *
 * Flujo real: se cifra y persiste -> se transmite por BLE -> se marca como
 * entregado solo cuando llega el ACK del receptor. Si no hay enlace, queda en
 * cola para reintentarlo.
 */
class SendMessageUseCase(
    private val messageRepository: MessageRepository,
    private val transport: TransportRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(
        peerId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        ttlSeconds: Int = 0,
    ): Outcome<Message> = withContext(dispatchers.io) {
        val message = buildMessage(peerId, content, type, ttlSeconds)
        transmit(message)
    }

    suspend fun transmit(message: Message): Outcome<Message> = withContext(dispatchers.io) {
        when (val prepared = messageRepository.enqueueOutgoing(message)) {
            is Outcome.Failure -> prepared
            is Outcome.Success -> {
                val frame = prepared.data
                val frameType = when (message.type) {
                    MessageType.VOICE, MessageType.FILE -> FrameType.FILE
                    MessageType.SOS -> FrameType.SOS
                    MessageType.TEXT -> FrameType.MESSAGE
                }
                when (val result = transport.send(message.peerId, frameType, frame.payload)) {
                    is Outcome.Success -> {
                        messageRepository.updateStatus(message.id, MessageStatus.SENT)
                        Outcome.Success(message)
                    }

                    is Outcome.Failure -> {
                        messageRepository.updateStatus(message.id, MessageStatus.QUEUED)
                        Outcome.Failure(message = result.message)
                    }
                }
            }
        }
    }
}

/** Envia una nota de voz o un fichero adjunto. */
class SendAttachmentUseCase(
    private val messageRepository: MessageRepository,
    private val transport: TransportRepository,
    private val dispatchers: CoroutineDispatchers,
    private val sendMessage: SendMessageUseCase,
) {
    suspend operator fun invoke(
        peerId: String,
        bytes: ByteArray,
        name: String,
        mimeType: String,
        ttlSeconds: Int = 0,
    ): Outcome<Message> = withContext(dispatchers.io) {
        if (bytes.isEmpty()) {
            return@withContext Outcome.Failure(message = "El archivo está vacío")
        }
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            return@withContext Outcome.Failure(
                message = "El archivo supera el máximo de ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB por mensaje",
            )
        }
        val type = if (mimeType.startsWith("audio/")) MessageType.VOICE else MessageType.FILE
        val messageId = UUID.randomUUID().toString()
        val attachment = messageRepository.storeAttachment(
            peerId = peerId,
            messageId = messageId,
            bytes = bytes,
            name = name,
            mimeType = mimeType,
        )
        val message = buildMessage(peerId, name, type, ttlSeconds, attachment).copy(id = messageId)
        sendMessage.transmit(message)
    }

    companion object {
        /** Tope de 25 MB: por encima el consumo de memoria y bateria no compensa. */
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
    }
}

/** Reintenta los mensajes que quedaron en cola (por ejemplo tras un handshake). */
class RetryPendingMessagesUseCase(
    private val messageRepository: MessageRepository,
    private val sendMessage: SendMessageUseCase,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(): Int = withContext(dispatchers.io) {
        val pending = messageRepository.pendingMessages()
        var delivered = 0
        pending.forEach { message ->
            val result = sendMessage.transmit(message)
            if (result is Outcome.Success) delivered++
        }
        delivered
    }
}

/** Limpia los mensajes con TTL vencido. */
class PurgeExpiredMessagesUseCase(
    private val messageRepository: MessageRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(now: Long = System.currentTimeMillis()): Int =
        withContext(dispatchers.io) { messageRepository.purgeExpired(now) }
}

/** Establece el enlace con un nodo. */
class ConnectToPeerUseCase(
    private val transport: TransportRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(peerId: String): Outcome<Unit> =
        withContext(dispatchers.io) { transport.connect(peerId) }
}

/** Renueva el PIN de emparejamiento. */
class RotatePairingPinUseCase(
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(): String =
        withContext(dispatchers.io) { settingsRepository.rotatePairingPin() }
}
