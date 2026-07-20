
package com.nearlink.app.domain.model

sealed interface Result<out T> {
    data class Success<out T>(val data: T) : Result<T>
    data class Error(val exception: Throwable, val message: String) : Result<Nothing>
    object Loading : Result<Nothing>
}

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR
}

data class PeerDevice(
    val id: String,
    val name: String,
    val address: String,
    val rssi: Int = -75,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val pin: String = "4821",
    val publicKeyFingerprint: String = "X25519:A7F3:9C21"
)

enum class MessageType {
    TEXT, VOICE, FILE, SOS
}

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED
}

data class Message(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    val isEncrypted: Boolean = true,
    val fileName: String? = null,
    val ttlSeconds: Int = 0,
    val isSos: Boolean = false
)
