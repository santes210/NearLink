package com.nearlink.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nearlink.app.R
import com.nearlink.app.di.AppContainer
import com.nearlink.app.di.LocalContainer
import com.nearlink.app.domain.model.UserSettings
import com.nearlink.app.permissions.NearLinkPermissions
import com.nearlink.app.ui.screens.chat.ChatScreen
import com.nearlink.app.ui.screens.home.HomeScreen
import com.nearlink.app.ui.screens.permissions.PermissionsScreen
import com.nearlink.app.ui.screens.radar.RadarScreen
import com.nearlink.app.ui.screens.settings.SettingsScreen
import com.nearlink.app.ui.theme.NearLinkTheme

/** Ancho (dp) a partir del cual la navegacion pasa de barra inferior a rail. */
private const val MEDIUM_WIDTH_BREAKPOINT_DP = 600

@Composable
fun NearLinkApp(
    container: AppContainer,
    startDestination: String = com.nearlink.app.ui.navigation.Routes.HOME,
    onPermissionsGranted: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by container.settingsRepository.settings.collectAsState(initial = UserSettings())

    var permissionsGranted by remember {
        mutableStateOf(NearLinkPermissions.hasAll(context))
    }
    var missingPermissions by remember {
        mutableStateOf(NearLinkPermissions.missing(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val stillMissing = results.filter { !it.value }.keys.toList()
        missingPermissions = stillMissing
        permissionsGranted = stillMissing.isEmpty()
        if (permissionsGranted) onPermissionsGranted()
    }

    val darkTheme = when (settings.themeMode) {
        com.nearlink.app.domain.model.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        com.nearlink.app.domain.model.ThemeMode.LIGHT -> false
        com.nearlink.app.domain.model.ThemeMode.DARK -> true
    }

    CompositionLocalProvider(LocalContainer provides container) {
        NearLinkTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
            if (permissionsGranted) {
                MainContent(container = container, startDestination = startDestination)
            } else {
                PermissionsScreen(
                    missing = missingPermissions.ifEmpty {
                        listOf(Manifest.permission.BLUETOOTH_SCAN)
                    },
                    allGranted = false,
                    onRequest = { permissionLauncher.launch(NearLinkPermissions.required().toTypedArray()) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    container: AppContainer,
    startDestination: String,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavigation = com.nearlink.app.ui.navigation.Routes.isTopLevel(currentRoute)

    // Adaptacion a tamanos de ventana: por debajo de 600dp va barra inferior y
    // a partir de ahi NavigationRail (patron canonico de Material 3).
    val useRail = LocalConfiguration.current.screenWidthDp >= MEDIUM_WIDTH_BREAKPOINT_DP

    DisposableEffect(Unit) {
        onDispose { container.voicePlayer.stop() }
    }

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showNavigation) {
                AppNavigationRail(
                    currentRoute = currentRoute,
                    onNavigate = { route -> navigateTopLevel(navController, route) },
                )
            }
            AppNavHost(
                container = container,
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                if (showNavigation) {
                    AppNavigationBar(
                        currentRoute = currentRoute,
                        onNavigate = { route -> navigateTopLevel(navController, route) },
                    )
                }
            },
        ) { padding ->
            AppNavHost(
                container = container,
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

private fun navigateTopLevel(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(com.nearlink.app.ui.navigation.Routes.HOME) {
            val viewModel: com.nearlink.app.ui.screens.home.HomeViewModel =
                viewModel(factory = container.homeViewModelFactory)
            HomeScreen(
                viewModel = viewModel,
                onOpenChat = { peerId ->
                    navController.navigate(com.nearlink.app.ui.navigation.Routes.chat(peerId))
                },
                onOpenRadar = {
                    navigateTopLevel(navController, com.nearlink.app.ui.navigation.Routes.RADAR)
                },
            )
        }

        composable(com.nearlink.app.ui.navigation.Routes.RADAR) {
            val viewModel: com.nearlink.app.ui.screens.radar.RadarViewModel =
                viewModel(factory = container.radarViewModelFactory)
            RadarScreen(
                viewModel = viewModel,
                onOpenChat = { peerId ->
                    navController.navigate(com.nearlink.app.ui.navigation.Routes.chat(peerId))
                },
            )
        }

        composable(com.nearlink.app.ui.navigation.Routes.SETTINGS) {
            val viewModel: com.nearlink.app.ui.screens.settings.SettingsViewModel =
                viewModel(factory = container.settingsViewModelFactory)
            SettingsScreen(viewModel = viewModel)
        }

        composable(
            route = com.nearlink.app.ui.navigation.Routes.CHAT_PATTERN,
            arguments = listOf(
                navArgument(com.nearlink.app.ui.navigation.Routes.CHAT_ARG_PEER) {
                    type = NavType.StringType
                },
            ),
        ) { entry ->
            val peerId = entry.arguments?.getString(com.nearlink.app.ui.navigation.Routes.CHAT_ARG_PEER)
            if (peerId == null) {
                navController.popBackStack()
                return@composable
            }
            val chatViewModel: com.nearlink.app.ui.screens.chat.ChatViewModel =
                viewModel(factory = container.chatViewModelFactory(peerId))
            ChatScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun AppNavigationBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            TopLevelDestination.HOME -> Icons.Default.Forum
                            TopLevelDestination.RADAR -> Icons.Default.BluetoothSearching
                            TopLevelDestination.SETTINGS -> Icons.Default.Settings
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun AppNavigationRail(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            TopLevelDestination.HOME -> Icons.Default.Forum
                            TopLevelDestination.RADAR -> Icons.Default.BluetoothSearching
                            TopLevelDestination.SETTINGS -> Icons.Default.Settings
                        },
                        contentDescription = stringResource(destination.label),
                    )
                },
                label = { Text(stringResource(destination.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

private enum class TopLevelDestination(
    val route: String,
    val label: Int,
) {
    HOME(com.nearlink.app.ui.navigation.Routes.HOME, R.string.nav_home),
    RADAR(com.nearlink.app.ui.navigation.Routes.RADAR, R.string.nav_radar),
    SETTINGS(com.nearlink.app.ui.navigation.Routes.SETTINGS, R.string.nav_settings),
}
