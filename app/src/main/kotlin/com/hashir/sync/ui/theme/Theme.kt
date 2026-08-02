package com.hashir.sync.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Sync's own palette, taken from the launcher icon's pink-to-orange gradient.
private val SyncPink = Color(0xFFFF5F9E)
private val SyncCoral = Color(0xFFFF7A5C)
private val SyncOrange = Color(0xFFFF9A3D)
private val ErrorRed = Color(0xFFBA1A1A)

private val LightColors = lightColorScheme(
    // Deeper than the icon's pink: the gradient colours are lovely as a large fill but
    // fail contrast as text or small controls on a light background.
    primary = Color(0xFFC1265F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3F001C),
    secondary = Color(0xFFB2521A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC5),
    onSecondaryContainer = Color(0xFF331200),
    tertiary = SyncCoral,
    surface = Color(0xFFFFF8F8),
    background = Color(0xFFFFF8F8),
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = SyncPink,
    onPrimary = Color(0xFF63002E),
    primaryContainer = Color(0xFF8B1145),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = SyncOrange,
    onSecondary = Color(0xFF522300),
    secondaryContainer = Color(0xFF743500),
    onSecondaryContainer = Color(0xFFFFDCC5),
    tertiary = SyncCoral,
    surface = Color(0xFF1A1113),
    background = Color(0xFF1A1113),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You wallpaper colours, available from Android 12.
     *
     * Off by default so the app keeps its own identity. With it on, the whole UI takes
     * its colour from the user's wallpaper and Sync ends up looking like everything else
     * on the phone.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
