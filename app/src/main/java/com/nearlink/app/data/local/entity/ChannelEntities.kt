package com.nearlink.app.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Canal/grupo en reposo. La clave maestra del canal viaja CIFRADA (AES-256-GCM
 * con la clave local del dispositivo); el código de acceso nunca se guarda.
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** Clave maestra del canal cifrada en reposo (base64). */
    @ColumnInfo(name = "keyCiphertext") val keyCiphertext: String,
    @ColumnInfo(name = "keyIv") val keyIv: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "lastActivity") val lastActivity: Long,
)

/**
 * Mensaje de grupo en reposo. El contenido viaja cifrado con una clave única
 * por mensaje derivada de la clave del canal; `salt` permite rederivarla.
 */
@Entity(
    tableName = "group_messages",
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["channelId", "timestamp"]),
    ],
)
data class GroupMessageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "channelId") val channelId: String,
    @ColumnInfo(name = "senderId") val senderId: String,
    @ColumnInfo(name = "senderName") val senderName: String,
    @ColumnInfo(name = "outgoing") val outgoing: Boolean,
    @ColumnInfo(name = "ciphertext") val ciphertext: String,
    @ColumnInfo(name = "iv") val iv: String,
    @ColumnInfo(name = "salt") val salt: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "hops") val hops: Int,
    @ColumnInfo(name = "relayed") val relayed: Boolean,
)

/** Miembro conocido de un canal (nodo remoto que ha escrito en él). */
@Entity(
    tableName = "channel_members",
    primaryKeys = ["channelId", "memberId"],
    indices = [Index(value = ["channelId"])],
)
data class ChannelMemberEntity(
    @ColumnInfo(name = "channelId") val channelId: String,
    @ColumnInfo(name = "memberId") val memberId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "lastSeen") val lastSeen: Long,
)

/** Fila agregada de la lista de grupos (canal + recuento de miembros). */
data class ChannelRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastActivity: Long,
    val memberCount: Int,
)
