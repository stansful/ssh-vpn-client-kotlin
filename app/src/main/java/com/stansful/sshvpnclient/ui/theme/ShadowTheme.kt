package com.stansful.sshvpnclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors

/** Entry point to the design tokens: `Shadow.colors`, `Shadow.type`, `Shadow.reducedMotion`. */
object Shadow {
    val colors: ShadowColors
        @Composable
        @ReadOnlyComposable
        get() = LocalShadowColors.current

    val type: ShadowType
        @Composable
        @ReadOnlyComposable
        get() = LocalShadowType.current

    val reducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReducedMotion.current
}

/** Corner radii of the design (BRIEF §4). */
object ShadowShapes {
    val Pill = CircleShape
    val Card = RoundedCornerShape(18.dp)
    val Button = RoundedCornerShape(16.dp)
    val ButtonSmall = RoundedCornerShape(14.dp)
    val Input = RoundedCornerShape(14.dp)
    val Banner = RoundedCornerShape(14.dp)
    val Toast = RoundedCornerShape(14.dp)
    val IconButton = RoundedCornerShape(14.dp)
    val TileLarge = RoundedCornerShape(14.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Segmented = RoundedCornerShape(16.dp)
    val SegmentThumb = RoundedCornerShape(12.dp)
    val Menu = RoundedCornerShape(16.dp)
    val MenuItem = RoundedCornerShape(12.dp)
    val Dialog = RoundedCornerShape(24.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/** Spacing and sizes of the design (BRIEF §4). */
object ShadowDimens {
    /** Screen side gutter. */
    val Gutter = 20.dp

    /**
     * Widest content column of a single-pane screen on a tablet (gutters included): Servers, Settings,
     * Appearance, App routing, the editors and Activity line up on one centered column.
     */
    val PaneMaxWidth = 720.dp

    /** Minimum touch target. */
    val TouchTarget = 44.dp
    val ButtonLarge = 52.dp
    val ButtonMedium = 44.dp
    val ButtonSmall = 40.dp
    val Chip = 36.dp
    val Field = 52.dp
    val ListRowMin = 56.dp
    val BottomNav = 72.dp
    val NavRail = 88.dp
    val SubScreenBar = 56.dp
    val CardPadding = 16.dp
    val Hairline = 1.dp
}

/** Resolves the palette for a theme mode: SYSTEM → Night/Day by system, LIGHT → Day, DARK → Night. */
fun shadowColorsFor(
    themeMode: AppThemeMode,
    customThemeColors: CustomThemeColors,
    systemDark: Boolean,
): ShadowColors = when (themeMode) {
    AppThemeMode.SYSTEM -> if (systemDark) NightColors else DayColors
    AppThemeMode.LIGHT -> DayColors
    AppThemeMode.DARK -> NightColors
    AppThemeMode.CUSTOM -> customThemeColors.toShadowColors()
}

/**
 * Root theme of the redesigned app. Provides `Shadow.colors`/`Shadow.type`/`LocalReducedMotion`
 * and a Material3 theme mapped from the tokens (text selection, sheets, menus match).
 */
@Composable
fun ShadowTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    customThemeColors: CustomThemeColors = CustomThemeColors.night(),
    reducedMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors = remember(themeMode, customThemeColors, systemDark) {
        shadowColorsFor(themeMode, customThemeColors, systemDark)
    }
    ShadowTheme(colors = colors, reducedMotion = reducedMotion, content = content)
}

/** Same as [ShadowTheme] with an explicit palette (previews, tablet panes, tests). */
@Composable
fun ShadowTheme(
    colors: ShadowColors,
    reducedMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val motionReduced = reducedMotion ?: rememberSystemReducedMotion()
    val colorScheme = remember(colors) { colors.toColorScheme() }
    val selectionColors = remember(colors) {
        TextSelectionColors(
            handleColor = colors.amber,
            backgroundColor = colors.amber.copy(alpha = SELECTION_ALPHA),
        )
    }
    CompositionLocalProvider(
        LocalShadowColors provides colors,
        LocalShadowType provides DefaultShadowType,
        LocalReducedMotion provides motionReduced,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShadowMaterialTypography,
            shapes = ShadowMaterialShapes,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides colors.ink1,
                LocalTextSelectionColors provides selectionColors,
                content = content,
            )
        }
    }
}

private fun ShadowColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = amber,
        onPrimary = onAmber,
        primaryContainer = mixColors(amber, surface1, CONTAINER_ALPHA),
        onPrimaryContainer = amberText,
        inversePrimary = amberText,
        secondary = mint,
        onSecondary = onMint,
        secondaryContainer = mixColors(mint, surface1, CONTAINER_ALPHA),
        onSecondaryContainer = mintText,
        tertiary = sky,
        onTertiary = bg,
        tertiaryContainer = mixColors(sky, surface1, CONTAINER_ALPHA),
        onTertiaryContainer = skyText,
        background = bg,
        onBackground = ink1,
        surface = surface1,
        onSurface = ink1,
        surfaceVariant = surface2,
        onSurfaceVariant = ink2,
        surfaceTint = Color.Transparent,
        inverseSurface = ink1,
        inverseOnSurface = bg,
        error = coral,
        onError = onCoral,
        errorContainer = mixColors(coral, surface1, CONTAINER_ALPHA),
        onErrorContainer = coralText,
        outline = line2,
        outlineVariant = line,
        scrim = scrim,
        surfaceBright = surface2,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface1,
        surfaceContainer = surface1,
        surfaceContainerHigh = sheet,
        surfaceContainerHighest = surface3,
    )
}

private val ShadowMaterialTypography = Typography(
    displayLarge = DefaultShadowType.display,
    displayMedium = DefaultShadowType.display,
    displaySmall = DefaultShadowType.display,
    headlineLarge = DefaultShadowType.display,
    headlineMedium = DefaultShadowType.titleL,
    headlineSmall = DefaultShadowType.titleM,
    titleLarge = DefaultShadowType.titleL,
    titleMedium = DefaultShadowType.titleS,
    titleSmall = DefaultShadowType.label,
    bodyLarge = DefaultShadowType.body,
    bodyMedium = DefaultShadowType.body,
    bodySmall = DefaultShadowType.bodyS,
    labelLarge = DefaultShadowType.button,
    labelMedium = DefaultShadowType.label,
    labelSmall = DefaultShadowType.caption,
)

private val ShadowMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private const val SELECTION_ALPHA = 0.35f
private const val CONTAINER_ALPHA = 0.16f
