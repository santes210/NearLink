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
    val errorMessage by viewModel.errorMessage.collectAsState()
    val transferStatus by viewModel.transferStatus.collectAsState()
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
            text = "Descubriendo dispositivos NearLink cercanos vía Bluetooth Classic",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    isScanning = !isScanning
                    if (isScanning) viewModel.startScan() else viewModel.stopScan()
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.Radar, contentDescription = "Escanear")
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isScanning) "Detener" else "Escanear")
            }
            OutlinedButton(onClick = { makeDiscoverable() }) {
                Icon(Icons.Default.Visibility, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Hacer visible")
            }
            OutlinedButton(onClick = { openBluetoothSettings() }) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Emparejar")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Dispositivos",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Para conectar dos teléfonos: 1) pulsa «Emparejar» y vínculalos en Ajustes, " +
                "o 2) pulsa «Hacer visible» en uno y «Escanear» en el otro. Los emparejados " +
                "aparecen al instante. PIN de emparejamiento por defecto: 4821 (igual en ambos).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        transferStatus?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

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
                            Text(text = "MAC: ${device.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "PIN: ${device.pin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            if (device.rssi != 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                RssiIndicator(rssi = device.rssi)
                            }
                        }
                        Button(onClick = { viewModel.selectPeer(device) }) {
                            Text(text = if (device.connectionState == ConnectionState.CONNECTED) "Conectado" else "Conectar")
                        }
                    }
                }
            }
        }
    }
}
