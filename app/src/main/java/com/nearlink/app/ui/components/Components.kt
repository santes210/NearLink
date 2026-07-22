package com.nearlink.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearlink.app.data.storage.MediaStorage
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

@Composable
fun AudioWaveformVisualizer(isPlaying: Boolean, tint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "phase"
    )
    Canvas(modifier = Modifier.width(120.dp).height(32.dp)) {
        val width = size.width
        val height = size.height
        val barCount = 16
        val barWidth = width / (barCount * 2)
        for (i in 0 until barCount) {
            val x = i * (barWidth * 2) + barWidth
            val multiplier = if (isPlaying) (0.3f + 0.7f * kotlin.math.abs(sin(phase + i))) else 0.3f
            val barHeight = height * multiplier
            drawLine(
                color = tint,
                start = Offset(x, height / 2 - barHeight / 2),
                end = Offset(x, height / 2 + barHeight / 2),
                strokeWidth = barWidth
            )
        }
    }
}

/** Previsualiza una imagen local (path/Uri) con submuestreo para no saturar memoria. */
@Composable
fun LocalImagePreview(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val reqW = 720
                var sample = 1
                while (bounds.outWidth / sample > reqW || bounds.outHeight / sample > reqW) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
        }
        bitmap = loaded
        if (loaded == null) failed = true
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "imagen",
            modifier = modifier,
            contentScale = ContentScale.FillWidth
        )
    } else if (failed) {
        Text("No se pudo cargar la imagen", fontSize = 12.sp, color = Color.Gray)
    } else {
        Text("Cargando imagen…", fontSize = 12.sp, color = Color.Gray)
    }
}

/** Reproductor de nota de voz con MediaPlayer. */
@Composable
fun VoicePlayer(path: String?, tint: Color) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(path) {
        onDispose { runCatching { player?.release() }; player = null; playing = false }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (path == null) return@IconButton
            if (playing) {
                runCatching { player?.pause() }
                playing = false
            } else {
                runCatching {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(context, Uri.parse(path))
                        setOnCompletionListener { playing = false }
                        prepare()
                        start()
                    }
                    playing = true
                }
            }
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Reproducir voz", tint = tint)
        }
        AudioWaveformVisualizer(isPlaying = playing, tint = tint)
    }
}

@Composable
fun ChatBubble(message: Message, isMe: Boolean) {
    val bubbleColor = when {
        message.isSos -> MaterialTheme.colorScheme.errorContainer
        isMe -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        message.isSos -> MaterialTheme.colorScheme.onErrorContainer
        isMe -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isMe) 20.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 20.dp
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp),
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.isSos) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = "SOS", tint = textColor)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "¡ALERTA SOS EMERGENCIA!", fontWeight = FontWeight.Bold, color = textColor, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                when {
                    message.type == MessageType.VOICE -> {
                        VoicePlayer(path = message.localFilePath, tint = textColor)
                    }
                    message.type == MessageType.FILE -> {
                        if (MediaStorage.isImage(message.fileName) && message.localFilePath != null) {
                            LocalImagePreview(
                                path = message.localFilePath!!,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = message.fileName ?: "imagen", fontWeight = FontWeight.Bold, color = textColor, fontSize = 13.sp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = "Archivo", tint = textColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = message.fileName ?: "Archivo", fontWeight = FontWeight.Bold, color = textColor, fontSize = 14.sp)
                                    Text(text = message.content, color = textColor, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    else -> {
                        Text(text = message.content, color = textColor, fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.ttlSeconds > 0) {
                        Icon(Icons.Default.Timer, contentDescription = "TTL", modifier = Modifier.size(10.dp), tint = textColor.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "${message.ttlSeconds}s", fontSize = 10.sp, color = textColor.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (message.isEncrypted) {
                        Icon(Icons.Default.Lock, contentDescription = "Cifrado", modifier = Modifier.size(10.dp), tint = textColor.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Text(text = "12:34", fontSize = 10.sp, color = textColor.copy(alpha = 0.7f))
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val statusIcon = when (message.status) {
                            MessageStatus.SENDING -> Icons.Default.AccessTime
                            MessageStatus.SENT -> Icons.Default.Check
                            MessageStatus.DELIVERED -> Icons.Default.DoneAll
                            MessageStatus.READ -> Icons.Default.DoneAll
                            MessageStatus.FAILED -> Icons.Default.Error
                        }
                        Icon(statusIcon, contentDescription = "Estado", modifier = Modifier.size(12.dp), tint = if (message.status == MessageStatus.READ) MaterialTheme.colorScheme.primary else textColor.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
fun RssiIndicator(rssi: Int) {
    val signalText = when {
        rssi > -60 -> "Excelente"
        rssi > -75 -> "Bueno"
        else -> "Débil"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SignalCellularAlt, contentDescription = "Señal", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "$rssi dBm ($signalText)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
