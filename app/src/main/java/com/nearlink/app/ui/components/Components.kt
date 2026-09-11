package com.nearlink.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nearlink.app.R
import com.nearlink.app.domain.model.Attachment
import com.nearlink.app.domain.model.Conversation
import com.nearlink.app.domain.model.Message
import com.nearlink.app.domain.model.MessageStatus
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.model.SignalQuality

// ------------------------------------------------------------------ senal

@Composable
fun SignalIndicator(
    rssi: Int,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val quality = when {
        rssi >= -60 -> SignalQuality.EXCELLENT
        rssi >= -75 -> SignalQuality.GOOD
        rssi >= -90 -> SignalQuality.FAIR
        else -> SignalQuality.WEAK
    }
    val label = stringResource(
        when (quality) {
            SignalQuality.EXCELLENT -> R.string.signal_excellent
            SignalQuality.GOOD -> R.string.signal_good
            SignalQuality.FAIR -> R.string.signal_fair
            SignalQuality.WEAK -> R.string.signal_weak
        },
    )
    val color = when (quality) {
        SignalQuality.EXCELLENT, SignalQuality.GOOD -> MaterialTheme.colorScheme.primary
        SignalQuality.FAIR -> MaterialTheme.colorScheme.tertiary
        SignalQuality.WEAK -> MaterialTheme.colorScheme.error
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.SignalCellularAlt,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        if (showLabel) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.signal_value, rssi, label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -------------------------------------------------------------- listados

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(name = conversation.peerName, connected = conversation.isConnected)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.peerName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = conversation.preview.ifBlank { stringResource(R.string.home_no_preview) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                SignalIndicator(rssi = conversation.rssi)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = com.nearlink.app.ui.components.RelativeTime(conversation.lastActivity),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.unreadCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeerItem(
    peer: Peer,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    connectLabel: String = stringResource(R.string.radar_connect),
    connected: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(name = peer.name, connected = connected)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                peer.fingerprint?.let {
                    FingerprintChip(fingerprint = it, verified = peer.verified)
                }
                Spacer(Modifier.height(4.dp))
                SignalIndicator(rssi = peer.rssi)
                if (peer.relay) {
                    Spacer(Modifier.height(4.dp))
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.peer_relay)) },
                        leadingIcon = {
                            Icon(Icons.Default.DeviceHub, null, Modifier.size(AssistChipDefaults.IconSize))
                        },
                    )
                }
            }
            androidx.compose.material3.Button(onClick = onConnect, enabled = !connected) {
                Text(if (connected) stringResource(R.string.peer_connected) else connectLabel)
            }
        }
    }
}

@Composable
fun PeerAvatar(name: String, connected: Boolean, size: Dp = 46.dp) {
    val container = if (connected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (connected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(16.dp), color = container, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = content,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun FingerprintChip(fingerprint: String, verified: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (verified) Icons.Default.Lock else Icons.Default.Fingerprint,
            contentDescription = null,
            tint = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ chat

@Composable
fun ChatBubble(
    message: Message,
    isMe: Boolean,
    formattedTime: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playbackProgress: Float = 0f,
    onTogglePlayback: (() -> Unit)? = null,
    onOpenAttachment: (() -> Unit)? = null,
) {
    val container = when {
        message.isSos -> MaterialTheme.colorScheme.errorContainer
        isMe -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        message.isSos -> MaterialTheme.colorScheme.onErrorContainer
        isMe -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isMe) 22.dp else 6.dp,
                bottomEnd = if (isMe) 6.dp else 22.dp,
            ),
            color = container,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.isSos) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = content, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_sos_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = content,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                when {
                    message.type == MessageType.VOICE -> VoiceRow(
                        content = content,
                        isPlaying = isPlaying,
                        progress = playbackProgress,
                        onToggle = onTogglePlayback,
                    )

                    message.type == MessageType.FILE -> FileRow(
                        attachment = message.attachment,
                        content = content,
                        onOpen = onOpenAttachment,
                    )

                    else -> Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = content,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (message.ttlSeconds > 0) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = stringResource(R.string.chat_ttl_description),
                            tint = content.copy(alpha = 0.75f),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = stringResource(R.string.chat_ttl_value, message.ttlSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.75f),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.chat_encrypted_description),
                        tint = content.copy(alpha = 0.75f),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.width(4.dp))
                    StatusIcon(status = message.status, tint = content)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: MessageStatus, tint: Color) {
    val icon: ImageVector = when (status) {
        MessageStatus.QUEUED -> Icons.Default.HourglassEmpty
        MessageStatus.SENDING -> Icons.Default.Schedule
        MessageStatus.SENT -> Icons.Default.Check
        MessageStatus.DELIVERED -> Icons.Default.DoneAll
        MessageStatus.READ -> Icons.Default.DoneAll
        MessageStatus.FAILED -> Icons.Default.ErrorOutline
    }
    val color = when (status) {
        MessageStatus.READ -> MaterialTheme.colorScheme.primary
        MessageStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> tint.copy(alpha = 0.75f)
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(R.string.chat_status_description),
        tint = color,
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun VoiceRow(
    content: Color,
    isPlaying: Boolean,
    progress: Float,
    onToggle: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = { onToggle?.invoke() }) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.chat_play_voice),
                tint = content,
            )
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Waveform(
                modifier = Modifier
                    .width(140.dp)
                    .height(30.dp),
                progress = progress,
                active = isPlaying,
                color = content,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            tint = content.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FileRow(
    attachment: Attachment?,
    content: Color,
    onOpen: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = attachment?.mimeType?.takeIf { it.startsWith("audio/") }?.let {
                Icons.Default.GraphicEq
            } ?: Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = content,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = attachment?.name ?: stringResource(R.string.chat_attachment_unknown),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            attachment?.sizeBytes?.takeIf { it > 0 }?.let {
                Text(
                    text = formatBytes(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.8f),
                )
            }
        }
        if (onOpen != null) {
            androidx.compose.material3.TextButton(onClick = onOpen) {
                Text(stringResource(R.string.chat_open), color = content)
            }
        }
    }
}

