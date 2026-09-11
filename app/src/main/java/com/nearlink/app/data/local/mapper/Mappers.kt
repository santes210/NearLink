package com.nearlink.app.data.local.mapper

import java.util.Base64
import com.nearlink.app.data.local.entity.MessageEntity
import com.nearlink.app.data.local.entity.PeerEntity
import com.nearlink.app.domain.model.Attachment
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.Peer

fun Peer.toEntity(): PeerEntity = PeerEntity(
    id = id,
    name = name,
    address = address,
    rssi = rssi,
    lastSeen = lastSeen,
    connectionState = connectionState,
    fingerprint = fingerprint,
    publicKey = publicKey?.let { Base64.getEncoder().encodeToString(it) },
    relay = relay,
    hops = hops,
    verified = verified,
    blocked = blocked,
)

fun PeerEntity.toDomain(): Peer = Peer(
    id = id,
    name = name,
    address = address,
    rssi = rssi,
    lastSeen = lastSeen,
    connectionState = connectionState,
    fingerprint = fingerprint,
    publicKey = publicKey?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
    relay = relay,
    hops = hops,
    verified = verified,
    blocked = blocked,
)

fun Message.toEntity(
    sealedContent: Pair<String, String>,
    status: MessageStatus,
    attachment: Attachment? = this.attachment,
): MessageEntity = MessageEntity(
    id = id,
    peerId = peerId,
    outgoing = outgoing,
    ciphertext = sealedContent.first,
    iv = sealedContent.second,
    type = type,
    status = status,
    timestamp = timestamp,
    ttlSeconds = ttlSeconds,
    expiresAt = expiresAt,
    attachmentPath = attachment?.path,
    attachmentName = attachment?.name,
    attachmentMime = attachment?.mimeType,
    attachmentSize = attachment?.sizeBytes,
    durationMs = attachment?.durationMs,
    hops = hops,
    relayed = relayed,
    read = !outgoing && status == MessageStatus.READ,
)

fun MessageEntity.toDomain(content: String): Message = Message(
    id = id,
    peerId = peerId,
    outgoing = outgoing,
    content = content,
    timestamp = timestamp,
    type = type,
    status = status,
    attachment = attachmentOf(),
    ttlSeconds = ttlSeconds,
    expiresAt = expiresAt,
    hops = hops,
    relayed = relayed,
)

fun MessageEntity.attachmentOf(): Attachment? {
    val path = attachmentPath ?: return null
    val name = attachmentName ?: return null
    val mime = attachmentMime ?: "application/octet-stream"
    return Attachment(
        path = path,
        name = name,
        mimeType = mime,
        sizeBytes = attachmentSize ?: 0L,
        durationMs = durationMs,
    )
}

fun MessageType.isFileLike(): Boolean = this == MessageType.FILE || this == MessageType.VOICE

fun String.toMessageTypeOrNull(): MessageType? = runCatching { MessageType.valueOf(this) }.getOrNull()

fun String.toConnectionStateOrNull(): ConnectionState? =
    runCatching { ConnectionState.valueOf(this) }.getOrNull()
