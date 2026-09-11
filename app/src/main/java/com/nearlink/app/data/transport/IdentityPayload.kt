package com.nearlink.app.data.transport

/**
 * Formato de la carga util de identidad que se intercambia en el handshake:
 *
 * ```
 * [1 byte  longitud del nombre]
 * [n bytes nombre en UTF-8]
 * [m bytes clave publica X.509 (P-256)]
 * ```
 */
object IdentityPayload {

    fun encode(displayName: String, publicKey: ByteArray): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val result = ByteArray(1 + nameBytes.size + publicKey.size)
        result[0] = nameBytes.size.coerceAtMost(255).toByte()
        System.arraycopy(nameBytes, 0, result, 1, nameBytes.size.coerceAtMost(255))
        System.arraycopy(publicKey, 0, result, 1 + nameBytes.size, publicKey.size)
        return result
    }

    fun decode(payload: ByteArray): DecodedIdentity? {
        if (payload.size < 2) return null
        val nameLength = payload[0].toInt() and 0xFF
        if (payload.size < 1 + nameLength + 1) return null
        val name = String(payload, 1, nameLength, Charsets.UTF_8)
        val publicKey = payload.copyOfRange(1 + nameLength, payload.size)
        return DecodedIdentity(name = name.ifBlank { "Nodo NearLink" }, publicKey = publicKey)
    }
}

data class DecodedIdentity(val name: String, val publicKey: ByteArray)
