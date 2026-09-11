package com.nearlink.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nearlink.app.di.AppContainer
import com.nearlink.app.permissions.NearLinkPermissions
import com.nearlink.app.service.NearLinkForegroundService
import com.nearlink.app.ui.NearLinkApp
import com.nearlink.app.ui.navigation.Routes

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as NearLinkApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startPeer = intent?.getStringExtra(EXTRA_PEER_ID)
        val startDestination = startPeer?.let { Routes.chat(it) } ?: Routes.HOME

        setContent {
            NearLinkApp(
                container = container,
                startDestination = startDestination,
                onPermissionsGranted = { startMeshService() },
            )
        }

        if (NearLinkPermissions.hasAll(this)) {
            startMeshService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun startMeshService() {
        val serviceIntent = Intent(this, NearLinkForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_PEER_ID = "extra_peer_id"
    }
}
