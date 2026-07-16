package com.stansful.sshvpnclient.ui.main

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.stansful.sshvpnclient.ui.common.SectionHeader
import com.stansful.sshvpnclient.ui.common.StatusCapsule

@Composable
internal fun TerminalPanel(
    state: MainUiState,
    onOpenTerminal: () -> Unit,
    onCloseTerminal: () -> Unit,
    onTerminalInputChange: (String) -> Unit,
    onSubmitTerminalInput: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val terminalState = state.terminalState
    val outputScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val latestOnCloseTerminal by rememberUpdatedState(onCloseTerminal)

    LaunchedEffect(expanded, state.isConnected) {
        if (expanded && state.isConnected) {
            onOpenTerminal()
        } else if (!state.isConnected) {
            latestOnCloseTerminal()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        expanded = false
        latestOnCloseTerminal()
    }

    DisposableEffect(Unit) {
        onDispose { latestOnCloseTerminal() }
    }

    LaunchedEffect(terminalState.outputRevision, expanded) {
        if (expanded) {
            outputScrollState.scrollTo(outputScrollState.maxValue)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Terminal")
        GlassPanel {
            Column {
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (expanded) {
                            onCloseTerminal()
                        }
                        expanded = !expanded
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SSH Terminal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Private command channel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusCapsule(
                    text = terminalStatusLabel(
                        connected = state.isConnected,
                        terminalState = terminalState,
                    ),
                    color = if (terminalState.isOpen) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 4.dp),
                )
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
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
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
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
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFocusInput,
            )
            .padding(12.dp)
            .verticalScroll(scrollState),
    ) {
        SelectionContainer {
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
