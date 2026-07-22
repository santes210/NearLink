
package com.nearlink.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import kotlin.math.sin

@Composable
fun AudioWaveformVisualizer(isPlaying: Boolean, tint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
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

                if (message.type == MessageType.VOICE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var isPlaying by remember { mutableStateOf(false) }
                        IconButton(onClick = { isPlaying = !isPlaying }) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = textColor)
                        }
                        AudioWaveformVisualizer(isPlaying = isPlaying, tint = textColor)
                    }
                } else if (message.type == MessageType.FILE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WifiTethering, contentDescription = "Wi-Fi Direct", tint = textColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = message.fileName ?: "Archivo Wi-Fi Direct", fontWeight = FontWeight.Bold, color = textColor, fontSize = 14.sp)
                            Text(text = message.content, color = textColor, fontSize = 12.sp)
                        }
                    }
                } else {
                    Text(text = message.content, color = textColor, fontSize = 15.sp)
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
