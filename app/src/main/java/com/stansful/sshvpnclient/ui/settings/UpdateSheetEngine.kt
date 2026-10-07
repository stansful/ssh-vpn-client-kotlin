package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowProgressBar
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** What the update sheet's "Engine for Auto and Routes" card shows for an [EngineUi]. */
@Immutable
internal data class SheetEngine(
    val tone: StatusTone,
    val status: String,
    /** The status line in the tone's color (else ink-2 next to the colored dot). */
    val statusTinted: Boolean = false,
    /** Mono line under the status (asset, version · ABI). */
    val detail: String? = null,
    /** Checking or downloading: the dot blinks. */
    val busy: Boolean = false,
    val showProgress: Boolean = false,
    val showInUse: Boolean = false,
    val checkLabel: String? = null,
    val checking: Boolean = false,
    val downloadLabel: String? = null,
    val downloadEnabled: Boolean = true,
    val showCancel: Boolean = false,
    val showRelease: Boolean = false,
    val note: String? = null,
    val noteIsError: Boolean = false,
)

/**
 * Maps the engine state to the sheet card (Updates artboard: installed, not installed, checking,
 * available, downloading, failed, restart, in use, no asset). [downloadFailed] tells a failed download
 * ("Try again") from a failed lookup.
 */
internal fun sheetEngine(engine: EngineUi, downloadFailed: Boolean): SheetEngine {
    val release = engine.release
    val asset = engine.asset
    val abi = engine.runtimeAbi
    val outcome = engine.outcome
    val errorNote = (outcome as? EngineOutcome.Error)?.message
    val assetLine = asset?.let {
        val size = formatFileSize(it.sizeBytes)
        if (it.universal) "Universal AAR · $size" else "${it.name} · $size"
    }
    val versionLine = release?.let { "${it.versionName} · $abi" } ?: abi
    val base = if (engine.installed) {
        SheetEngine(StatusTone.Success, "Installed · $abi", checkLabel = "Check for engine updates")
    } else {
        SheetEngine(
            tone = StatusTone.Error,
            status = "Not installed",
            detail = "Auto and Routes need it to connect",
            checkLabel = "Find the engine for this phone",
        )
    }
    return when {
        engine.isChecking -> base.copy(
            tone = StatusTone.Progress,
            status = "Looking for the latest build…",
            statusTinted = true,
            detail = null,
            busy = true,
            checkLabel = "Checking…",
            checking = true,
        )
        engine.isDownloading -> SheetEngine(
            tone = StatusTone.Progress,
            status = "Downloading engine…",
            statusTinted = true,
            detail = assetLine,
            busy = true,
            showProgress = true,
            showCancel = true,
            note = "Keeps going if you close this sheet. Closing shadow from Recents stops it.",
        )
        outcome == EngineOutcome.NeedsRestart -> SheetEngine(
            tone = StatusTone.Success,
            status = "Installed — restart shadow to use it",
            statusTinted = true,
            detail = versionLine,
            note = "Until shadow restarts, Auto and Routes keep the engine that is running.",
        )
        outcome == EngineOutcome.Installed -> SheetEngine(
            tone = StatusTone.Success,
            status = "Installed · ready to use",
            statusTinted = true,
            detail = versionLine,
            checkLabel = "Check for engine updates",
        )
        release != null && asset == null -> SheetEngine(
            tone = StatusTone.Error,
            status = "No $abi build in ${release.versionName}",
            statusTinted = true,
            detail = if (engine.installed) {
                "The installed engine keeps working"
            } else {
                "Nothing to download for this phone yet"
            },
            showRelease = true,
        )
        release != null && downloadFailed && errorNote != null -> SheetEngine(
            tone = StatusTone.Error,
            status = "Download failed · nothing changed",
            statusTinted = true,
            detail = assetLine,
            downloadLabel = "Try again",
            downloadEnabled = !engine.inUse,
            showRelease = true,
            showInUse = engine.inUse,
            note = "${errorNote.trimEnd('.')}. Engine downloads can’t resume, so the next try starts over.",
            noteIsError = true,
        )
        release != null -> SheetEngine(
            tone = StatusTone.Progress,
            status = "${release.versionName} available",
            detail = assetLine,
            downloadLabel = "Download",
            downloadEnabled = !engine.inUse,
            showRelease = true,
            showInUse = engine.inUse,
            note = when (outcome) {
                EngineOutcome.Cancelled -> "Download cancelled. Nothing changed."
                else -> errorNote
            },
            noteIsError = errorNote != null,
        )
        else -> base.copy(note = errorNote, noteIsError = errorNote != null)
    }
}

/** Callbacks of the sheet's engine card. */
@Immutable
internal class SheetEngineActions(
    val onCheck: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenRelease: () -> Unit = {},
    /** "Go to Home" under "Disconnect first…": closes the sheet and shows Home (null hides it). */
    val onGoHome: (() -> Unit)? = null,
)

