package com.nearlink.app.data.crypto

/**
 * Formato de la trama de grupo: es el payload del paquete de malla cuando el
 * tipo es [com.nearlink.app.data.transport.Packet.TYPE_GROUP].
 *
 * ```
 * [16] channelId
 * [6]  senderNodeId (identidad estable del emisor)
 * [1]  longitud del nombre del emisor
 * [n]  nombre UTF-8
 * [16] salt (derivación de clave única por mensaje)
 * [12] iv del AES-GCM
 * [..] ciphertext (AES-256-GCM con AAD = channelId + senderNodeId + nombre)
 * ```
 *
 * El AAD vincula el ciphertext al canal, al emisor y al nombre mostrado, de
 * modo que un repetidor de la malla no puede alterar ninguno de los tres sin
 * romper la etiqueta de autenticación del GCM.
 */
object GroupWireFormat {

    const val CHANNEL_ID_BYTES = GroupKeys.CHANNEL_ID_BYTES
    const val NODE_ID_BYTES = 6
    const val SALT_BYTES = GroupKeys.SALT_BYTES
    const val IV_BYTES = 12
    const val NAME_MAX_BYTES = 127

    fun encode(
        channelId: ByteArray,
        senderNodeId: ByteArray,
        senderName: String,
        salt: ByteArray,
        box: SealedBox,
    ): ByteArray {
        require(channelId.size == CHANNEL_ID_BYTES) { "channelId debe tener $CHANNEL_ID_BYTES bytes" }
        require(senderNodeId.size == NODE_ID_BYTES) { "senderNodeId debe tener $NODE_ID_BYTES bytes" }
        require(salt.size == SALT_BYTES) { "salt debe tener $SALT_BYTES bytes" }
        require(box.iv.size == IV_BYTES) { "iv debe tener $IV_BYTES bytes" }
        val nameBytes = senderName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(NAME_MAX_BYTES)

        val result = ByteArray(
            CHANNEL_ID_BYTES + NODE_ID_BYTES + 1 + nameLen + SALT_BYTES + box.iv.size + box.ciphertext.size,
        )
        var cursor = 0
        System.arraycopy(channelId, 0, result, cursor, CHANNEL_ID_BYTES); cursor += CHANNEL_ID_BYTES
        System.arraycopy(senderNodeId, 0, result, cursor, NODE_ID_BYTES); cursor += NODE_ID_BYTES
        result[cursor++] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, result, cursor, nameLen); cursor += nameLen
        System.arraycopy(salt, 0, result, cursor, SALT_BYTES); cursor += SALT_BYTES
        System.arraycopy(box.iv, 0, result, cursor, box.iv.size); cursor += box.iv.size
        System.arraycopy(box.ciphertext, 0, result, cursor, box.ciphertext.size)
        return result
    }

    fun decode(bytes: ByteArray): GroupFrame? {
        if (bytes.size < CHANNEL_ID_BYTES + NODE_ID_BYTES + 1 + SALT_BYTES + IV_BYTES) return null
        var cursor = 0
        val channelId = bytes.copyOfRange(cursor, cursor + CHANNEL_ID_BYTES); cursor += CHANNEL_ID_BYTES
        val senderNodeId = bytes.copyOfRange(cursor, cursor + NODE_ID_BYTES); cursor += NODE_ID_BYTES
        val nameLen = bytes[cursor].toInt() and 0xFF; cursor += 1
        if (bytes.size < cursor + nameLen + SALT_BYTES + IV_BYTES) return null
        val senderName = String(bytes, cursor, nameLen, Charsets.UTF_8); cursor += nameLen
        val salt = bytes.copyOfRange(cursor, cursor + SALT_BYTES); cursor += SALT_BYTES
        val iv = bytes.copyOfRange(cursor, cursor + IV_BYTES); cursor += IV_BYTES
        val ciphertext = bytes.copyOfRange(cursor, bytes.size)
        return GroupFrame(
            channelId = channelId,
            senderNodeId = senderNodeId,
            senderName = senderName,
            salt = salt,
            box = SealedBox(ciphertext = ciphertext, iv = iv),
        )
    }
}

/** Trama de grupo ya decodificada, lista para descifrar. */
data class GroupFrame(
    val channelId: ByteArray,
    val senderNodeId: ByteArray,
    val senderName: String,
    val salt: ByteArray,
    val box: SealedBox,
)
