package com.nearlink.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearlink.app.R
import com.nearlink.app.domain.model.ThemeMode
import com.nearlink.app.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var pinVisible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.settings_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard(title = stringResource(R.string.settings_identity)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = identity?.fingerprint ?: stringResource(R.string.settings_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(identity?.fingerprint.orEmpty()))
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.settings_copy),
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.settings_identity_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = settings.displayName,
                        onValueChange = viewModel::setDisplayName,
                        label = { Text(stringResource(R.string.settings_display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.settings_pin)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (settings.pairingPin.isBlank()) {
                                stringResource(R.string.settings_pin_none)
                            } else if (pinVisible) {
                                settings.pairingPin
                            } else {
                                "••••••"
                            },
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pinVisible = !pinVisible }) {
                            Text(
                                stringResource(
                                    if (pinVisible) R.string.settings_pin_hide else R.string.settings_pin_show,
                                ),
                            )
                        }
                        FilledTonalButton(onClick = viewModel::rotatePin) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.settings_pin_rotate))
                        }
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.settings_pin_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.settings_mesh)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_relay),
                        subtitle = stringResource(R.string.settings_relay_help),
                        checked = settings.relayEnabled,
                        onCheckedChange = viewModel::setRelay,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_autodelete),
                        subtitle = stringResource(R.string.settings_autodelete_help),
                        checked = settings.autoDeleteEnabled,
                        onCheckedChange = viewModel::setAutoDelete,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.settings_default_ttl),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 30, 60, 300).forEach { seconds ->
                            FilterChip(
                                selected = settings.defaultTtlSeconds == seconds,
                                onClick = { viewModel.setDefaultTtl(seconds) },
                                label = {
                                    Text(
                                        if (seconds == 0) {
                                            stringResource(R.string.chat_ttl_off)
                                        } else {
                                            stringResource(R.string.chat_ttl_seconds, seconds)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.settings_appearance)) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setTheme(mode) },
                                label = {
                                    Text(
                                        stringResource(
                                            when (mode) {
                                                ThemeMode.SYSTEM -> R.string.settings_theme_system
                                                ThemeMode.LIGHT -> R.string.settings_theme_light
                                                ThemeMode.DARK -> R.string.settings_theme_dark
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_help),
                        checked = settings.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.settings_danger)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.settings_regenerate),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { confirmRegenerate = true }) {
                            Text(stringResource(R.string.settings_regenerate_action))
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.settings_wipe),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { confirmWipe = true }) {
                            Text(stringResource(R.string.settings_wipe_action))
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.settings_wipe_title)) },
            text = { Text(stringResource(R.string.settings_wipe_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.wipeMessages()
                    confirmWipe = false
                }) { Text(stringResource(R.string.settings_wipe_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.settings_regenerate_title)) },
            text = { Text(stringResource(R.string.settings_regenerate_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.regenerateIdentity()
                    confirmRegenerate = false
                }) { Text(stringResource(R.string.settings_regenerate_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PasswordPlaceholder() {
    Text(text = "••••••", style = MaterialTheme.typography.displaySmall)
}

@Composable
private fun PinTransformation(): PasswordVisualTransformation = PasswordVisualTransformation()
