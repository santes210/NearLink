
package com.nearlink.app.ui.screens

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
import com.nearlink.app.model.PeerDevice
import com.nearlink.app.ui.components.RssiIndicator
import com.nearlink.app.viewmodel.NearLinkViewModel

@Composable
fun DiscoveryScreen(viewModel: NearLinkViewModel) {
    var isScanning by remember { mutableStateOf(false) }
    val discoveredList = listOf(
        PeerDevice("1", "Pixel 8 Pro (NearLink)", "AA:BB:CC:11:22:33", -55, false, "4821"),
        PeerDevice("2", "Galaxy S24 Ultra", "AA:BB:CC:44:55:66", -68, false, "9134"),
        PeerDevice("3", "Xiaomi 14 Pro", "AA:BB:CC:77:88:99", -82, false, "3052")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Radar Bluetooth P2P",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Descubriendo dispositivos NearLink cercanos vía BLE / Classic",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    isScanning = !isScanning
                    viewModel.startScan()
                },
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.Radar, contentDescription = "Escanear")
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (isScanning) "Detener Radar" else "Escanear Dispositivos")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Dispositivos Encontrados",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(discoveredList) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = device.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                            Text(text = "PIN Temporal: ${device.pin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            RssiIndicator(rssi = device.rssi)
                        }

                        Button(onClick = { viewModel.selectPeer(device) }) {
                            Text(text = "Conectar")
                        }
                    }
                }
            }
        }
    }
}
