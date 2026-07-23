package com.nearlink.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Calendar

/**
 * Guarda archivos multimedia recibidos/enviados en almacenamiento COMPARTIDO
 * (Galería/Música/Descargas) para que sean visibles fuera de la app, y resuelve
 * el tipo MIME a partir del nombre. Usa IS_PENDING en Android 10+ para publicar
 * el archivo correctamente.
 */
object MediaStorage {

    fun saveToSharedStorage(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String
    ): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relPath) = when {
                mimeType.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/NearLink"
                mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/NearLink"
                mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/NearLink"
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/NearLink"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, unique(displayName))
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                null
            } else {
                out.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            }
        } else {
            val baseDir = when {
                mimeType.startsWith("image") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                mimeType.startsWith("audio") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            val dir = File(baseDir, "NearLink").apply { mkdirs() }
            val file = File(dir, unique(displayName))
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    }

    private fun unique(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$base" + "_" + System.currentTimeMillis() + ext
    }

    fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "m4a", "aac", "mp3" -> "audio/mp4"
            "3gp" -> "audio/3gpp"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    fun isImage(name: String?): Boolean =
        name != null && mimeFromName(name).startsWith("image")

    fun isAudio(name: String?): Boolean =
        name != null && mimeFromName(name).startsWith("audio")
}
