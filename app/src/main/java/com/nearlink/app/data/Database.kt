
package com.nearlink.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val type: String,
    val status: String,
    val isEncrypted: Boolean,
    val fileName: String?,
    val ttlSeconds: Int,
    val isSos: Boolean
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE senderId = :peerId OR recipientId = :peerId ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("DELETE FROM messages WHERE ttlSeconds > 0 AND (:currentTime - timestamp) > (ttlSeconds * 1000)")
    suspend fun deleteExpiredMessages(currentTime: Long)
}

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class NearLinkDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: NearLinkDatabase? = null

        fun getDatabase(context: Context): NearLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NearLinkDatabase::class.java,
                    "nearlink_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
