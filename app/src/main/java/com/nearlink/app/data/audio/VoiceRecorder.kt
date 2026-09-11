package com.nearlink.app.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Resultado de una grabacion. */
data class Recording(val file: File, val durationMs: Long)

/**
 * Grabador de notas de voz real (MediaRecorder / AAC en contenedor M4A).
 * Requiere el permiso RECORD_AUDIO en tiempo de ejecucion.
 */
@SuppressLint("MissingPermission")
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): File? {
        stop()
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val mediaRecorder = runCatching {
            val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse {
            runCatching { file.delete() }
            return null
        }
        recorder = mediaRecorder
        output = file
        startedAt = System.currentTimeMillis()
        return file
    }

    /** Amplitud instantanea normalizada 0..1 para el visualizador. */
    fun amplitude(): Float {
        val current = recorder ?: return 0f
        return runCatching {
            val max = current.maxAmplitude.coerceAtLeast(1)
            (max / 20_000f).coerceIn(0f, 1f)
        }.getOrDefault(0f)
    }

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    fun stop(): Recording? {
        val current = recorder ?: return null
        val file = output ?: return null
        val duration = System.currentTimeMillis() - startedAt
        runCatching {
            current.stop()
            current.release()
        }
        recorder = null
        output = null
        startedAt = 0L
        return if (file.exists() && file.length() > 0) Recording(file, duration) else null
    }

    fun cancel() {
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        output?.let { runCatching { it.delete() } }
        output = null
        startedAt = 0L
    }
}

/** Reproductor de notas de voz (MediaPlayer). */
class VoicePlayer {

    private var player: MediaPlayer? = null
    private var playingFile: String? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun playingPath(): String? = playingFile

    @SuppressLint("MissingPermission")
    fun toggle(path: String, onCompleted: () -> Unit = {}) {
        if (playingFile == path && player?.isPlaying == true) {
            stop()
            return
        }
        stop()
        val mediaPlayer = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    playingFile = null
                    onCompleted()
                }
                prepare()
                start()
            }
        }.getOrNull() ?: return
        player = mediaPlayer
        playingFile = path
    }

    fun duration(): Int = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    fun position(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        playingFile = null
    }
}
