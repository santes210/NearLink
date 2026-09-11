package com.nearlink.app.data.mesh

import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.data.crypto.MessageCipher
import com.nearlink.app.data.repository.WireFormat
import com.nearlink.app.domain.model.FrameType
import com.nearlink.app.domain.model.IncomingEnvelope
import com.nearlink.app.domain.model.Attachment
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.TransportRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Buzon de entrada de la malla: recibe las tramas del transporte, las descifra
 * y las guarda en la base de datos.
 *
 * Antes de este cambio la app "recibia" mensajes simulados; aqui es donde el
 * mensaje real de otro movil pasa a ser una fila cifrada en Room.
 */
class MeshInbox(
    private val transport: TransportRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val cipher: MessageCipher,
    private val dispatchers: CoroutineDispatchers,
    private val scope: CoroutineScope,
    private val onIncomingMessage: suspend (peerId: String, preview: String) -> Unit = { _, _ -> },
) {

    @Volatile
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(dispatchers.io) {
            transport.observeIncoming().collect { envelope ->
                runCatching { handle(envelope) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handle(envelope: IncomingEnvelope) {
        val peer = peerRepository.find(envelope.senderId)
        if (peer?.blocked == true) return

        when (envelope.type) {
            FrameType.MESSAGE, FrameType.SOS -> handleText(envelope)
            FrameType.FILE -> handleFile(envelope)
            FrameType.HELLO, FrameType.HELLO_ACK, FrameType.ACK -> Unit
        }
    }

    private suspend fun handleText(envelope: IncomingEnvelope) {
        val box = WireFormat.unseal(envelope.payload) ?: return
        val bytes = cipher.decrypt(envelope.senderId, box) ?: return
        val text = String(bytes, Charsets.UTF_8)
        storeAndNotify(
            envelope = envelope,
            content = text,
            type = if (envelope.type == FrameType.SOS) MessageType.SOS else MessageType.TEXT,
            attachment = null,
        )
    }

    private suspend fun handleFile(envelope: IncomingEnvelope) {
        val frame = WireFormat.parseFileFrame(envelope.payload) ?: return
        val bytes = cipher.decrypt(envelope.senderId, frame.box) ?: return
        val isAudio = frame.mimeType.startsWith("audio/")
        val messageId = UUID.randomUUID().toString()
        val attachment = messageRepository.storeAttachment(
            peerId = envelope.senderId,
            messageId = messageId,
            bytes = bytes,
            name = frame.name,
            mimeType = frame.mimeType,
            durationMs = null,
        )
        storeAndNotify(
            envelope = envelope,
            content = frame.name,
            type = if (isAudio) MessageType.VOICE else MessageType.FILE,
            attachment = attachment,
            messageId = messageId,
        )
    }

    private suspend fun storeAndNotify(
        envelope: IncomingEnvelope,
        content: String,
        type: MessageType,
        attachment: Attachment?,
        messageId: String = UUID.randomUUID().toString(),
    ) {
        val now = System.currentTimeMillis()
        val message = Message(
            id = messageId,
            peerId = envelope.senderId,
            outgoing = false,
            content = content,
            timestamp = envelope.receivedAt.takeIf { it <= now } ?: now,
            type = type,
            status = MessageStatus.DELIVERED,
            attachment = attachment,
            hops = envelope.hops,
            relayed = envelope.hops > 0,
        )
        withContext(dispatchers.io) { messageRepository.persistIncoming(message) }
        onIncomingMessage(envelope.senderId, content.take(120))
    }
}
