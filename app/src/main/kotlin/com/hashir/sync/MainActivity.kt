package com.hashir.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hashir.sync.ui.navigation.AppNavHost
import com.hashir.sync.ui.permissions.RequestNotificationPermissionOnce
import com.hashir.sync.ui.theme.SyncTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncTheme {
                // Must run before the first publish: without it, Android 13+ denies the
                // notification that a foreground upload service is required to show.
                RequestNotificationPermissionOnce()
                AppNavHost()
            }
        }
    }
}
