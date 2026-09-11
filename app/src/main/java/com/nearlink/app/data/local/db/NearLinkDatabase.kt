package com.nearlink.app.data.local.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nearlink.app.data.local.dao.MessageDao
import com.nearlink.app.data.local.dao.PeerDao
import com.nearlink.app.data.local.dao.SettingsDao
import com.nearlink.app.data.local.entity.MessageEntity
import com.nearlink.app.data.local.entity.PeerEntity
import com.nearlink.app.data.local.entity.SettingEntity

@Database(
    entities = [MessageEntity::class, PeerEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NearLinkDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DB_NAME = "nearlink.db"

        @Volatile
        private var instance: NearLinkDatabase? = null

        fun get(context: Context): NearLinkDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): NearLinkDatabase =
            Room.databaseBuilder<NearLinkDatabase>(context, DB_NAME)
                // Room 3 obliga a instalar un driver de SQLite explicitamente.
                .setDriver(BundledSQLiteDriver())
                .build()
    }
}