/** "Engine for Auto and Routes": the Xray engine's state and actions under the app update. */
@Composable
internal fun UpdateSheetEngineSection(
    abi: String,
    engine: SheetEngine,
    actions: SheetEngineActions,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val ink = colors.toneText(engine.tone)
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "ENGINE FOR AUTO AND ROUTES",
                style = Shadow.type.overline,
                color = colors.ink3,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline()
                    .semantics { heading() },
            )
            Text(text = abi, style = Shadow.type.monoS, color = colors.ink3, modifier = Modifier.alignByBaseline())
        }
        Column(
            Modifier
                .fillMaxWidth()
                .settingsCard()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = ShadowIcons.Engine, tint = ink)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Xray engine", style = Shadow.type.titleS, color = colors.ink1)
                    Row(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .layerAlpha(blinkAlpha(engine.busy))
                                .background(ink, CircleShape),
                        )
                        SwapText(
                            text = engine.status,
                            style = Shadow.type.bodyS,
                            color = if (engine.statusTinted) ink else colors.ink2,
                        )
                    }
                    engine.detail?.let {
                        Text(
                            text = it,
                            style = Shadow.type.monoS,
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (engine.showProgress) {
                ShadowProgressBar(
                    progress = null,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
                )
            }
            AnimatedVisibility(
                visible = engine.showInUse,
                enter = expandVertically(shadowTween(ShadowMotion.Surface), expandFrom = Alignment.Top) +
                    fadeIn(shadowTween(ShadowMotion.Small)),
                exit = shrinkVertically(shadowTween(ShadowMotion.Surface), shrinkTowards = Alignment.Top) +
                    fadeOut(shadowTween(ShadowMotion.Small)),
            ) {
                InlineBanner(
                    message = "Disconnect first. Your connection is running on the engine right now.",
                    tone = BannerTone.Warning,
                    modifier = Modifier.padding(top = 14.dp),
                    action = actions.onGoHome?.let { goHome -> { BannerLink(text = "Go to Home", onClick = goHome) } },
                )
            }
            EngineButtons(engine, actions)
            engine.note?.let {
                SwapText(
                    text = it,
                    style = Shadow.type.bodyS,
                    color = if (engine.noteIsError) colors.coralText else colors.ink3,
                    maxLines = 4,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * Updates.dc.html's amber 13/600 text link in a banner: a 44 dp tall target pulled up 4 dp into the
 * text's line and reaching through the banner's bottom padding (margin -4 0 -12), so the banner keeps
 * its text-only height plus one link line.
 */
@Composable
private fun BannerLink(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val up = (BANNER_ACTION_GAP + LINK_OVERLAP).roundToPx()
            val down = LINK_INTO_PADDING.roundToPx()
            layout(placeable.width, (placeable.height - up - down).coerceAtLeast(0)) {
                placeable.place(0, -up)
            }
        },
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .shadowClickable(interaction, onClick = onClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = text, style = Shadow.type.label, color = Shadow.colors.amberText)
        }
    }
}

/** InlineBanner's gap between its text and the action. */
private val BANNER_ACTION_GAP = 12.dp
private val LINK_OVERLAP = 4.dp
private val LINK_INTO_PADDING = 12.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineButtons(engine: SheetEngine, actions: SheetEngineActions) {
    val any = engine.checkLabel != null || engine.downloadLabel != null || engine.showCancel || engine.showRelease
    if (!any) return
    FlowRow(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        engine.checkLabel?.let {
            ShadowButton(
                text = it,
                onClick = actions.onCheck,
                variant = ShadowButtonVariant.Secondary,
                size = EngineButtonSize,
                icon = ShadowIcons.Refresh,
                loading = engine.checking,
            )
        }
        engine.downloadLabel?.let {
            ShadowButton(
                text = it,
                onClick = actions.onDownload,
                variant = ShadowButtonVariant.Tonal,
                size = EngineButtonSize.copy(horizontalPadding = 16.dp),
                icon = ShadowIcons.Download,
                enabled = engine.downloadEnabled,
            )
        }
        if (engine.showCancel) {
            ShadowButton(
                text = "Cancel",
                onClick = actions.onCancel,
                variant = ShadowButtonVariant.Secondary,
                size = EngineButtonSize,
                icon = ShadowIcons.Close,
            )
        }
        if (engine.showRelease) {
            ShadowButton(
                text = "Release",
                onClick = actions.onOpenRelease,
                variant = ShadowButtonVariant.Secondary,
                size = EngineButtonSize,
                trailingIcon = ShadowIcons.External,
                contentDescription = "Open the engine release on GitHub",
            )
        }
    }
}

private fun ShadowColors.toneText(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ink2
    StatusTone.Progress -> amberText
    StatusTone.Success -> mintText
    StatusTone.Error -> coralText
    StatusTone.Info -> skyText
}

/** 40 dp, radius 12, 13/600 buttons of the engine card. */
private val EngineButtonSize = ShadowButtonSize(40.dp, 14.dp, 16.dp, 12.dp, ShadowButtonLabel.Small)
