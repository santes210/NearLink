package com.nearlink.app.data.repository

import java.util.Base64
import java.util.UUID
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.core.outcomeOf
import com.nearlink.app.data.crypto.CryptoManager
import com.nearlink.app.data.crypto.GroupKeys
import com.nearlink.app.data.crypto.GroupWireFormat
import com.nearlink.app.data.crypto.SealedBox
import com.nearlink.app.data.local.dao.ChannelDao
import com.nearlink.app.data.local.entity.ChannelEntity
import com.nearlink.app.data.local.entity.ChannelMemberEntity
import com.nearlink.app.data.local.entity.ChannelRow
import com.nearlink.app.data.local.entity.GroupMessageEntity
import com.nearlink.app.data.transport.Packet
import com.nearlink.app.domain.model.Channel
import com.nearlink.app.domain.model.GroupMessage
import com.nearlink.app.domain.repository.ChannelOutgoingFrame
import com.nearlink.app.domain.repository.ChannelRepository
import com.nearlink.app.domain.repository.DecryptedGroupMessage
import com.nearlink.app.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * Repositorio de grupos/canales.
 *
 * Seguridad en reposo: la clave maestra de cada canal se guarda cifrada con la
 * clave local del dispositivo (AES-256-GCM) y el contenido de cada mensaje se
 * cifra con una clave única por mensaje derivada por HKDF de la clave del canal.
 * El código de acceso nunca se escribe en disco.
 */