/** Forma de onda determinista segun el progreso de reproduccion. */
@Composable
fun Waveform(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    active: Boolean = false,
    level: Float = 0f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "wave-progress")
    Canvas(modifier = modifier) {
        val barCount = 24
        val barWidth = size.width / (barCount * 2)
        val idleColor = color.copy(alpha = 0.35f)
        for (index in 0 until barCount) {
            val fraction = index.toFloat() / barCount
            val reached = fraction <= animatedProgress.coerceIn(0f, 1f)
            val base = if (active) 0.35f + level * 0.65f else 0.35f
            val heightFactor = when {
                reached -> base
                active -> base * 0.6f
                else -> 0.3f
            }
            val barHeight = (size.height * heightFactor).coerceAtLeast(2f)
            val x = index * (barWidth * 2) + barWidth
            drawLine(
                color = if (reached) color else idleColor,
                start = androidx.compose.ui.geometry.Offset(x, size.height / 2 - barHeight / 2),
                end = androidx.compose.ui.geometry.Offset(x, size.height / 2 + barHeight / 2),
                strokeWidth = barWidth,
            )
        }
    }
}

// --------------------------------------------------------------- auxiliar

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun TtlSelector(
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(0, 10, 30, 60)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { seconds ->
            FilterChip(
                selected = selected == seconds,
                onClick = { onSelected(seconds) },
                label = {
                    Text(
                        if (seconds == 0) {
                            stringResource(R.string.chat_ttl_off)
                        } else {
                            stringResource(R.string.chat_ttl_seconds, seconds)
                        },
                    )
                },
                leadingIcon = if (seconds == 0) null else {
                    { Icon(Icons.Default.Timer, null, Modifier.size(18.dp)) }
                },
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun BluetoothDisabledIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.BluetoothDisabled,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
fun RelativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3_600_000 -> stringResource(R.string.time_minutes, (diff / 60_000).toInt())
        diff < 86_400_000 -> stringResource(R.string.time_hours, (diff / 3_600_000).toInt())
        else -> stringResource(R.string.time_days, (diff / 86_400_000).toInt())
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun DayDivider(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
fun MicIcon(modifier: Modifier = Modifier, active: Boolean = false, tint: Color = Color.Unspecified) {
    Icon(
        imageVector = Icons.Default.Mic,
        contentDescription = null,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) {
            if (active) MaterialTheme.colorScheme.error else LocalContentTint
        } else {
            tint
        },
    )
}

private val LocalContentTint: Color
    @Composable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun SendIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.Send,
        contentDescription = stringResource(R.string.chat_send),
        modifier = modifier,
    )
}

@Composable
fun AttachIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.AttachFile,
        contentDescription = stringResource(R.string.chat_attach),
        modifier = modifier,
    )
}
