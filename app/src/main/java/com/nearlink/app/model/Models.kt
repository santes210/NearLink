
package com.nearlink.app.model

/**
 * Modelos legacy. @deprecated Migrar a [com.nearlink.app.domain.model].
 * Se mantienen para no romper imports antiguos, pero toda la lógica de
 * dominio (ViewModel, Repository, Service) debe usar domain.model.
 */
@Deprecated("Usar com.nearlink.app.domain.model.PeerDevice")
data class PeerDevice(
    val id: String,
    val name: String,
    val address: String,
    val rssi: Int = -75,
    val isConnected: Boolean = false,
    val pin: String = "4821",
    val publicKeyFingerprint: String = "A7F3:9C21:44E2"
)

@Deprecated("Usar com.nearlink.app.domain.model.MessageType")
enum class MessageType {
    TEXT, VOICE, FILE, SOS
}

@Deprecated("Usar com.nearlink.app.domain.model.MessageStatus")
enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED
}

@Deprecated("Usar com.nearlink.app.domain.model.Message")
data class Message(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    @Suppress("DEPRECATION") val status: MessageStatus = MessageStatus.SENT,
    val isEncrypted: Boolean = true,
    val fileName: String? = null,
    val ttlSeconds: Int = 0, // 0 = permanent, >0 = self-destruct countdown
    val isSos: Boolean = false
)
