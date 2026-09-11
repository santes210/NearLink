package com.nearlink.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearlink.app.R
import com.nearlink.app.ui.components.ConversationItem
import com.nearlink.app.ui.components.EmptyState
import com.nearlink.app.ui.components.FingerprintChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenChat: (String) -> Unit,
    onOpenRadar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val status by viewModel.transportStatus.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val fingerprint = identity?.fingerprint
                        if (fingerprint != null) {
                            FingerprintChip(fingerprint = fingerprint, verified = true)
                        } else {
                            Text(
                                text = stringResource(R.string.home_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.home_scan_cta)) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.BluetoothSearching,
                        contentDescription = null,
                    )
                },
                onClick = onOpenRadar,
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(padding),
                icon = Icons.Default.Forum,
                title = stringResource(R.string.home_empty_title),
                subtitle = stringResource(R.string.home_empty_subtitle),
                action = {
                    Button(onClick = onOpenRadar) {
                        Text(stringResource(R.string.home_scan_cta))
                    }
                },
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MeshStatusCard(
                    advertising = status.advertising,
                    bluetoothEnabled = status.bluetoothEnabled,
                    connectedPeers = status.connectedPeers,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(items = conversations, key = { it.peerId }) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    onClick = { onOpenChat(conversation.peerId) },
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun MeshStatusCard(
    advertising: Boolean,
    bluetoothEnabled: Boolean,
    connectedPeers: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (advertising) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (advertising) Icons.Default.Lock else Icons.Default.BluetoothSearching,
                contentDescription = null,
                tint = if (advertising) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.padding(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (advertising) R.string.home_mesh_active else R.string.home_mesh_inactive,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (advertising) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = if (!bluetoothEnabled) {
                        stringResource(R.string.home_bluetooth_off)
                    } else {
                        stringResource(R.string.home_mesh_peers, connectedPeers)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (advertising) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
