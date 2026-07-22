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
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.ui.components.RssiIndicator
import com.nearlink.app.viewmodel.NearLinkViewModel

@Composable
fun DiscoveryScreen(viewModel: NearLinkViewModel) {
    var isScanning by remember { mutableStateOf(false) }
    val discoveredList by viewModel.peers.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val transferStatus by viewModel.transferStatus.collectAsState()

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

        Spacer(modifier = Modifier.height(24.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    isScanning = !isScanning
                    if (isScanning) viewModel.startScan() else viewModel.stopScan()
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

        if (discoveredList.isEmpty()) {
            Text(
                text = "Activa el Bluetooth en ambos teléfonos, hazlos visibles o emparéjalos, " +
                    "y pulsa «Escanear Dispositivos». El PIN de emparejamiento por defecto es 4821 " +
                    "(debe ser el mismo en los dos).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
                            Text(text = "PIN emparejamiento: ${device.pin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            RssiIndicator(rssi = device.rssi)
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
