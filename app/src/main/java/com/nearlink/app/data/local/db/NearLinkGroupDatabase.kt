package com.nearlink.app.data.local.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nearlink.app.data.local.dao.ChannelDao
import com.nearlink.app.data.local.entity.ChannelEntity
import com.nearlink.app.data.local.entity.ChannelMemberEntity
import com.nearlink.app.data.local.entity.GroupMessageEntity

/**
 * Base de datos de grupos/canales. Vive en un fichero independiente de la base
 * de mensajes 1:1, de modo que añadir grupos no exige migrar (ni arriesgar) los
 * datos existentes.
 */
@Database(
    entities = [ChannelEntity::class, GroupMessageEntity::class, ChannelMemberEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NearLinkGroupDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao

    companion object {
        private const val DB_NAME = "nearlink_groups.db"

        @Volatile
        private var instance: NearLinkGroupDatabase? = null

        fun get(context: Context): NearLinkGroupDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): NearLinkGroupDatabase =
            Room.databaseBuilder<NearLinkGroupDatabase>(context, DB_NAME)
                .setDriver(BundledSQLiteDriver())
                .build()
    }
}
