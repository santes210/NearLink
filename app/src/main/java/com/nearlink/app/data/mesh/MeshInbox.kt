package com.nearlink.app.data.mesh

import android.util.Log
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.data.crypto.MessageCipher
import com.nearlink.app.data.repository.WireFormat
import com.nearlink.app.domain.model.FrameType
import com.nearlink.app.domain.model.Attachment
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.repository.ChannelRepository
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.IncomingEnvelope
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
    private val channelRepository: ChannelRepository,
    private val identityRepository: IdentityRepository,
    private val dispatchers: CoroutineDispatchers,
    private val scope: CoroutineScope,
    private val onIncomingMessage: suspend (peerId: String, preview: String) -> Unit = { _, _ -> },
) {

    @Volatile
    private var job: Job? = null

    /** Ids de mensajes de grupo ya vistos (evita duplicados en mallas con varias rutas). */
    private val seenGroupMessages = LinkedHashSet<String>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(dispatchers.io) {
            transport.observeIncoming().collect { envelope ->
                // Un fallo al procesar una trama no debe matar el colector (si
                // no, la app dejaria de recibir para siempre), pero tampoco
                // puede quedar invisible: por eso se registra en el log.
                runCatching { handle(envelope) }.onFailure {
                    Log.w(TAG, "Trama entrante descartada (${envelope.type})", it)
                }
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
            FrameType.GROUP -> handleGroup(envelope)
            FrameType.HELLO, FrameType.HELLO_ACK, FrameType.ACK -> Unit
        }
    }

    /**
     * Mensaje de grupo: se descifra con la clave del canal (solo si pertenecemos
     * a él) y se guarda. Los nodos que no conocen el canal lo ignoran, pero ya
     * lo habrán reenviado a nivel de transporte si actúan como repetidores.
     */
    private suspend fun handleGroup(envelope: IncomingEnvelope) {
        if (rememberGroupMessage(envelope.messageId)) return
        val decrypted = channelRepository.decryptIncoming(envelope.payload) ?: return
        // La malla puede devolvernos nuestra propia difusión: la descartamos.
        if (decrypted.senderId == identityRepository.nodeId()) return
        channelRepository.recordMember(
            channelId = decrypted.channelId,
            senderId = decrypted.senderId,
            senderName = decrypted.senderName,
            seenAt = envelope.receivedAt,
        )
        channelRepository.persistIncomingGroup(
            decrypted = decrypted,
            timestamp = envelope.receivedAt,
            hops = envelope.hops,
            relayed = envelope.hops > 0,
        )
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

    /**
     * Marca un id de mensaje de grupo como visto. Devuelve true si ya estaba
     * (duplicado) o si no trae id. La colección es acotada para no crecer.
     */
    private fun rememberGroupMessage(id: String): Boolean {
        if (id.isBlank()) return true
        synchronized(seenGroupMessages) {
            if (!seenGroupMessages.add(id)) return true
            while (seenGroupMessages.size > MAX_SEEN_GROUP_MESSAGES) {
                val iterator = seenGroupMessages.iterator()
                iterator.next()
                iterator.remove()
            }
            return false
        }
    }

    companion object {
        private const val TAG = "NearLink"
        private const val MAX_SEEN_GROUP_MESSAGES = 512
    }
}
