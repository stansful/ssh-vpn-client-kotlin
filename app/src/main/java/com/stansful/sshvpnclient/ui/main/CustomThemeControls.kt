package com.stansful.sshvpnclient.ui.main

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.SectionHeader

@Composable
internal fun CustomThemeColorsEditor(
    colors: CustomThemeColors,
    onColorsChange: (CustomThemeColors) -> Unit,
) {
    var draft by remember(colors) { mutableStateOf(colors) }
    var showAdvanced by remember { mutableStateOf(false) }
    val hasChanges = draft != colors

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "Custom palette",
            subtitle = "Preview changes, then apply them once.",
        )
        InsetGroup {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CustomThemePreview(draft)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { draft = shadowThemeColors() },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Shadow")
                    }
                    FilledTonalButton(
                        onClick = { draft = CustomThemeColors.defaultLight() },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Light")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { draft = colors },
                        enabled = hasChanges,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Revert")
                    }
                    Button(
                        onClick = { onColorsChange(draft) },
                        enabled = hasChanges,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Apply")
                    }
                }
            }
        }

        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(if (showAdvanced) "Hide RGB controls" else "Advanced RGB controls")
            androidx.compose.material3.Icon(
                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        AnimatedVisibility(visible = showAdvanced) {
            InsetGroup {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ColorEditorRow(
                        label = "Primary",
                        color = draft.primary,
                        onColorChange = { draft = draft.copy(primary = it) },
                    )
                    ColorEditorRow(
                        label = "Success",
                        color = draft.secondary,
                        onColorChange = { draft = draft.copy(secondary = it) },
                    )
                    ColorEditorRow(
                        label = "Background",
                        color = draft.background,
                        onColorChange = { draft = draft.copy(background = it) },
                    )
                    ColorEditorRow(
                        label = "Surface",
                        color = draft.surface,
                        onColorChange = { draft = draft.copy(surface = it) },
                    )
                    ColorEditorRow(
                        label = "Surface variant",
                        color = draft.surfaceVariant,
                        onColorChange = { draft = draft.copy(surfaceVariant = it) },
                    )
                    ColorEditorRow(
                        label = "Text",
                        color = draft.onSurface,
                        onColorChange = { draft = draft.copy(onSurface = it) },
                    )
                    ColorEditorRow(
                        label = "Outline",
                        color = draft.outline,
                        onColorChange = { draft = draft.copy(outline = it) },
                    )
                    ColorEditorRow(
                        label = "Error",
                        color = draft.error,
                        onColorChange = { draft = draft.copy(error = it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomThemePreview(colors: CustomThemeColors) {
    Surface(
        color = Color(colors.surface),
        contentColor = Color(colors.onSurface),
        border = BorderStroke(1.dp, Color(colors.outline)),
        shape = MaterialTheme.shapes.medium,
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
    val shape = MaterialTheme.shapes.small
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

private fun shadowThemeColors(): CustomThemeColors = CustomThemeColors(
    primary = 0xFFFF9F1C.toInt(),
    secondary = 0xFF28C76F.toInt(),
    background = 0xFF000000.toInt(),
    surface = 0xFF090909.toInt(),
    surfaceVariant = 0xFF17110A.toInt(),
    onSurface = 0xFFF6F1EA.toInt(),
    outline = 0xFF3A2A18.toInt(),
    error = 0xFFFF6B6B.toInt(),
)

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
