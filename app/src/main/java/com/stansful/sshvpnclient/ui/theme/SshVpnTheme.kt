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
    primary = Color(0xFF66D9FF),
    onPrimary = Color(0xFF002534),
    primaryContainer = Color(0xFF063544),
    onPrimaryContainer = Color(0xFFD8F4FF),
    secondary = Color(0xFF7AE4D4),
    secondaryContainer = Color(0xFF123B37),
    tertiary = Color(0xFFC7C2FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEFF3F8),
    surface = Color(0xFF08090C),
    onSurface = Color(0xFFEFF3F8),
    surfaceVariant = Color(0xFF171B22),
    onSurfaceVariant = Color(0xFFB8C1CC),
    outline = Color(0xFF2B3440),
    error = Color(0xFFFFB4AB),
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
