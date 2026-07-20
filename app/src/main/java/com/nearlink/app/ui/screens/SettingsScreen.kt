
package com.nearlink.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nearlink.app.viewmodel.NearLinkViewModel

@Composable
fun SettingsScreen(viewModel: NearLinkViewModel) {
    val pin by viewModel.temporaryPin.collectAsState()
    val fingerprint by viewModel.identityFingerprint.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Configuración NearLink",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Identidad Criptográfica, PAKE y Wi-Fi Direct P2P",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Huella Digital de Identidad (X25519)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = fingerprint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Clave pública asimétrica usada para autenticación de extremo a extremo sin servidores.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "PIN Temporal PAKE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = pin, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.regeneratePin() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Renovar")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Renovar")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Transferencia Híbrida Wi-Fi Direct", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = "Wi-Fi Direct", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "Aceleración de Archivos a 250Mbps", fontWeight = FontWeight.SemiBold)
                        Text(text = "Negociación automática para fotos y videos grandes sin router ni internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
