package com.nearlink.app.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.nearlink.app.data.local.entity.ChannelEntity
import com.nearlink.app.data.local.entity.ChannelMemberEntity
import com.nearlink.app.data.local.entity.ChannelRow
import com.nearlink.app.data.local.entity.GroupMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query(
        """
        SELECT
            c.id AS id,
            c.name AS name,
            c.createdAt AS createdAt,
            c.lastActivity AS lastActivity,
            (SELECT COUNT(*) FROM channel_members m WHERE m.channelId = c.id) AS memberCount
        FROM channels c
        ORDER BY c.lastActivity DESC
        """,
    )
    fun observeChannels(): Flow<List<ChannelRow>>

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    suspend fun find(channelId: String): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channel_members WHERE channelId = :channelId")
    suspend fun countMembers(channelId: String): Int

    @Upsert
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id = :channelId")
    suspend fun deleteChannel(channelId: String)

    @Query("UPDATE channels SET lastActivity = :at WHERE id = :channelId")
    suspend fun touchLastActivity(channelId: String, at: Long)

    @Query("SELECT * FROM group_messages WHERE channelId = :channelId ORDER BY timestamp ASC")
    fun observeMessages(channelId: String): Flow<List<GroupMessageEntity>>

    @Upsert
    suspend fun upsertMessage(message: GroupMessageEntity)

    @Query("DELETE FROM group_messages WHERE channelId = :channelId")
    suspend fun deleteMessages(channelId: String)

    @Upsert
    suspend fun upsertMember(member: ChannelMemberEntity)

    @Query("DELETE FROM channel_members WHERE channelId = :channelId")
    suspend fun deleteMembers(channelId: String)
}
