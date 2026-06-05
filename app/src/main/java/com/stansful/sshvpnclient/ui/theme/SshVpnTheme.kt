package com.stansful.sshvpnclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C5B),
    onPrimary = Color.White,
    secondary = Color(0xFF445E91),
    tertiary = Color(0xFF7D5260),
    surface = Color(0xFFFBFCF8),
    background = Color(0xFFF7FAF5),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF67DBC3),
    secondary = Color(0xFFADC6FF),
    tertiary = Color(0xFFEFB8C8),
    surface = Color(0xFF111411),
    background = Color(0xFF0E1210),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SshVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
