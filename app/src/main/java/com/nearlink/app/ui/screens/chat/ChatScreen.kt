package com.nearlink.app.ui.screens.chat

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearlink.app.R
import com.nearlink.app.core.TimeFormatter
import com.nearlink.app.di.LocalContainer
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.permissions.NearLinkPermissions
import com.nearlink.app.ui.components.AttachIcon
import com.nearlink.app.ui.components.ChatBubble
import com.nearlink.app.ui.components.DayDivider
import com.nearlink.app.ui.components.EmptyState
import com.nearlink.app.ui.components.FingerprintChip
import com.nearlink.app.ui.components.SendIcon
import com.nearlink.app.ui.components.TtlSelector
import com.nearlink.app.ui.components.Waveform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val timeFormatter = remember { TimeFormatter() }

    val peer by viewModel.peer.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ttl by viewModel.ttlSeconds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val endToEnd by viewModel.endToEnd.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var playbackProgress by remember { mutableFloatStateOf(0f) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val listState = rememberLazyListState()
    val recorder = container.voiceRecorder
    val player = container.voicePlayer

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            recorder.start()
            isRecording = true
            elapsed = 0L
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> pendingUri = uri }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            amplitude = recorder.amplitude()
            elapsed = recorder.elapsedMs()
            delay(80)
        }
    }

    LaunchedEffect(playingPath) {
        val path = playingPath
        if (path == null) {
            playbackProgress = 0f
            return@LaunchedEffect
        }
        while (playingPath == path) {
            val duration = player.duration().takeIf { it > 0 } ?: 1
            playbackProgress = (player.position().toFloat() / duration).coerceIn(0f, 1f)
            delay(120)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    pendingUri?.let { uri ->
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = uri.lastPathSegment ?: "adjunto"
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text(stringResource(R.string.chat_send_file_title)) },
            text = { Text(stringResource(R.string.chat_send_file_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null) {
                        viewModel.sendAttachmentBytes(bytes, name, mimeType)
                    }
                    pendingUri = null
                }) { Text(stringResource(R.string.chat_send)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUri = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = peer?.name ?: stringResource(R.string.chat_unknown_peer),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        peer?.fingerprint?.let { FingerprintChip(fingerprint = it, verified = peer?.verified == true) }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::sendSos) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.home_sos),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Estado del enlace y cifrado
            ConnectionBanner(
                connection = connection,
                endToEnd = endToEnd,
                onConnect = viewModel::connect,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Forum,
                    title = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_subtitle),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        Column {
                            val index = messages.indexOf(message)
                            val previous = messages.getOrNull(index - 1)
                            if (previous == null || !timeFormatter.isSameDay(previous.timestamp, message.timestamp)) {
                                DayDivider(label = timeFormatter.formatDayLabel(message.timestamp))
                            }
                            ChatBubble(
                                message = message,
                                isMe = message.outgoing,
                                formattedTime = timeFormatter.formatTime(message.timestamp),
                                isPlaying = message.attachment?.path == playingPath,
                                playbackProgress = if (message.attachment?.path == playingPath) playbackProgress else 0f,
                                onTogglePlayback = message.attachment?.let { attachment ->
                                    {
                                        scope.launch {
                                            val path = viewModel.openAttachment(attachment)?.absolutePath
                                            if (path != null) {
                                                if (playingPath == path) {
                                                    player.stop()
                                                    playingPath = null
                                                } else {
                                                    player.toggle(path) { playingPath = null }
                                                    playingPath = path
                                                }
                                            }
                                        }
                                    }
                                },
                                onOpenAttachment = message.attachment?.let { attachment ->
                                    {
                                        scope.launch {
                                            val file = viewModel.openAttachment(attachment)
                                            if (file != null) {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file,
                                                )
                                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                    setDataAndType(uri, attachment.mimeType)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (isRecording) {
                RecordingBar(
                    amplitude = amplitude,
                    elapsedMs = elapsed,
                    onCancel = {
                        recorder.cancel()
                        isRecording = false
                    },
                    onSend = {
                        val result = recorder.stop()
                        isRecording = false
                        result?.let { recording ->
                            viewModel.sendAttachmentBytes(
                                bytes = recording.file.readBytes(),
                                name = "voz_${System.currentTimeMillis()}.m4a",
                                mimeType = "audio/mp4",
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            TtlSelector(
                selected = ttl,
                onSelected = viewModel::setTtl,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            MessageComposer(
                value = text,
                onValueChange = { text = it },
                onSend = {
                    viewModel.sendText(text)
                    text = ""
                },
                onAttach = { fileLauncher.launch("*/*") },
                onMic = {
                    if (isRecording) {
                        val result = recorder.stop()
                        isRecording = false
                        result?.let { recording ->
                            viewModel.sendAttachmentBytes(
                                bytes = recording.file.readBytes(),
                                name = "voz_${System.currentTimeMillis()}.m4a",
                                mimeType = "audio/mp4",
                            )
                        }
                    } else if (NearLinkPermissions.isGranted(context, Manifest.permission.RECORD_AUDIO)) {
                        recorder.start()
                        isRecording = true
                        elapsed = 0L
                    } else {
                        recordPermissionLauncher.launch(NearLinkPermissions.audio())
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ConnectionBanner(
    connection: ConnectionState,
    endToEnd: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection == ConnectionState.CONNECTED
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = if (endToEnd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(
                    when (connection) {
                        ConnectionState.CONNECTED -> R.string.chat_status_connected
                        ConnectionState.CONNECTING -> R.string.chat_status_connecting
                        ConnectionState.FAILED -> R.string.chat_status_failed
                        ConnectionState.DISCONNECTING -> R.string.chat_status_disconnected
                        ConnectionState.DISCONNECTED -> R.string.chat_status_disconnected
                    },
                ) + " · " + stringResource(if (endToEnd) R.string.chat_e2e_on else R.string.chat_e2e_off),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!connected) {
                FilledTonalButton(onClick = onConnect) {
                    Text(stringResource(R.string.chat_connect))
                }
            }
        }
    }
}

@Composable
private fun RecordingBar(
    amplitude: Float,
    elapsedMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Waveform(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                level = amplitude,
                active = true,
                progress = 0f,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = (elapsedMs / 1000).toString() + "s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            IconButton(onClick = onSend) {
                SendIcon()
            }
        }
    }
}

@Composable
private fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onAttach) { AttachIcon() }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 120.dp),
            placeholder = { Text(stringResource(R.string.chat_placeholder)) },
            maxLines = 4,
            shape = MaterialTheme.shapes.extraLarge,
        )
        Spacer(Modifier.size(6.dp))
        if (value.isBlank()) {
            IconButton(onClick = onMic) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.chat_record),
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) { SendIcon() }
        }
    }
}
