package com.stansful.sshvpnclient.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.CustomThemeColors

@Composable
internal fun CustomThemeColorsEditor(
    colors: CustomThemeColors,
    onColorsChange: (CustomThemeColors) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CustomThemePreview(colors)

        ColorEditorRow(
            label = "Primary",
            color = colors.primary,
            onColorChange = { onColorsChange(colors.copy(primary = it)) },
        )
        ColorEditorRow(
            label = "Success",
            color = colors.secondary,
            onColorChange = { onColorsChange(colors.copy(secondary = it)) },
        )
        ColorEditorRow(
            label = "Background",
            color = colors.background,
            onColorChange = { onColorsChange(colors.copy(background = it)) },
        )
        ColorEditorRow(
            label = "Surface",
            color = colors.surface,
            onColorChange = { onColorsChange(colors.copy(surface = it)) },
        )
        ColorEditorRow(
            label = "Surface variant",
            color = colors.surfaceVariant,
            onColorChange = { onColorsChange(colors.copy(surfaceVariant = it)) },
        )
        ColorEditorRow(
            label = "Text",
            color = colors.onSurface,
            onColorChange = { onColorsChange(colors.copy(onSurface = it)) },
        )
        ColorEditorRow(
            label = "Outline",
            color = colors.outline,
            onColorChange = { onColorsChange(colors.copy(outline = it)) },
        )
        ColorEditorRow(
            label = "Error",
            color = colors.error,
            onColorChange = { onColorsChange(colors.copy(error = it)) },
        )
    }
}

@Composable
private fun CustomThemePreview(colors: CustomThemeColors) {
    Surface(
        color = Color(colors.surface),
        contentColor = Color(colors.onSurface),
        border = BorderStroke(1.dp, Color(colors.outline)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorSwatch(colors.primary)
            ColorSwatch(colors.secondary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Custom",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = colorHex(colors.primary),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(colors.onSurface).copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun ColorEditorRow(
    label: String,
    color: Int,
    onColorChange: (Int) -> Unit,
) {
    val red = color.redChannel()
    val green = color.greenChannel()
    val blue = color.blueChannel()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorSwatch(color)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = colorHex(color),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            RgbTextField(
                label = "R",
                value = red,
                onValueChange = { onColorChange(argb(it, green, blue)) },
                modifier = Modifier.weight(1f),
            )
            RgbTextField(
                label = "G",
                value = green,
                onValueChange = { onColorChange(argb(red, it, blue)) },
                modifier = Modifier.weight(1f),
            )
            RgbTextField(
                label = "B",
                value = blue,
                onValueChange = { onColorChange(argb(red, green, it)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RgbTextField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { rawValue ->
            val sanitized = rawValue.filter(Char::isDigit).take(RGB_MAX_DIGITS)
            text = sanitized
            sanitized.toIntOrNull()
                ?.coerceIn(MIN_RGB_VALUE, MAX_RGB_VALUE)
                ?.let(onValueChange)
        },
        modifier = modifier.widthIn(min = 72.dp),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ColorSwatch(color: Int) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(shape)
            .background(Color(color))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                shape = shape,
            ),
    )
}

private fun Int.redChannel(): Int = (this ushr RED_SHIFT) and CHANNEL_MASK

private fun Int.greenChannel(): Int = (this ushr GREEN_SHIFT) and CHANNEL_MASK

private fun Int.blueChannel(): Int = this and CHANNEL_MASK

private fun argb(
    red: Int,
    green: Int,
    blue: Int,
): Int {
    return (ALPHA_MASK shl ALPHA_SHIFT) or
        (red.coerceIn(MIN_RGB_VALUE, MAX_RGB_VALUE) shl RED_SHIFT) or
        (green.coerceIn(MIN_RGB_VALUE, MAX_RGB_VALUE) shl GREEN_SHIFT) or
        blue.coerceIn(MIN_RGB_VALUE, MAX_RGB_VALUE)
}

private fun colorHex(color: Int): String {
    return "#${color.redChannel().hex()}${color.greenChannel().hex()}${color.blueChannel().hex()}"
}

private fun Int.hex(): String = toString(radix = HEX_RADIX).padStart(length = 2, padChar = '0').uppercase()

private const val MIN_RGB_VALUE = 0
private const val MAX_RGB_VALUE = 255
private const val RGB_MAX_DIGITS = 3
private const val HEX_RADIX = 16
private const val CHANNEL_MASK = 0xFF
private const val ALPHA_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
