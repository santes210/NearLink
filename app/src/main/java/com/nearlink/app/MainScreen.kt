
package com.nearlink.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.nearlink.app.ui.screens.*
import com.nearlink.app.viewmodel.NearLinkViewModel
import com.nearlink.app.viewmodel.Screen

@Composable
fun MainScreen(viewModel: NearLinkViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    Scaffold(
        bottomBar = {
            if (currentScreen != Screen.CHAT) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Chat, contentDescription = "Chats") },
                        label = { Text("Chats") },
                        selected = currentScreen == Screen.HOME,
                        onClick = { viewModel.navigateTo(Screen.HOME) }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Radar, contentDescription = "Radar") },
                        label = { Text("Radar") },
                        selected = currentScreen == Screen.DISCOVERY,
                        onClick = { viewModel.navigateTo(Screen.DISCOVERY) }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                        label = { Text("Ajustes") },
                        selected = currentScreen == Screen.SETTINGS,
                        onClick = { viewModel.navigateTo(Screen.SETTINGS) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                Screen.HOME -> HomeScreen(viewModel)
                Screen.DISCOVERY -> DiscoveryScreen(viewModel)
                Screen.CHAT -> ChatScreen(viewModel)
                Screen.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
