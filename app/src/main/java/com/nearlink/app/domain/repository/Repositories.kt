package com.nearlink.app.domain.repository

import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.Attachment
import com.nearlink.app.domain.model.Channel
import com.nearlink.app.domain.model.Conversation
import com.nearlink.app.domain.model.FrameType
import com.nearlink.app.domain.model.GroupMessage
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.NodeIdentity
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.model.ScanState
import com.nearlink.app.domain.model.TransportStatus
import com.nearlink.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * Almacen de mensajes. La capa de datos se encarga de cifrar el contenido
 * antes de escribirlo y de descifrarlo al leerlo: la UI nunca ve ciphertext.
 */
interface MessageRepository {

    fun observeConversations(): Flow<List<Conversation>>

    fun observeMessages(peerId: String): Flow<List<Message>>

    suspend fun persistIncoming(message: Message)

    suspend fun enqueueOutgoing(message: Message): Outcome<OutgoingFrame>

    suspend fun updateStatus(messageId: String, status: MessageStatus)

    suspend fun markConversationRead(peerId: String)

    /** Borra los mensajes caducados y devuelve cuantos se eliminaron. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int

    suspend fun deleteMessage(messageId: String)

    suspend fun clearConversation(peerId: String)

    /** Mensajes que aun no se han entregado (para reintentos al reconectar). */
    suspend fun pendingMessages(): List<Message>

    suspend fun getMessage(messageId: String): Message?

    /** Guarda un adjunto recibido (cifrado en reposo). */
    suspend fun storeAttachment(
        peerId: String,
        messageId: String,
        bytes: ByteArray,
        name: String,
        mimeType: String,
        durationMs: Long? = null,
    ): Attachment

    /** Descifra un adjunto a la cache para reproducirlo o compartirlo. */
    suspend fun openAttachment(peerId: String, attachment: Attachment): java.io.File?
}

interface PeerRepository {

    fun observePeers(): Flow<List<Peer>>

    suspend fun upsert(peer: Peer)

    suspend fun forget(peerId: String)

    suspend fun find(peerId: String): Peer?

    suspend fun all(): List<Peer>

    suspend fun updateConnectionState(peerId: String, connected: Boolean)

    suspend fun setBlocked(peerId: String, blocked: Boolean)
}

interface IdentityRepository {

    suspend fun getIdentity(): NodeIdentity

    fun observeIdentity(): Flow<NodeIdentity>

    suspend fun regenerate()

    suspend fun rename(name: String)

    /** Secreto compartido derivado con ECDH a partir de la clave publica del par. */
    suspend fun sharedSecret(peerPublicKey: ByteArray): Outcome<ByteArray>

    /** Clave publica del nodo local en formato X.509. */
    suspend fun publicKeyBytes(): ByteArray

    /** Id corto del nodo (6 bytes en hex) que viaja en la cabecera de trama. */
    suspend fun nodeId(): String

    /**
     * Id corto (6 bytes en hex) de un nodo a partir de su clave publica.
     *
     * Es la pieza que falta para enrutar por la malla: la cabecera de trama
     * lleva el nodeId y NO la MAC (para no filtrar la direccion a los
     * repetidores), pero para descifrar hace falta la clave del emisor
     * original, y esa se busca por MAC. Con esto el transporte puede traducir
     * nodeId -> direccion.
     */
    suspend fun nodeIdOf(publicKey: ByteArray): String

    /** Id de 4 bytes que se anuncia por BLE. */
    suspend fun advertiseId(): ByteArray

    /** Huella de una clave publica concreta. */
    suspend fun fingerprintOf(publicKey: ByteArray): String

    suspend fun fingerprint(): String
}

/**
 * Almacén de grupos/canales. La clave de cada canal se guarda cifrada en reposo
 * y el código de acceso nunca se persiste.
 */
interface ChannelRepository {

    fun observeChannels(): Flow<List<Channel>>

    fun observeMessages(channelId: String): Flow<List<GroupMessage>>

    /** Crea el canal si no existe o se reincorpora a él. */
    suspend fun join(code: String, name: String): Outcome<Channel>

    suspend fun leave(channelId: String)

    suspend fun find(channelId: String): Channel?

