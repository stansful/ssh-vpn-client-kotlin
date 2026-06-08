package com.stansful.sshvpnclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors

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
    secondary = Color(0xFF28C76F),
    onSecondary = Color(0xFF06180D),
    secondaryContainer = Color(0xFF063A20),
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
    customThemeColors: CustomThemeColors = CustomThemeColors.defaultLight(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.CUSTOM -> false
    }

    MaterialTheme(
        colorScheme = when (themeMode) {
            AppThemeMode.CUSTOM -> customThemeColors.toColorScheme()
            else -> if (darkTheme) DarkColors else LightColors
        },
        typography = MaterialTheme.typography,
        content = content,
    )
}

private fun CustomThemeColors.toColorScheme() = lightColorScheme(
    primary = Color(primary),
    onPrimary = readableOn(Color(primary)),
    primaryContainer = blend(Color(primary), Color(surface), CONTAINER_ALPHA),
    onPrimaryContainer = Color(onSurface),
    secondary = Color(secondary),
    onSecondary = readableOn(Color(secondary)),
    secondaryContainer = blend(Color(secondary), Color(surface), CONTAINER_ALPHA),
    onSecondaryContainer = Color(onSurface),
    tertiary = Color(secondary),
    background = Color(background),
    onBackground = Color(onSurface),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurface).copy(alpha = ON_SURFACE_VARIANT_ALPHA),
    outline = Color(outline),
    outlineVariant = Color(outline).copy(alpha = OUTLINE_VARIANT_ALPHA),
    error = Color(error),
    onError = readableOn(Color(error)),
    errorContainer = blend(Color(error), Color(surface), ERROR_CONTAINER_ALPHA),
    onErrorContainer = Color(onSurface),
)

private fun readableOn(color: Color): Color {
    return if (color.luminance() > READABLE_LUMINANCE_THRESHOLD) Color.Black else Color.White
}

private fun blend(
    foreground: Color,
    background: Color,
    alpha: Float,
): Color {
    val backgroundAlpha = 1f - alpha
    return Color(
        red = foreground.red * alpha + background.red * backgroundAlpha,
        green = foreground.green * alpha + background.green * backgroundAlpha,
        blue = foreground.blue * alpha + background.blue * backgroundAlpha,
        alpha = 1f,
    )
}

private const val CONTAINER_ALPHA = 0.18f
private const val ERROR_CONTAINER_ALPHA = 0.16f
private const val ON_SURFACE_VARIANT_ALPHA = 0.74f
private const val OUTLINE_VARIANT_ALPHA = 0.42f
private const val READABLE_LUMINANCE_THRESHOLD = 0.55f
