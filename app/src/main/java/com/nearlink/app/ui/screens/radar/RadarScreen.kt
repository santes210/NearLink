package com.nearlink.app.ui.screens.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearlink.app.R
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.ScanState
import com.nearlink.app.ui.components.EmptyState
import com.nearlink.app.ui.components.PeerItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier,
    onOpenChat: (String) -> Unit,
) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.radar_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScanControls(
                    scanning = scanState == ScanState.SCANNING,
                    bluetoothEnabled = status.bluetoothEnabled,
                    onToggle = viewModel::toggleScan,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.radar_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (peers.isEmpty() && scanState != ScanState.SCANNING) {
                item {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        icon = if (status.bluetoothEnabled) {
                            Icons.Default.BluetoothSearching
                        } else {
                            Icons.Default.BluetoothDisabled
                        },
                        title = if (status.bluetoothEnabled) {
                            stringResource(R.string.radar_empty_title)
                        } else {
                            stringResource(R.string.radar_bluetooth_off_title)
                        },
                        subtitle = if (status.bluetoothEnabled) {
                            stringResource(R.string.radar_empty_subtitle)
                        } else {
                            stringResource(R.string.radar_bluetooth_off_subtitle)
                        },
                    )
                }
            }

            items(items = peers, key = { it.id }) { peer ->
                PeerItem(
                    peer = peer,
                    connected = peer.connectionState == ConnectionState.CONNECTED,
                    onConnect = { viewModel.connect(peer) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ScanControls(
    scanning: Boolean,
    bluetoothEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    when {
                        !bluetoothEnabled -> R.string.radar_state_bluetooth_off
                        scanning -> R.string.radar_state_scanning
                        else -> R.string.radar_state_idle
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            }
            if (bluetoothEnabled) {
                Button(onClick = onToggle) {
                    Icon(
                        imageVector = if (scanning) Icons.Default.Stop else Icons.Default.BluetoothSearching,
                        contentDescription = null,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(
                            if (scanning) R.string.radar_stop else R.string.radar_scan,
                        ),
                    )
                }
            } else {
                FilledTonalButton(onClick = { /* se abre desde Ajustes del sistema */ }) {
                    Text(stringResource(R.string.radar_enable_bluetooth))
                }
            }
        }
    }
}