class ChannelRepositoryImpl(
    private val dao: ChannelDao,
    private val crypto: CryptoManager,
    private val identityRepository: IdentityRepository,
    private val dispatchers: CoroutineDispatchers,
) : ChannelRepository {

    override fun observeChannels(): Flow<List<Channel>> =
        dao.observeChannels().map { rows -> rows.map { it.toChannel() } }

    /**
     * Mensajes del grupo. El descifrado sale del hilo de la UI con `flowOn`:
     * `GroupChatViewModel` recoge este flujo con `stateIn(viewModelScope, ...)`
     * (`Dispatchers.Main.immediate`), y aqui habia una consulta a la BD + una
     * derivacion HKDF por CADA mensaje en cada emision.
     */
    override fun observeMessages(channelId: String): Flow<List<GroupMessage>> =
        dao.observeMessages(channelId)
            .mapLatest { entities ->
                // La clave maestra del canal se resuelve una sola vez.
                val groupKey = storedGroupKey(channelId) ?: return@mapLatest emptyList<GroupMessage>()
                entities.mapNotNull { entity -> entity.toGroupMessage(channelId, groupKey) }
            }
            .flowOn(dispatchers.default)

    override suspend fun join(code: String, name: String): Outcome<Channel> = withContext(dispatchers.io) {
        outcomeOf {
            val normalized = code.trim()
            require(normalized.isNotEmpty()) { "Escribe un código de grupo" }
            val channelId = GroupKeys.channelIdHex(normalized)
            val existing = dao.find(channelId)
            if (existing == null) {
                val groupKey = GroupKeys.groupKey(normalized)
                val box = crypto.encryptLocal(groupKey)
                dao.upsertChannel(
                    ChannelEntity(
                        id = channelId,
                        name = name.ifBlank { "Grupo ${channelId.take(6)}" },
                        keyCiphertext = box.ciphertext.toBase64(),
                        keyIv = box.iv.toBase64(),
                        createdAt = System.currentTimeMillis(),
                        lastActivity = System.currentTimeMillis(),
                    ),
                )
            } else if (name.isNotBlank()) {
                dao.upsertChannel(existing.copy(name = name))
            }
            find(channelId) ?: error("No se pudo crear el grupo")
        }
    }

    override suspend fun leave(channelId: String) {
        withContext(dispatchers.io) {
            dao.deleteMessages(channelId)
            dao.deleteMembers(channelId)
            dao.deleteChannel(channelId)
        }
    }

    override suspend fun find(channelId: String): Channel? = withContext(dispatchers.io) {
        val entity = dao.find(channelId) ?: return@withContext null
        Channel(
            id = entity.id,
            name = entity.name,
            createdAt = entity.createdAt,
            lastActivity = entity.lastActivity,
            memberCount = dao.countMembers(channelId),
        )
    }

    override suspend fun enqueueGroupMessage(channelId: String, content: String): Outcome<ChannelOutgoingFrame> =
        withContext(dispatchers.io) {
            outcomeOf {
                val groupKey = storedGroupKey(channelId) ?: error("No perteneces a este grupo")
                val identity = identityRepository.getIdentity()
                val senderId = identityRepository.nodeId()
                val senderIdBytes = Packet.macToBytes(senderId)
                val senderName = identity.displayName.truncateUtf8(GroupWireFormat.NAME_MAX_BYTES)
                val nameBytes = senderName.toByteArray(Charsets.UTF_8)
                val channelIdBytes = GroupKeys.hexToBytes(channelId)

                val salt = crypto.randomBytes(GroupKeys.SALT_BYTES)
                val messageKey = GroupKeys.messageKey(groupKey, salt)
                val aad = channelIdBytes + senderIdBytes + nameBytes
                val box = crypto.encryptAad(content.toByteArray(Charsets.UTF_8), messageKey, aad)

                val payload = GroupWireFormat.encode(channelIdBytes, senderIdBytes, senderName, salt, box)
                val now = System.currentTimeMillis()
                val message = GroupMessage(
                    id = UUID.randomUUID().toString(),
                    channelId = channelId,
                    senderId = senderId,
                    senderName = senderName,
                    outgoing = true,
                    content = content,
                    timestamp = now,
                    hops = 0,
                    relayed = false,
                )
                dao.upsertMessage(
                    GroupMessageEntity(
                        id = message.id,
                        channelId = channelId,
                        senderId = senderId,
                        senderName = senderName,
                        outgoing = true,
                        ciphertext = box.ciphertext.toBase64(),
                        iv = box.iv.toBase64(),
                        salt = salt.toBase64(),
                        timestamp = now,
                        hops = 0,
                        relayed = false,
                    ),
                )
                dao.touchLastActivity(channelId, now)
                ChannelOutgoingFrame(message = message, payload = payload)
            }
        }

    override suspend fun decryptIncoming(payload: ByteArray): DecryptedGroupMessage? = withContext(dispatchers.io) {
        val frame = GroupWireFormat.decode(payload) ?: return@withContext null
        val channelId = GroupKeys.hexOf(frame.channelId)
        val groupKey = storedGroupKey(channelId) ?: return@withContext null
        val messageKey = GroupKeys.messageKey(groupKey, frame.salt)
        val aad = frame.channelId + frame.senderNodeId + frame.senderName.toByteArray(Charsets.UTF_8)
        val bytes = runCatching { crypto.decryptAad(frame.box, messageKey, aad) }.getOrNull() ?: return@withContext null
        DecryptedGroupMessage(
            channelId = channelId,
            senderId = Packet.bytesToMac(frame.senderNodeId),
            senderName = frame.senderName,
            content = String(bytes, Charsets.UTF_8),
        )
    }

    override suspend fun persistIncomingGroup(
        decrypted: DecryptedGroupMessage,
        timestamp: Long,
        hops: Int,
        relayed: Boolean,
    ) {
        withContext(dispatchers.io) {
            val groupKey = storedGroupKey(decrypted.channelId) ?: return@withContext
            val channelIdBytes = GroupKeys.hexToBytes(decrypted.channelId)
            val senderIdBytes = Packet.macToBytes(decrypted.senderId)
            val senderName = decrypted.senderName.truncateUtf8(GroupWireFormat.NAME_MAX_BYTES)
            val nameBytes = senderName.toByteArray(Charsets.UTF_8)
            val aad = channelIdBytes + senderIdBytes + nameBytes

            val salt = crypto.randomBytes(GroupKeys.SALT_BYTES)
            val messageKey = GroupKeys.messageKey(groupKey, salt)
            val box = crypto.encryptAad(decrypted.content.toByteArray(Charsets.UTF_8), messageKey, aad)

            val now = timestamp.takeIf { it <= System.currentTimeMillis() } ?: System.currentTimeMillis()
            dao.upsertMessage(
                GroupMessageEntity(
                    id = UUID.randomUUID().toString(),
                    channelId = decrypted.channelId,
                    senderId = decrypted.senderId,
                    senderName = senderName,
                    outgoing = false,
                    ciphertext = box.ciphertext.toBase64(),
                    iv = box.iv.toBase64(),
                    salt = salt.toBase64(),
                    timestamp = now,
                    hops = hops,
                    relayed = relayed,
                ),
            )
            dao.touchLastActivity(decrypted.channelId, now)
        }
    }

    override suspend fun recordMember(channelId: String, senderId: String, senderName: String, seenAt: Long) {
        withContext(dispatchers.io) {
            dao.upsertMember(
                ChannelMemberEntity(
                    channelId = channelId,
                    memberId = senderId,
                    name = senderName,
                    lastSeen = seenAt,
                ),
            )
        }
    }

    // --------------------------------------------------------------- privado

    /**
     * Clave maestra del canal, descifrada con la clave local del dispositivo.
     * Devuelve null en lugar de propagar el fallo: un error del Keystore no
     * debe tumbar el flujo de mensajes que consume la UI.
     */
    private suspend fun storedGroupKey(channelId: String): ByteArray? =
        withContext(dispatchers.io) {
            runCatching {
                val entity = dao.find(channelId) ?: return@runCatching null
                val box = SealedBox(
                    ciphertext = Base64.getDecoder().decode(entity.keyCiphertext),
                    iv = Base64.getDecoder().decode(entity.keyIv),
                )
                crypto.decryptLocal(box)
            }.getOrNull()
        }

    private fun GroupMessageEntity.toGroupMessage(channelId: String, groupKey: ByteArray): GroupMessage? {
        val box = runCatching {
            SealedBox(
                ciphertext = Base64.getDecoder().decode(ciphertext),
                iv = Base64.getDecoder().decode(iv),
            )
        }.getOrNull() ?: return null
        val salt = runCatching { Base64.getDecoder().decode(salt) }.getOrNull() ?: return null
        // Un mensaje corrupto no puede tumbar el flujo que consume la UI.
        val messageKey = runCatching { GroupKeys.messageKey(groupKey, salt) }.getOrNull() ?: return null
        val channelIdBytes = GroupKeys.hexToBytes(channelId)
        val senderIdBytes = Packet.macToBytes(senderId)
        val nameBytes = senderName.truncateUtf8(GroupWireFormat.NAME_MAX_BYTES).toByteArray(Charsets.UTF_8)
        val aad = channelIdBytes + senderIdBytes + nameBytes
        val content = runCatching { crypto.decryptAad(box, messageKey, aad) }.getOrNull() ?: return null
        return GroupMessage(
            id = id,
            channelId = channelId,
            senderId = senderId,
            senderName = senderName,
            outgoing = outgoing,
            content = String(content, Charsets.UTF_8),
            timestamp = timestamp,
            hops = hops,
            relayed = relayed,
        )
    }

    private fun ChannelRow.toChannel(): Channel = Channel(
        id = id,
        name = name,
        createdAt = createdAt,
        lastActivity = lastActivity,
        memberCount = memberCount,
    )

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    /** Recorta la cadena para que su codificación UTF-8 no supere [maxBytes], sin partir caracteres. */
    private fun String.truncateUtf8(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        var end = length
        while (end > 0 && substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return substring(0, end)
    }
}
