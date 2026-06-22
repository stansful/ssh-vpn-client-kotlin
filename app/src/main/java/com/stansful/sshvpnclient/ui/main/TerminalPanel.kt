package com.stansful.sshvpnclient.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
internal fun TerminalPanel(
    state: MainUiState,
    onOpenTerminal: () -> Unit,
    onTerminalInputChange: (String) -> Unit,
    onSubmitTerminalInput: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val terminalState = state.terminalState
    val outputScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(expanded, state.isConnected) {
        if (expanded && state.isConnected && !terminalState.isOpen && !terminalState.isConnecting) {
            onOpenTerminal()
        }
    }

    LaunchedEffect(terminalState.output.length, expanded) {
        if (expanded) {
            outputScrollState.scrollTo(outputScrollState.maxValue)
        }
    }

    GlassPanel {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SSH Terminal", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = terminalStatusLabel(
                            connected = state.isConnected,
                            terminalState = terminalState,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    val icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore
                    val description = if (expanded) "Hide terminal" else "Show terminal"
                    Icon(icon, contentDescription = description)
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { -it / 5 },
                exit = fadeOut(tween(120)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TerminalOutputBox(
                        output = terminalState.output,
                        errorMessage = terminalState.errorMessage,
                        scrollState = outputScrollState,
                        onFocusInput = {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        },
                    )
                    OutlinedTextField(
                        value = terminalState.input,
                        onValueChange = onTerminalInputChange,
                        enabled = state.isConnected && terminalState.isOpen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        leadingIcon = {
                            Text(
                                text = "$",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { onSubmitTerminalInput() },
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalOutputBox(
    output: String,
    errorMessage: String?,
    scrollState: androidx.compose.foundation.ScrollState,
    onFocusInput: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = colorScheme.background.luminance() < 0.5f
    val backgroundColor = if (darkTheme) {
        Color.Black.copy(alpha = 0.42f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.44f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFocusInput,
            )
            .padding(12.dp)
            .verticalScroll(scrollState),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = output.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
            )
            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.error,
                )
            }
        }
    }
}

private fun terminalStatusLabel(
    connected: Boolean,
    terminalState: TerminalUiState,
): String {
    return when {
        !connected -> "Disconnected"
        terminalState.isConnecting -> "Opening shell"
        terminalState.isOpen -> "Shell active"
        else -> "Ready"
    }
}
