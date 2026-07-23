package com.nearlink.app.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.ui.components.RssiIndicator
import com.nearlink.app.viewmodel.NearLinkViewModel

@Composable
fun DiscoveryScreen(viewModel: NearLinkViewModel) {
    var isScanning by remember { mutableStateOf(false) }
    val discoveredList by viewModel.peers.collectAsState()
    val connected by viewModel.connectedPeers.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current

    fun makeDiscoverable() {
        runCatching {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            context.startActivity(intent)
        }
    }
    fun openBluetoothSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Radar Bluetooth", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Conecta los NODOS DE TRANSPORTE de la malla (luego chateas desde Inicio)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { isScanning = !isScanning; if (isScanning) viewModel.startScan() else viewModel.stopScan() },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.Radar, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isScanning) "Detener" else "Escanear")
            }
            OutlinedButton(onClick = { makeDiscoverable() }) {
                Icon(Icons.Default.Visibility, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Visible")
            }
            OutlinedButton(onClick = { openBluetoothSettings() }) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Emparejar")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        AssistChip(onClick = {}, label = { Text("Conectados: ${connected.size}") }, leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) })

        Spacer(modifier = Modifier.height(8.dp))

        Text("Dispositivos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "1) «Emparejar» y vincúlalos en Ajustes, o 2) «Visible» en uno y «Escanear» en el otro. Toca «Conectar» para sumarlo a la malla.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(discoveredList) { device ->
                val isConnected = device.connectionState == ConnectionState.CONNECTED
                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                            Text("MAC: ${device.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (device.rssi != 0) { Spacer(modifier = Modifier.height(4.dp)); RssiIndicator(rssi = device.rssi) }
                        }
                        Button(onClick = { viewModel.connectPeer(device) }) {
                            Text(if (isConnected) "Conectado" else "Conectar")
                        }
                    }
                }
            }
        }
    }
}
