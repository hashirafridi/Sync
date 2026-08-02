package com.hashir.sync.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for notification permission once, on first launch.
 *
 * This is not about being able to nag the user. Uploads run as a foreground service so
 * they survive the app being backgrounded, and a foreground service is required to show a
 * notification. From Android 13 onwards that permission is denied until explicitly
 * granted, so without this a long upload can be refused promotion and killed the moment
 * the user switches away.
 *
 * Declared in the manifest but never requested is the easy mistake here: the manifest
 * entry alone grants nothing on API 33 and above.
 */
@Composable
fun RequestNotificationPermissionOnce() {
    // Below API 33 the permission is granted at install time and no dialog exists.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Nothing to do either way. If it is denied, uploads still run -- they are just
        // more exposed to being killed in the background, and the user can grant it later
        // from system settings. Blocking the app over this would be worse.
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Asks for storage permission, but only on Android 9 and below.
 *
 * Those versions have no MediaStore insert path, so saving a download into the public
 * Movies folder means writing to shared storage directly, which requires the permission.
 * From Android 10 onwards this is unnecessary and the dialog would be an insult.
 *
 * Called from the downloader screen rather than at launch: asking for storage access
 * before the user has shown any interest in downloading is the kind of prompt people
 * reflexively deny.
 */
@Composable
fun RequestLegacyStoragePermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Denial is survivable: the download still succeeds, it just stays in app-private
        // storage and does not appear in the gallery.
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
