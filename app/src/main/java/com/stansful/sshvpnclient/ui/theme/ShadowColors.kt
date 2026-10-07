package com.stansful.sshvpnclient.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.stansful.sshvpnclient.domain.model.CustomThemeColors

/**
 * Color tokens of the "shadow" design (BRIEF §2). Use them through `Shadow.colors`.
 *
 * Fill tokens (`amber`, `mint`, `coral`, `sky`) are for fills, rings and meters; the `*Text` tokens are
 * the readable text/icon shade on `bg`/surfaces (identical to the fill on Night, darker on Day).
 */
@Immutable
data class ShadowColors(
    val bg: Color,
    val navBg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val sheet: Color,
    val line: Color,
    val line2: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val amber: Color,
    val onAmber: Color,
    val amberText: Color,
    val amberTint: Color,
    val mint: Color,
    val onMint: Color,
    val mintText: Color,
    val mintTint: Color,
    val coral: Color,
    val onCoral: Color,
    val coralText: Color,
    val coralTint: Color,
    val sky: Color,
    val skyText: Color,
    val skyTint: Color,
    /** Soft sky body text of info notes on a sky tint (Night #C9DAFF; the sky text shade on Day). */
    val skySoft: Color,
    val scrim: Color,
    /** Muted amber for "Traffic is not protected yet" style sublines. */
    val amberMuted: Color,
    /** Border of sheets, dialogs and menus. */
    val sheetLine: Color,
    /** Top hairline of the bottom nav / end hairline of the rail. */
    val navLine: Color,
    /** Idle track ring of the connection orb. */
    val orbTrack: Color,
    /** Radial fill of the orb button: center stop. */
    val orbFillTop: Color,
    /** Radial fill of the orb button: outer stop. */
    val orbFillBottom: Color,
    /** The wordmark / rail state dot while nothing is connected (a dim neutral). */
    val stateDotOff: Color,
    val isDark: Boolean,
)

/** Night: the default dark look. */
val NightColors: ShadowColors = ShadowColors(
    bg = Color(0xFF0B0D10),
    navBg = Color(0xFF0E1014),
    surface1 = Color(0xFF13161B),
    surface2 = Color(0xFF1A1E25),
    surface3 = Color(0xFF232833),
    sheet = Color(0xFF171A20),
    line = Color(0xFF20242C),
    line2 = Color(0xFF2A303B),
    ink1 = Color(0xFFF3F1EC),
    ink2 = Color(0xFFB4B9C3),
    ink3 = Color(0xFF8A909C),
    amber = Color(0xFFFFB547),
    onAmber = Color(0xFF1A1206),
    amberText = Color(0xFFFFB547),
    amberTint = Color(0x1FFFB547),
    mint = Color(0xFF46E0A8),
    onMint = Color(0xFF04241A),
    mintText = Color(0xFF46E0A8),
    mintTint = Color(0x1F46E0A8),
    coral = Color(0xFFFF7A6B),
    onCoral = Color(0xFF2A0905),
    coralText = Color(0xFFFF7A6B),
    coralTint = Color(0x1FFF7A6B),
    sky = Color(0xFF8AB4FF),
    skyText = Color(0xFF8AB4FF),
    skyTint = Color(0x1F8AB4FF),
    skySoft = Color(0xFFC9DAFF),
    scrim = Color(0x9E050608),
    amberMuted = Color(0xFFD9A35A),
    sheetLine = Color(0xFF262B34),
    navLine = Color(0xFF1A1E25),
    orbTrack = Color(0xFF1F242C),
    orbFillTop = Color(0xFF1E232B),
    orbFillBottom = Color(0xFF13161B),
    stateDotOff = Color(0xFF4A505B),
    isDark = true,
)

/** Day: the light look; mirrors every Night role. */
val DayColors: ShadowColors = ShadowColors(
    bg = Color(0xFFF4F2EE),
    navBg = Color(0xFFFBFAF7),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF1EEE9),
    surface3 = Color(0xFFE7E3DC),
    sheet = Color(0xFFFFFFFF),
    line = Color(0xFFE4E0D8),
    line2 = Color(0xFFD6D1C7),
    ink1 = Color(0xFF14161A),
    ink2 = Color(0xFF4A505B),
    ink3 = Color(0xFF6B717C),
    amber = Color(0xFFF5A524),
    onAmber = Color(0xFF1A1206),
    amberText = Color(0xFF9A5200),
    amberTint = Color(0x29F5A524),
    mint = Color(0xFF2BC48A),
    onMint = Color(0xFF04241A),
    mintText = Color(0xFF0B7A53),
    mintTint = Color(0x242BC48A),
    coral = Color(0xFFC2392B),
    onCoral = Color(0xFFFFFFFF),
    coralText = Color(0xFFC2392B),
    coralTint = Color(0x1AC2392B),
    sky = Color(0xFF2F6FDB),
    skyText = Color(0xFF2F6FDB),
    skyTint = Color(0x1A2F6FDB),
    skySoft = Color(0xFF2F6FDB),
    scrim = Color(0x6614161A),
    amberMuted = Color(0xFF9A5200),
    sheetLine = Color(0xFFE4E0D8),
    navLine = Color(0xFFE4E0D8),
    orbTrack = Color(0xFFD6D1C7),
    orbFillTop = Color(0xFFFFFFFF),
    orbFillBottom = Color(0xFFF1EEE9),
    stateDotOff = Color(0xFF6B717C),
    isDark = false,
)

val LocalShadowColors: ProvidableCompositionLocal<ShadowColors> = staticCompositionLocalOf { NightColors }