    /** Cifra y persiste un mensaje de grupo saliente; devuelve la trama a difundir. */
    suspend fun enqueueGroupMessage(channelId: String, content: String): Outcome<ChannelOutgoingFrame>

    /** Descifra una trama de grupo entrante. Null si no pertenecemos al canal. */
    suspend fun decryptIncoming(payload: ByteArray): DecryptedGroupMessage?

    /** Persiste un mensaje de grupo entrante ya descifrado. */
    suspend fun persistIncomingGroup(decrypted: DecryptedGroupMessage, timestamp: Long, hops: Int, relayed: Boolean)

    /** Registra/actualiza la presencia de un miembro del canal. */
    suspend fun recordMember(channelId: String, senderId: String, senderName: String, seenAt: Long)
}

/** Mensaje de grupo saliente ya cifrado, con su trama lista para la malla. */
data class ChannelOutgoingFrame(
    val message: GroupMessage,
    val payload: ByteArray,
)

/** Mensaje de grupo entrante ya descifrado. */
data class DecryptedGroupMessage(
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
)

interface SettingsRepository {

    val settings: Flow<UserSettings>

    suspend fun current(): UserSettings

    suspend fun update(transform: (UserSettings) -> UserSettings)
}

/**
 * Transporte de la malla: anuncio/descubrimiento BLE, enlace GATT y envio de
 * tramas. Es la pieza que hace que la app funcione de verdad sin internet.
 */
interface TransportRepository {

    val status: Flow<TransportStatus>

    val scanState: Flow<ScanState>

    fun observeIncoming(): Flow<IncomingEnvelope>

    /** True si el adaptador Bluetooth existe y esta encendido. */
    fun isBluetoothEnabled(): Boolean

    suspend fun start(): Outcome<Unit>

    suspend fun stop()

    suspend fun startScan(): Outcome<Unit>

    suspend fun stopScan()

    suspend fun connect(peerId: String): Outcome<Unit>

    suspend fun disconnect(peerId: String)

    suspend fun send(
        peerId: String,
        type: FrameType,
        payload: ByteArray,
        requireAck: Boolean = true,
    ): Outcome<Unit>

    /**
     * Difunde una trama a todos los nodos enlazados (broadcast de malla). Los
     * repetidores la reenviarán; se usa para los mensajes de grupo.
     */
    suspend fun broadcast(payload: ByteArray): Outcome<Unit>

    /** Intenta enlazar con hasta [limit] nodos conocidos que estén desconectados. */
    suspend fun connectAllKnown(limit: Int = 6): Int

    /** Reintentos de la cola de mensajes pendientes. */
    suspend fun flushPending()

    /** Bytes de identidad publica que se exponen por GATT. */
    fun localIdentityBytes(): ByteArray
}

/**
 * Trama cifrada lista para enviar por el transporte. El repositorio guarda el
 * mensaje y devuelve exactamente los bytes que hay que transmitir: asi el
 * mensaje se cifra una sola vez y viaja igual que se almacena.
 */
data class OutgoingFrame(
    val messageId: String,
    val payload: ByteArray,
    /** True si se cifro con el secreto compartido (E2E) y no solo en reposo. */
    val endToEnd: Boolean,
)

/** Trama recibida por el transporte, ya reensamblada (aun sin descifrar). */
data class IncomingEnvelope(
    /** Dirección del nodo que nos la entregó (salto inmediato). */
    val senderId: String,
    val payload: ByteArray,
    val type: FrameType,
    val hops: Int,
    val receivedAt: Long = System.currentTimeMillis(),
    /** Identidad estable del emisor original (nodeId); sobrevive a los saltos. */
    val originId: String = "",
    /** Id de la trama (UUID); permite descartar duplicados de la malla. */
    val messageId: String = "",
    /**
     * Direccion del emisor ORIGINAL, ya resuelta por el transporte a partir del
     * nodeId de la cabecera. Vacio si no se pudo resolver.
     *
     * Sin esto, un mensaje reenviado por un repetidor se intentaba descifrar
     * con la clave del repetidor y se descartaba en silencio: los mensajes 1:1
     * solo funcionaban en enlace directo.
     */
    val originAddress: String = "",
)
