
package com.nearlink.app.ui.screens

import androidx.compose.foundation.clickable
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
import com.nearlink.app.ui.components.RssiIndicator
import com.nearlink.app.viewmodel.NearLinkViewModel

@Composable
fun HomeScreen(viewModel: NearLinkViewModel) {
    val peers by viewModel.peers.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = "NearLink P2P",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Modo Mesh • X25519 • Cifrado Extremo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = { viewModel.sendSosAlert() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Warning, contentDescription = "SOS", tint = MaterialTheme.colorScheme.onError)
                Spacer(modifier = Modifier.width(4.dp))
                Text("SOS", color = MaterialTheme.colorScheme.onError)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Radar, contentDescription = "Beacon", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Baliza BLE Silenciosa Activa", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(text = "Transmitiendo presencia offline en segundo plano", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Nodos y Chats en Red Mesh",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(peers) { peer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectPeer(peer) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = "Dispositivo", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = peer.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                            Text(text = "Fp: ${peer.publicKeyFingerprint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(2.dp))
                            RssiIndicator(rssi = peer.rssi)
                        }

                        if (peer.isConnected) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                Text(text = "Mesh Link", modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
