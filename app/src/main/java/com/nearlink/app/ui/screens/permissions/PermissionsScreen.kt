package com.nearlink.app.ui.screens.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nearlink.app.R

/**
 * Puerta de permisos: la malla no puede funcionar sin ellos, asi que se explica
 * para que sirve cada uno antes de pedirlo (mejor tasa de aceptacion y evita
 * el bloqueo permanente del sistema).
 */
@Composable
fun PermissionsScreen(
    missing: List<String>,
    allGranted: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.BluetoothSearching,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.permissions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(24.dp))

        missing.forEach { permission ->
            PermissionRow(permission = permission)
            Spacer(Modifier.size(8.dp))
        }

        Spacer(Modifier.size(28.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (allGranted) R.string.permissions_continue else R.string.permissions_grant,
                ),
            )
        }
        if (!allGranted) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.permissions_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermissionRow(permission: String) {
    val (icon, text) = when {
        permission.endsWith("BLUETOOTH_SCAN") || permission.endsWith("BLUETOOTH_CONNECT") ||
            permission.endsWith("BLUETOOTH_ADVERTISE") ->
            Icons.Default.BluetoothSearching to R.string.permissions_bluetooth

        permission.endsWith("POST_NOTIFICATIONS") ->
            Icons.Default.Notifications to R.string.permissions_notifications

        permission.endsWith("NEARBY_WIFI_DEVICES") || permission.endsWith("ACCESS_FINE_LOCATION") ->
            Icons.Default.Place to R.string.permissions_wifi

        else -> Icons.Default.BluetoothSearching to R.string.permissions_other
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
