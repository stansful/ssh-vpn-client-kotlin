package com.stansful.sshvpnclient.ui.settings

import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.ui.theme.contrastRatio
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** The 8 editable roles of a custom palette, in the order the editor lists them. */
internal enum class PaletteRole(val label: String, val hint: String) {
    Primary("Primary", "Accent, buttons, progress"),
    Success("Success", "Connected, available, fast"),
    Background("Background", "Screens and system bars"),
    Surface("Surface", "Cards and lists"),
    SurfaceVariant("Surface variant", "Icon tiles, inputs, chips"),
    Text("Text", "Main text · muted at 74%"),
    Outline("Outline", "Borders and dividers"),
    Error("Error", "Errors, unavailable routes"),
}

internal operator fun CustomThemeColors.get(role: PaletteRole): Int = when (role) {
    PaletteRole.Primary -> primary
    PaletteRole.Success -> secondary
    PaletteRole.Background -> background
    PaletteRole.Surface -> surface
    PaletteRole.SurfaceVariant -> surfaceVariant
    PaletteRole.Text -> onSurface
    PaletteRole.Outline -> outline
    PaletteRole.Error -> error
}

internal fun CustomThemeColors.with(role: PaletteRole, argb: Int): CustomThemeColors {
    val opaque = argb or OPAQUE
    return when (role) {
        PaletteRole.Primary -> copy(primary = opaque)
        PaletteRole.Success -> copy(secondary = opaque)
        PaletteRole.Background -> copy(background = opaque)
        PaletteRole.Surface -> copy(surface = opaque)
        PaletteRole.SurfaceVariant -> copy(surfaceVariant = opaque)
        PaletteRole.Text -> copy(onSurface = opaque)
        PaletteRole.Outline -> copy(outline = opaque)
        PaletteRole.Error -> copy(error = opaque)
    }
}

/** Roles whose color differs between [this] draft and [saved]. */
internal fun CustomThemeColors.changedRoles(saved: CustomThemeColors): List<PaletteRole> =
    PaletteRole.entries.filter { this[it] != saved[it] }

internal val CustomThemeColorsSaver: Saver<CustomThemeColors, IntArray> = Saver(
    save = { colors -> PaletteRole.entries.map { colors[it] }.toIntArray() },
    restore = { values ->
        PaletteRole.entries.foldIndexed(CustomThemeColors.night()) { index, colors, role ->
            colors.with(role, values[index])
        }
    },
)

internal fun Int.red(): Int = (this shr RED_SHIFT) and CHANNEL_MAX
internal fun Int.green(): Int = (this shr GREEN_SHIFT) and CHANNEL_MAX
internal fun Int.blue(): Int = this and CHANNEL_MAX

internal fun rgb(red: Int, green: Int, blue: Int): Int =
    OPAQUE or
        (red.coerceIn(0, CHANNEL_MAX) shl RED_SHIFT) or
        (green.coerceIn(0, CHANNEL_MAX) shl GREEN_SHIFT) or
        blue.coerceIn(0, CHANNEL_MAX)

/** `#FF9F1C`. */
internal fun Int.toHex(): String = String.format(Locale.US, "#%06X", this and RGB_MASK)

/**
 * The hex field's text: keeps hex digits only (a pasted `#ff9f1c` works), upper case, at most 6.
 */
internal fun sanitizeHex(input: String): String =
    input.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.uppercase().take(HEX_DIGITS)

/** The color of a complete 6-digit hex text, else null. */
internal fun parseHex(text: String): Int? =
    text.takeIf { it.length == HEX_DIGITS }?.toIntOrNull(radix = 16)?.let { it or OPAQUE }

/** Hue in degrees (0–359) of an RGB color. */
internal fun Int.hue(): Int = hsl(this)[0].roundToInt() % DEGREES

/**
 * The color with its hue replaced, keeping saturation and lightness; greys get a usable saturation and
 * near-black/white a usable lightness first so the hue strip always changes something.
 */
internal fun Int.withHue(hue: Float): Int {
    val (_, saturation, lightness) = hsl(this)
    val s = if (saturation < MIN_SATURATION) GREY_SATURATION else saturation
    val l = when {
        lightness < NEAR_BLACK -> MIN_LIGHTNESS
        lightness > NEAR_WHITE -> MAX_LIGHTNESS
        else -> lightness
    }
    return fromHsl(hue, s, l)
}

/** WCAG contrast of two colors. */
internal fun contrast(first: Int, second: Int): Float = contrastRatio(Color(first), Color(second))

/** A dark base (light system-bar icons) when the background's luminance is below 0.5. */
internal fun isDarkBase(background: Int): Boolean = Color(background).luminance() < DARK_BASE_LUMINANCE

/** `4.6:1` — floored to one decimal so 4.49 never shows as passing 4.5. */
internal fun formatRatio(ratio: Float): String =
    String.format(Locale.US, "%.1f:1", floor(ratio * RATIO_PRECISION) / RATIO_PRECISION)

private fun hsl(argb: Int): FloatArray {
    val r = argb.red() / CHANNEL_MAX.toFloat()
    val g = argb.green() / CHANNEL_MAX.toFloat()
    val b = argb.blue() / CHANNEL_MAX.toFloat()
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, lightness)
    val delta = max - min
    val saturation = if (lightness > HALF) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> (g - b) / delta + if (g < b) HUE_SEXTANTS else 0f
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * HUE_SEXTANT_DEGREES
    return floatArrayOf(hue, saturation, lightness)
}

private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val sector = (((hue % DEGREES) + DEGREES) % DEGREES) / HUE_SEXTANT_DEGREES
    val x = chroma * (1f - abs(sector % 2f - 1f))
    val (r, g, b) = when (sector.toInt()) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = lightness - chroma / 2f
    return rgb(
        ((r + m) * CHANNEL_MAX).roundToInt(),
        ((g + m) * CHANNEL_MAX).roundToInt(),
        ((b + m) * CHANNEL_MAX).roundToInt(),
    )
}

internal const val CHANNEL_MAX = 255
internal const val DEGREES = 360
private const val HEX_DIGITS = 6
private const val OPAQUE = -0x1000000
private const val RGB_MASK = 0xFFFFFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val HALF = 0.5f
private const val HUE_SEXTANTS = 6f
private const val HUE_SEXTANT_DEGREES = 60f
private const val MIN_SATURATION = 0.08f
private const val GREY_SATURATION = 0.45f
private const val NEAR_BLACK = 0.03f
private const val NEAR_WHITE = 0.97f
private const val MIN_LIGHTNESS = 0.08f
private const val MAX_LIGHTNESS = 0.92f
private const val DARK_BASE_LUMINANCE = 0.5f
private const val RATIO_PRECISION = 10f