/**
 * Maps the 8 user-editable custom roles onto the full token set:
 * primary→amber, secondary→mint, background→bg, surface→surface1/sheet, surfaceVariant→surface2
 * (+surface3 derived), onSurface→ink1 (ink2/ink3 derived), outline→line2 (+line derived), error→coral.
 * Text shades are pushed to at least 4.5:1 against the background. The unedited Night and Day
 * presets resolve to exactly [NightColors] / [DayColors].
 */
fun CustomThemeColors.toShadowColors(): ShadowColors {
    when (this) {
        CustomThemeColors.night() -> return NightColors
        CustomThemeColors.day() -> return DayColors
        else -> Unit
    }
    val background = Color(background)
    val surface = Color(surface)
    val surfaceVariant = Color(surfaceVariant)
    val onSurface = Color(onSurface)
    val outline = Color(outline)
    val primary = Color(primary)
    val secondary = Color(secondary)
    val error = Color(error)
    val dark = background.luminance() < DARK_LUMINANCE_THRESHOLD
    val defaults = if (dark) NightColors else DayColors
    val tintAlpha = if (dark) NIGHT_TINT_ALPHA else DAY_TINT_ALPHA
    val sheet = if (dark) mixColors(surfaceVariant, surface, SHEET_BLEND) else surface
    val amberText = ensureContrast(primary, background)
    val ink3 = ensureContrast(mixColors(onSurface, background, INK3_ALPHA), background)
    return ShadowColors(
        bg = background,
        navBg = mixColors(surface, background, if (dark) NIGHT_NAV_BLEND else DAY_NAV_BLEND),
        surface1 = surface,
        surface2 = surfaceVariant,
        surface3 = mixColors(onSurface, surfaceVariant, SURFACE3_BLEND),
        sheet = sheet,
        line = mixColors(surface, outline, LINE_BLEND),
        line2 = outline,
        ink1 = onSurface,
        ink2 = mixColors(onSurface, background, INK2_ALPHA),
        ink3 = ink3,
        amber = primary,
        onAmber = readableInkOn(primary),
        amberText = amberText,
        amberTint = primary.copy(alpha = tintAlpha),
        mint = secondary,
        onMint = readableInkOn(secondary),
        mintText = ensureContrast(secondary, background),
        mintTint = secondary.copy(alpha = tintAlpha),
        coral = error,
        onCoral = readableInkOn(error),
        coralText = ensureContrast(error, background),
        coralTint = error.copy(alpha = tintAlpha),
        sky = defaults.sky,
        skyText = defaults.skyText,
        skyTint = defaults.skyTint,
        skySoft = defaults.skySoft,
        scrim = defaults.scrim,
        amberMuted = if (dark) mixColors(primary, background, AMBER_MUTED_BLEND) else amberText,
        sheetLine = if (dark) {
            mixColors(onSurface, sheet, SHEET_LINE_BLEND)
        } else {
            mixColors(surface, outline, LINE_BLEND)
        },
        navLine = if (dark) surfaceVariant else mixColors(surface, outline, LINE_BLEND),
        orbTrack = if (dark) mixColors(surface, outline, LINE_BLEND) else outline,
        orbFillTop = if (dark) mixColors(onSurface, surface, ORB_FILL_BLEND) else surface,
        orbFillBottom = if (dark) surface else surfaceVariant,
        stateDotOff = if (dark) mixColors(ink3, background, STATE_DOT_OFF_BLEND) else ink3,
        isDark = dark,
    )
}

/** Near-black or white, whichever has the higher contrast on [color]. */
internal fun readableInkOn(color: Color): Color {
    val dark = Color(0xFF14161A)
    return if (contrastRatio(dark, color) >= contrastRatio(Color.White, color)) dark else Color.White
}

/** Opaque mix: [alpha] of [foreground] over [background]. */
internal fun mixColors(foreground: Color, background: Color, alpha: Float): Color {
    val backgroundAlpha = 1f - alpha
    return Color(
        red = foreground.red * alpha + background.red * backgroundAlpha,
        green = foreground.green * alpha + background.green * backgroundAlpha,
        blue = foreground.blue * alpha + background.blue * backgroundAlpha,
        alpha = 1f,
    )
}

/** WCAG contrast ratio between two opaque colors. */
internal fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + CONTRAST_OFFSET) / (darker + CONTRAST_OFFSET)
}

/** Moves [color] toward black/white until it reaches [minRatio] against [background]. */
internal fun ensureContrast(color: Color, background: Color, minRatio: Float = AA_TEXT_CONTRAST): Color {
    val target = if (background.luminance() < DARK_LUMINANCE_THRESHOLD) Color.White else Color.Black
    var candidate = color.copy(alpha = 1f)
    var step = 0
    while (contrastRatio(candidate, background) < minRatio && step < CONTRAST_MAX_STEPS) {
        candidate = mixColors(target, candidate, CONTRAST_STEP)
        step++
    }
    return candidate
}

private const val DARK_LUMINANCE_THRESHOLD = 0.5f
private const val NIGHT_TINT_ALPHA = 0.12f
private const val DAY_TINT_ALPHA = 0.14f
private const val SHEET_BLEND = 0.5f
private const val NIGHT_NAV_BLEND = 0.4f
private const val DAY_NAV_BLEND = 0.6f
private const val SURFACE3_BLEND = 0.045f
private const val LINE_BLEND = 0.35f
private const val INK2_ALPHA = 0.74f
private const val INK3_ALPHA = 0.58f
private const val AMBER_MUTED_BLEND = 0.85f
private const val SHEET_LINE_BLEND = 0.065f
private const val ORB_FILL_BLEND = 0.05f
private const val STATE_DOT_OFF_BLEND = 0.5f
private const val CONTRAST_OFFSET = 0.05f
private const val AA_TEXT_CONTRAST = 4.5f
private const val CONTRAST_STEP = 0.06f
private const val CONTRAST_MAX_STEPS = 40
