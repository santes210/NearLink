package com.nearlink.app.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Grabador de notas de voz con MediaRecorder (AAC / MPEG-4). */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start() {
        val file = File.createTempFile("nearlink_voice_${System.currentTimeMillis()}", ".m4a", context.cacheDir)
        outputFile = file
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64000)
        r.setAudioSamplingRate(44100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
    }

    /** Detiene y devuelve el archivo de audio grabado (o null si falló). */
    fun stop(): File? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            outputFile
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            null
        }
    }
}
