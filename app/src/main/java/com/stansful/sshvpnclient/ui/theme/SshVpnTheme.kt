package com.stansful.sshvpnclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stansful.sshvpnclient.domain.model.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EBFF),
    onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF26A69A),
    secondaryContainer = Color(0xFFD7F4EF),
    tertiary = Color(0xFF5E5CE6),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF101214),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101214),
    surfaceVariant = Color(0xFFE8EDF3),
    onSurfaceVariant = Color(0xFF48525F),
    outline = Color(0xFFCBD3DC),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9F1C),
    onPrimary = Color(0xFF101010),
    primaryContainer = Color(0xFF2E1A00),
    onPrimaryContainer = Color(0xFFFFD8A0),
    secondary = Color(0xFFFFB84D),
    secondaryContainer = Color(0xFF271805),
    tertiary = Color(0xFFE65A00),
    tertiaryContainer = Color(0xFF351300),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF6F1EA),
    surface = Color(0xFF090909),
    onSurface = Color(0xFFF6F1EA),
    surfaceVariant = Color(0xFF17110A),
    onSurfaceVariant = Color(0xFFC9B8A2),
    outline = Color(0xFF3A2A18),
    outlineVariant = Color(0xFF24180D),
    error = Color(0xFFFF6B6B),
)

@Composable
fun SshVpnTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
