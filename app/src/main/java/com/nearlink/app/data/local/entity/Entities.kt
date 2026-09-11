package com.nearlink.app.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType

/**
 * Mensaje en reposo.
 *
 * IMPORTANTE: el contenido NUNCA se guarda en claro. `ciphertext` es el cuerpo
 * cifrado con AES-256-GCM usando el secreto compartido con ese par, y `iv` es
 * el vector de inicializacion (imprescindible para poder descifrarlo).
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["peerId"]),
        Index(value = ["timestamp"]),
        Index(value = ["expiresAt"]),
        Index(value = ["peerId", "timestamp"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "peerId") val peerId: String,
    @ColumnInfo(name = "outgoing") val outgoing: Boolean,
    @ColumnInfo(name = "ciphertext") val ciphertext: String,
    @ColumnInfo(name = "iv") val iv: String,
    @ColumnInfo(name = "type") val type: MessageType,
    @ColumnInfo(name = "status") val status: MessageStatus,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "ttlSeconds") val ttlSeconds: Int,
    @ColumnInfo(name = "expiresAt") val expiresAt: Long?,
    @ColumnInfo(name = "attachmentPath") val attachmentPath: String?,
    @ColumnInfo(name = "attachmentName") val attachmentName: String?,
    @ColumnInfo(name = "attachmentMime") val attachmentMime: String?,
    @ColumnInfo(name = "attachmentSize") val attachmentSize: Long?,
    @ColumnInfo(name = "durationMs") val durationMs: Long?,
    @ColumnInfo(name = "hops") val hops: Int,
    @ColumnInfo(name = "relayed") val relayed: Boolean,
    @ColumnInfo(name = "read") val read: Boolean,
)

/** Nodo conocido de la malla. */
@Entity(
    tableName = "peers",
    indices = [Index(value = ["lastSeen"]), Index(value = ["blocked"])],
)
data class PeerEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "rssi") val rssi: Int,
    @ColumnInfo(name = "lastSeen") val lastSeen: Long,
    @ColumnInfo(name = "connectionState") val connectionState: ConnectionState,
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    /** Clave publica del par en base64 (X.509, P-256). Se usa para el ECDH. */
    @ColumnInfo(name = "publicKey") val publicKey: String?,
    @ColumnInfo(name = "relay") val relay: Boolean,
    @ColumnInfo(name = "hops") val hops: Int,
    @ColumnInfo(name = "verified") val verified: Boolean,
    @ColumnInfo(name = "blocked") val blocked: Boolean,
)

/** Preferencias clave-valor (evita anadir otra dependencia de preferences). */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** Fila agregada de la lista de chats (JOIN peers + ultimo mensaje). */
data class ConversationRow(
    val peerId: String,
    val peerName: String,
    val lastActivity: Long,
    val unread: Int,
    val isConnected: Boolean,
    val rssi: Int,
    val fingerprint: String?,
    val previewCiphertext: String?,
    val previewIv: String?,
    val previewType: MessageType?,
)
