package com.nearlink.app.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.nearlink.app.data.local.entity.ConversationRow
import com.nearlink.app.data.local.entity.MessageEntity
import com.nearlink.app.data.local.entity.PeerEntity
import com.nearlink.app.data.local.entity.SettingEntity
import com.nearlink.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun observeForPeer(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peerId = :peerId")
    suspend fun forPeer(peerId: String): List<MessageEntity>

    @Query(
        """
        SELECT
            p.id AS peerId,
            p.name AS peerName,
            COALESCE(MAX(m.timestamp), p.lastSeen) AS lastActivity,
            COALESCE(SUM(CASE WHEN m.outgoing = 0 AND m.read = 0 THEN 1 ELSE 0 END), 0) AS unread,
            CASE WHEN p.connectionState = 'CONNECTED' THEN 1 ELSE 0 END AS isConnected,
            p.rssi AS rssi,
            p.fingerprint AS fingerprint,
            m.ciphertext AS previewCiphertext,
            m.iv AS previewIv,
            m.type AS previewType
        FROM peers p
        LEFT JOIN messages m
            ON m.peerId = p.id
           AND m.timestamp = (SELECT MAX(m2.timestamp) FROM messages m2 WHERE m2.peerId = p.id)
        GROUP BY p.id
        ORDER BY lastActivity DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET read = 1 WHERE peerId = :peerId AND outgoing = 0")
    suspend fun markRead(peerId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun find(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status IN ('QUEUED', 'SENDING') ORDER BY timestamp ASC")
    suspend fun pending(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteConversation(peerId: String)

    /**
     * Borra los mensajes caducados. Un mensaje caduca cuando tiene TTL y ya
     * paso su fecha de expiracion.
     */
    @Query("SELECT * FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun expired(now: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE expiresAt IS NOT NULL AND expiresAt > :now")
    suspend fun countPendingExpiration(now: Long): Int

    @Transaction
    suspend fun replaceAll(messages: List<MessageEntity>) {
        messages.forEach { upsert(it) }
    }
}

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers WHERE blocked = 0 ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE id = :peerId LIMIT 1")
    suspend fun find(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    suspend fun all(): List<PeerEntity>

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET connectionState = :state WHERE id = :peerId")
    suspend fun updateConnectionState(peerId: String, state: String)

    @Query("UPDATE peers SET blocked = :blocked WHERE id = :peerId")
    suspend fun setBlocked(peerId: String, blocked: Boolean)

    @Query("DELETE FROM peers WHERE id = :peerId")
    suspend fun delete(peerId: String)
}

@Dao
interface SettingsDao {

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Query("SELECT value FROM settings WHERE key = :key LIMIT 1")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}
