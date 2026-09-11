package com.nearlink.app.data.repository

import com.nearlink.app.data.crypto.SealedBox

/**
 * Formato de lo que viaja por la malla.
 *
 * Texto: `[1 byte len(iv)][iv][ciphertext]`
 * Fichero: `[1 byte len(nombre)][nombre][1 byte len(mime)][mime][4 bytes tamano][wire]`
 */
object WireFormat {

    fun seal(box: SealedBox): ByteArray {
        val ivLength = box.iv.size
        val result = ByteArray(1 + ivLength + box.ciphertext.size)
        result[0] = ivLength.toByte()
        System.arraycopy(box.iv, 0, result, 1, ivLength)
        System.arraycopy(box.ciphertext, 0, result, 1 + ivLength, box.ciphertext.size)
        return result
    }

    fun unseal(bytes: ByteArray): SealedBox? {
        if (bytes.size < 2) return null
        val ivLength = bytes[0].toInt() and 0xFF
        if (bytes.size < 1 + ivLength + 1) return null
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)
        return SealedBox(ciphertext = ciphertext, iv = iv)
    }

    /** Empaqueta nombre + mime + tamano + contenido cifrado. */
    fun fileFrame(name: String, mimeType: String, size: Long, box: SealedBox): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        val sealed = seal(box)
        val result = ByteArray(1 + nameBytes.size + 1 + mimeBytes.size + 4 + sealed.size)
        var cursor = 0
        result[cursor++] = nameBytes.size.coerceAtMost(255).toByte()
        System.arraycopy(nameBytes, 0, result, cursor, nameBytes.size.coerceAtMost(255))
        cursor += nameBytes.size.coerceAtMost(255)
        result[cursor++] = mimeBytes.size.coerceAtMost(255).toByte()
        System.arraycopy(mimeBytes, 0, result, cursor, mimeBytes.size.coerceAtMost(255))
        cursor += mimeBytes.size.coerceAtMost(255)
        result[cursor++] = (size shr 24).toByte()
        result[cursor++] = (size shr 16).toByte()
        result[cursor++] = (size shr 8).toByte()
        result[cursor++] = size.toByte()
        System.arraycopy(sealed, 0, result, cursor, sealed.size)
        return result
    }

    fun parseFileFrame(bytes: ByteArray): FileFrame? {
        if (bytes.size < 8) return null
        var cursor = 0
        val nameLength = bytes[cursor++].toInt() and 0xFF
        if (bytes.size < cursor + nameLength + 6) return null
        val name = String(bytes, cursor, nameLength, Charsets.UTF_8)
        cursor += nameLength
        val mimeLength = bytes[cursor++].toInt() and 0xFF
        if (bytes.size < cursor + mimeLength + 5) return null
        val mime = String(bytes, cursor, mimeLength, Charsets.UTF_8)
        cursor += mimeLength
        val size = ((bytes[cursor++].toLong() and 0xFF) shl 24) or
            ((bytes[cursor++].toLong() and 0xFF) shl 16) or
            ((bytes[cursor++].toLong() and 0xFF) shl 8) or
            (bytes[cursor++].toLong() and 0xFF)
        val sealed = WireFormat.unseal(bytes.copyOfRange(cursor, bytes.size)) ?: return null
        return FileFrame(name = name, mimeType = mime, size = size, box = sealed)
    }
}

data class FileFrame(
    val name: String,
    val mimeType: String,
    val size: Long,
    val box: SealedBox,
)
