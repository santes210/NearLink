package com.nearlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nearlink.app.ui.theme.NearLinkTheme
import com.nearlink.app.viewmodel.NearLinkViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: NearLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NearLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionGate { MainScreen(viewModel = viewModel) }
                }
            }
        }
    }
}

/**
 * Pide los permisos de runtime necesarios (Bluetooth, ubicación para descubrimiento,
 * Wi-Fi Direct y micrófono). El ViewModel (y por tanto el servidor Bluetooth) se crea
 * solo después de concederlos, porque MainScreen accede a él.
 */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val required = remember { requiredPermissions() }

    fun allGranted(): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = required.all {
            result[it] == true ||
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(required.toTypedArray())
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "NearLink necesita permisos de Bluetooth, ubicación y Wi-Fi Direct " +
                    "para descubrir y conectar con dispositivos cercanos, y micrófono para " +
                    "las notas de voz.",
                color = MaterialTheme.colorScheme.onBackground
            )
            Button(onClick = { granted = allGranted(); if (!granted) launcher.launch(required.toTypedArray()) }) {
                Text("Conceder permisos")
            }
        }
    }
}

private fun requiredPermissions(): List<String> {
    val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_SCAN)
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return list
}
