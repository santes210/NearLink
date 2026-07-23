package com.nearlink.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nearlink.app.domain.model.MessageType
import com.nearlink.app.ui.components.ChatBubble
import com.nearlink.app.ui.components.RssiIndicator
import com.nearlink.app.viewmodel.NearLinkViewModel

@Composable
fun ChatScreen(viewModel: NearLinkViewModel) {
    val selectedPeer by viewModel.selectedPeer.collectAsState()
    val messagesMap by viewModel.messages.collectAsState()
    val isRecording by viewModel.isRecordingVoice.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val peerFp by viewModel.peerFingerprint.collectAsState()
    val transferStatus by viewModel.transferStatus.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var textInput by remember { mutableStateOf("") }
    var showTtlMenu by remember { mutableStateOf(false) }
    var selectedTtl by remember { mutableStateOf(0) }

    // Selector de archivos real: al elegir uno se lee, cifra E2E y envía por la malla.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "archivo"
            viewModel.sendFileUri(uri, name)
        }
    }

    val peerMessages = selectedPeer?.let { messagesMap[it.id] } ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.navigateTo(com.nearlink.app.viewmodel.Screen.HOME) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedPeer?.name ?: "Chat P2P",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (connectionState) {
                        com.nearlink.app.domain.model.ConnectionState.CONNECTED -> if (peerFp != null) "🔒 Enlace seguro (mesh)" else "Handshake…"
                        com.nearlink.app.domain.model.ConnectionState.CONNECTING -> "Conectando…"
                        com.nearlink.app.domain.model.ConnectionState.SCANNING -> "Escaneando…"
                        else -> "Sin enlace directo (mensajes en cola)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selectedPeer?.let { RssiIndicator(rssi = it.rssi) }
            }

            // SOS Emergency Button
            IconButton(
                onClick = { viewModel.sendSosAlert() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(Icons.Default.Warning, contentDescription = "SOS", tint = MaterialTheme.colorScheme.error)
            }

            IconButton(onClick = { showTtlMenu = !showTtlMenu }) {
                Icon(Icons.Default.Timer, contentDescription = "Autodestrucción TTL", tint = if (selectedTtl > 0) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }

            DropdownMenu(expanded = showTtlMenu, onDismissRequest = { showTtlMenu = false }) {
                DropdownMenuItem(text = { Text("TTL: Desactivado") }, onClick = { selectedTtl = 0; showTtlMenu = false })
                DropdownMenuItem(text = { Text("TTL: 10 segundos") }, onClick = { selectedTtl = 10; showTtlMenu = false })
                DropdownMenuItem(text = { Text("TTL: 30 segundos") }, onClick = { selectedTtl = 30; showTtlMenu = false })
            }

            Icon(Icons.Default.Lock, contentDescription = "X25519 & AES-256", tint = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        errorMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        transferStatus?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        // Messages List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(peerMessages) { message ->
                ChatBubble(message = message, isMe = message.senderId == "me")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isRecording) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Grabando", tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Grabando nota de voz con visualizador...", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.toggleVoiceRecording() }) {
                        Icon(Icons.Default.Send, contentDescription = "Enviar voz", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.AttachFile, contentDescription = "Adjuntar archivo cifrado")
            }

            IconButton(onClick = { viewModel.toggleVoiceRecording() }) {
                Icon(Icons.Default.Mic, contentDescription = "Nota de voz", tint = if (isRecording) MaterialTheme.colorScheme.error else LocalContentColor.current)
            }

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text(if (selectedTtl > 0) "Mensaje TTL (${selectedTtl}s)..." else "Mensaje cifrado…") },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 3,
                shape = MaterialTheme.shapes.extraLarge
            )

            IconButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendMessage(textInput, MessageType.TEXT, null, selectedTtl, false)
                        textInput = ""
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Enviar")
            }
        }
    }
}
