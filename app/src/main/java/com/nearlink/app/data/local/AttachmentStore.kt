package com.nearlink.app.data.local

import com.nearlink.app.data.crypto.CryptoManager
import com.nearlink.app.domain.model.Attachment
import java.io.File

/**
 * Almacen de adjuntos (notas de voz y ficheros).
 *
 * Todo se guarda CIFRADO con la misma clave que el mensaje (secreto compartido
 * con el par, o clave de reposo si aun no hay handshake), y solo se descifra en
 * una carpeta de cache cuando el usuario va a reproducirlo/abrirlo.
 */
class AttachmentStore(filesDir: File, private val crypto: CryptoManager) {

    private val storageDir = File(filesDir, "attachments").apply { mkdirs() }
    private val cacheDir = File(filesDir, "attachment-cache").apply { mkdirs() }

    fun save(
        key: ByteArray,
        id: String,
        bytes: ByteArray,
        name: String,
        mimeType: String,
        durationMs: Long? = null,
    ): Attachment {
        val sealed = crypto.encrypt(bytes, key)
        val target = File(storageDir, "$id.enc")
        target.writeBytes(sealed.encode().toByteArray(Charsets.UTF_8))
        return Attachment(
            path = target.absolutePath,
            name = name,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            durationMs = durationMs,
        )
    }

    fun read(key: ByteArray, attachment: Attachment): ByteArray? {
        val source = File(attachment.path)
        if (!source.exists()) return null
        val box = com.nearlink.app.data.crypto.SealedBox.decode(source.readText()) ?: return null
        return runCatching { crypto.decrypt(box, key) }.getOrNull()
    }

    /** Descifra a la cache para poder reproducir/abrir con otra app. */
    fun export(key: ByteArray, attachment: Attachment): File? {
        val bytes = read(key, attachment) ?: return null
        val target = File(cacheDir, attachment.name)
        target.writeBytes(bytes)
        return target
    }

    fun delete(attachment: Attachment) {
        runCatching { File(attachment.path).delete() }
    }

    fun clearCache() {
        runCatching { cacheDir.deleteRecursively() }
        cacheDir.mkdirs()
    }
}
