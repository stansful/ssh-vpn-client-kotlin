package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stansful.sshvpnclient.BuildConfig
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomSheet
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButtonStyle
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowProgressBar
import com.stansful.sshvpnclient.ui.designsystem.ShadowSpinner
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.pressScale
import com.stansful.sshvpnclient.ui.opensource.OpenSourceViewModel
import com.stansful.sshvpnclient.ui.shell.activityViewModel
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectViewModel
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/**
 * App update sheet; rendered once by the shell above every screen. It opens by itself when a check
 * offers a release and on request (Settings › Updates › View); it shows the offer, the download, a
 * stopped or rejected download and the install steps. [onGoHome] shows Home (the engine card's
 * "Go to Home" while a connection runs on the engine; the sheet closes first).
 */
@Composable
fun UpdateSheetHost(onGoHome: () -> Unit = {}) {
    val presenter = appUpdatePresenter()
    val open by presenter.sheetOpen.collectAsStateWithLifecycle()
    val state by presenter.state.collectAsStateWithLifecycle()
    val offer by presenter.offer.collectAsStateWithLifecycle()
    val lastProgress by presenter.lastProgress.collectAsStateWithLifecycle()
    val links = rememberLinkOpener()
    var installStep by rememberSaveable { mutableIntStateOf(0) }
    val installer = rememberUpdateInstaller(
        onFailed = presenter::onActionFailed,
        onInstallerOpened = { if (installStep == INSTALL_STEP_ALLOW) installStep = INSTALL_STEP_FINISH },
    )
    val phase = updatePhase(state, offer, lastProgress)
    LaunchedEffect(phase is UpdatePhase.Ready) {
        if (phase !is UpdatePhase.Ready) installStep = 0
    }
    if (!open || phase == UpdatePhase.None) return

    // The engine card (read only while the sheet is open).
    val routes: OpenSourceViewModel = activityViewModel()
    val smart: SmartConnectViewModel = activityViewModel()
    val routesState by routes.uiState.collectAsStateWithLifecycle()
    val smartState by smart.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(routes) { routes.refreshXrayCoreAvailability() }
    val engineState = routesState.xrayCoreUpdateState
    var engineDownloadSeen by remember { mutableStateOf(false) }
    var engineDownloadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(engineState.isDownloading, engineState.isChecking) {
        when {
            engineState.isDownloading -> {
                engineDownloadSeen = true
                engineDownloadFailed = false
            }
            engineState.isChecking -> engineDownloadFailed = false
            engineDownloadSeen -> {
                engineDownloadSeen = false
                engineDownloadFailed = engineState.engineOutcome() is EngineOutcome.Error
            }
        }
    }
    val engine = engineUi(
        installed = routesState.xrayCoreAvailable,
        state = engineState,
        inUse = routesState.anyXrayRuntimeActive || smartState.xrayRuntimeInUse,
        checkedAtMs = null,
    )

    val dismiss = { if (phase is UpdatePhase.Available) presenter.later() else presenter.closeSheet() }
    UpdateSheet(
        state = UpdateSheetState(
            phase = phase,
            currentVersion = BuildConfig.VERSION_NAME,
            release = offer,
            checking = state.isChecking,
            installStep = installStep,
            installError = if (phase is UpdatePhase.Ready) installErrorMessage(state) else null,
            engine = sheetEngine(engine, engineDownloadFailed),
            engineAbi = engine.runtimeAbi,
        ),
        actions = UpdateSheetActions(
            onDismiss = dismiss,
            onDownload = presenter::download,
            onDownloadAgain = presenter::downloadAgain,
            onHide = presenter::closeSheet,
            onReleaseNotes = { links.open(offer?.releaseUrl ?: GITHUB_RELEASES_URL) },
            onInstall = {
                (phase as? UpdatePhase.Ready)?.let {
                    if (installer.canInstallPackages) {
                        installer.install(it.contentUri)
                    } else {
                        installStep = INSTALL_STEP_ALLOW
                    }
                }
            },
            onInstallStep = {
                (phase as? UpdatePhase.Ready)?.let {
                    if (installStep == INSTALL_STEP_ALLOW) {
                        installer.requestInstallPermission(it.contentUri)
                    } else {
                        installer.install(it.contentUri)
                    }
                }
            },
            engine = SheetEngineActions(
                onCheck = routes::checkXrayCoreUpdates,
                onDownload = routes::downloadEngineForThisPhone,
                onCancel = routes::cancelXrayCoreDownload,
                onOpenRelease = { engine.release?.let { links.open(it.releaseUrl) } },
                onGoHome = {
                    presenter.closeSheet()
                    onGoHome()
                },
            ),
        ),
    )
}

/** What the update sheet shows. */
@Immutable
internal data class UpdateSheetState(
    val phase: UpdatePhase,
    val currentVersion: String,
    /** The offered release (notes, size, page); null when only the download survived a restart. */
    val release: AppUpdateInfo?,
    val checking: Boolean = false,
    /** 0 = not started, 1 = "Allow installs from shadow", 2 = "Finish in the Android installer". */
    val installStep: Int = 0,
    val installError: String? = null,
    /** The Xray engine card under the update; null hides it. */
    val engine: SheetEngine? = null,
    val engineAbi: String = "",
)

/** Callbacks of the update sheet; defaults do nothing (screenshots). */
@Immutable
internal class UpdateSheetActions(
    /** Close / scrim / Back / "Later": hides an offer until the next check, otherwise just closes. */
    val onDismiss: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onDownloadAgain: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onReleaseNotes: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onInstallStep: () -> Unit = {},
    val engine: SheetEngineActions = SheetEngineActions(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(state: UpdateSheetState, actions: UpdateSheetActions) {
    ShadowBottomSheet(onDismissRequest = actions.onDismiss, showClose = false) {
        UpdateSheetContent(state, actions)
    }
}

/** The sheet body (header + scrolling details); also rendered inline by the screenshot tests. */
@Composable
internal fun ColumnScope.UpdateSheetContent(state: UpdateSheetState, actions: UpdateSheetActions) {
    val phase = state.phase
    UpdateHeader(state, actions.onDismiss)
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 2.dp),
    ) {
        state.release?.let { release ->
            ReleaseNotesCard(
                release = release,
                initiallyOpen = phase !is UpdatePhase.Ready,
                modifier = Modifier.fadeUpIn(1),
            )
        }
        when (phase) {
            is UpdatePhase.Available -> AvailableBody(phase.update, state.checking, actions)
            is UpdatePhase.Downloading -> DownloadingBody(phase, actions)
            is UpdatePhase.Stopped -> StoppedBody(phase, actions)
            is UpdatePhase.Failed -> FailedBody(phase, state.checking, actions)
            is UpdatePhase.Ready -> ReadyBody(phase, state, actions)
            UpdatePhase.None -> Unit
        }
        state.engine?.let {
            UpdateSheetEngineSection(
                abi = state.engineAbi,
                engine = it,
                actions = actions.engine,
                modifier = Modifier.fadeUpIn(ENGINE_ENTRANCE_INDEX),
            )
        }
    }
}

@Composable
private fun UpdateHeader(state: UpdateSheetState, onClose: () -> Unit) {
    val colors = Shadow.colors
    val phase = state.phase
    val tone = when (phase) {
        is UpdatePhase.Ready -> SheetTone.Mint
        is UpdatePhase.Stopped, is UpdatePhase.Failed -> SheetTone.Coral
        else -> SheetTone.Amber
    }
    val busy = phase is UpdatePhase.Downloading
    val icon = when {
        phase is UpdatePhase.Downloading && phase.verifying -> ShadowIcons.ShieldOutline
        phase is UpdatePhase.Ready -> ShadowIcons.Shield
        phase is UpdatePhase.Stopped || phase is UpdatePhase.Failed -> ShadowIcons.Warning
        else -> ShadowIcons.Download
    }
    val current = state.currentVersion
    val subtitle = when (phase) {
        is UpdatePhase.Available -> "New version · you have $current"
        is UpdatePhase.Downloading -> if (phase.verifying) {
            "Checking the download"
        } else {
            "Downloading · you have $current"
        }
        is UpdatePhase.Ready -> "Downloaded and verified"
        is UpdatePhase.Stopped -> "Paused · you have $current"
        is UpdatePhase.Failed -> if (phase.rejected) {
            "File rejected · you have $current"
        } else {
            "Download failed · you have $current"
        }
        UpdatePhase.None -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fadeUpIn(0)
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(tone.tint(), ShadowShapes.TileLarge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone.ink(),
                modifier = Modifier
                    .size(26.dp)
                    .layerAlpha(blinkAlpha(busy)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "shadow ${phase.versionName(state.release) ?: ""}".trim(),
                style = Shadow.type.titleL,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SwapText(
                text = subtitle,
                style = Shadow.type.bodyS,
                color = colors.ink3,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ShadowIconButton(
            icon = ShadowIcons.Close,
            contentDescription = "Close",
            onClick = onClose,
            style = ShadowIconButtonStyle.Filled,
            size = 40.dp,
            iconSize = 18.dp,
            modifier = Modifier.align(Alignment.Top),
        )
    }
}

/** "What's new": the release notes as plain text, bullets for "-", "*" and "•" lines. */
@Composable
private fun ReleaseNotesCard(release: AppUpdateInfo, initiallyOpen: Boolean, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    var open by rememberSaveable(initiallyOpen) { mutableStateOf(initiallyOpen) }
    val lines = remember(release.releaseNotes) { releaseNoteLines(release.releaseNotes) }
    val bullets = lines.count { it.bullet }
    val customTitle = release.title.takeUnless { it.isDefaultReleaseTitle(release.versionName) }
    val chevron by animateFloatAsState(if (open) HALF_TURN else 0f, shadowTween(CHEVRON_MS), label = "notes-chevron")
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().settingsCard()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = ShadowFocusIndication,
                    role = Role.Button,
                    onClickLabel = if (open) "Hide release notes" else "Show release notes",
                ) { open = !open }
                .pressScale(interaction)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("WHAT'S NEW", style = Shadow.type.overline, color = colors.ink3, modifier = Modifier.weight(1f))
            if (bullets > 0) {
                Text(
                    text = if (bullets == 1) "1 change" else "$bullets changes",
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
            Icon(
                imageVector = ShadowIcons.ChevronDown,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(shadowTween(ShadowMotion.Surface), expandFrom = Alignment.Top) +
                fadeIn(shadowTween(ShadowMotion.Small)),
            exit = shrinkVertically(shadowTween(ShadowMotion.Surface), shrinkTowards = Alignment.Top) +
                fadeOut(shadowTween(ShadowMotion.Small)),
        ) {
            // The 136 dp cap is for the notes; the 16 dp padding comes on top, as in the artboard.
            Column(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .heightIn(max = NOTES_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                customTitle?.let {
                    Text(it, style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold), color = colors.ink1)
                }
                if (lines.isEmpty()) {
                    Text("Release notes are available on GitHub.", style = Shadow.type.body, color = colors.ink2)
                }
                lines.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (line.bullet) {
                            Box(
                                Modifier
                                    .padding(top = 9.dp)
                                    .size(4.dp)
                                    .background(colors.ink3, CircleShape),
                            )
                        }
                        Text(line.text, style = Shadow.type.body, color = colors.ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailableBody(update: AppUpdateInfo, checking: Boolean, actions: UpdateSheetActions) {
    val colors = Shadow.colors
    Column(Modifier.fadeUpIn(2)) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (update.apkSizeBytes > 0L) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 26.dp)
                        .background(colors.surface3, CircleShape)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatFileSize(update.apkSizeBytes),
                        style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium),
                        color = colors.ink2,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ShadowIcons.Shield, null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                Text("Signature checked before install", style = Shadow.type.bodyS, color = colors.ink3)
            }
        }
        ShadowButton(
            text = "Download",
            onClick = actions.onDownload,
            modifier = Modifier.fillMaxWidth(),
            icon = ShadowIcons.Download,
            loading = checking,
        )
        SecondaryRow(actions.onReleaseNotes, "Later", actions.onDismiss, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DownloadingBody(phase: UpdatePhase.Downloading, actions: UpdateSheetActions) {
    val colors = Shadow.colors
    val verifying = phase.verifying
    Column(Modifier.padding(top = 12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .settingsCard()
                .padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (verifying) ShadowSpinner(color = colors.amberText, size = 18.dp)
                SwapText(
                    text = if (verifying) "Checking signature…" else "Downloading ${phase.versionName}",
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink1,
                )
            }
            ShadowProgressBar(
                progress = if (verifying) 1f else phase.fraction,
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .layerAlpha(blinkAlpha(verifying))
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(phase.fraction ?: 0f, 0f..1f)
                    },
            )
            Text(
                text = buildString {
                    val total = phase.totalBytes
                    if (total != null) {
                        append("${formatMebibytes(phase.downloadedBytes)} of ${formatFileSize(total)}")
                    } else {
                        append(formatFileSize(phase.downloadedBytes))
                    }
                    phase.percent?.let { append(" · $it%") }
                },
                style = Shadow.type.monoS,
                color = colors.ink2,
            )
        }
        SwapText(
            text = if (verifying) {
                "Making sure the file is signed by the same key as the shadow you have now."
            } else {
                "Keeps going in the background. You can close this."
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
            maxLines = 3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
        )
        SecondaryRow(actions.onReleaseNotes, "Hide", actions.onHide, Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun StoppedBody(phase: UpdatePhase.Stopped, actions: UpdateSheetActions) {
    val saved = phase.savedBytes
    val total = phase.totalBytes
    Column(Modifier.padding(top = 12.dp)) {
        AlertBox(
            title = "Download stopped.",
            message = if (saved != null && total != null) {
                "${formatMebibytes(saved)} of ${formatFileSize(total)} is saved. Resume picks up from there."
            } else {
                phase.message
            },
            progress = if (saved != null && total != null && total > 0) saved / total.toFloat() else null,
        )
        ShadowButton(
            text = "Resume download",
            onClick = actions.onDownload,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            icon = ShadowIcons.Download,
        )
        SecondaryRow(actions.onReleaseNotes, "Later", actions.onDismiss, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun FailedBody(phase: UpdatePhase.Failed, checking: Boolean, actions: UpdateSheetActions) {
    Column(Modifier.padding(top = 12.dp)) {
        AlertBox(
            title = if (phase.rejected) {
                "The download failed the safety check and was deleted."
            } else {
                "The update couldn't be downloaded."
            },
            message = if (phase.rejected) {
                "The file was damaged or not signed by the shadow developer. Nothing was installed."
            } else {
                phase.message
            },
            progress = null,
        )
        ShadowButton(
            text = "Download again",
            onClick = actions.onDownloadAgain,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            icon = ShadowIcons.Refresh,
            loading = checking,
        )
        SecondaryRow(actions.onReleaseNotes, "Later", actions.onDismiss, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ReadyBody(phase: UpdatePhase.Ready, state: UpdateSheetState, actions: UpdateSheetActions) {
    val colors = Shadow.colors
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.mintTint, ShadowShapes.Banner)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShadowIcons.CheckCircle, null, tint = colors.mintText, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Ready to install",
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.mintText,
                )
                Text(
                    text = "Signed by the same key as your shadow ${state.currentVersion}.",
                    style = Shadow.type.bodyS,
                    color = colors.ink2,
                )
            }
        }
        ShadowButton(
            text = "Install ${phase.versionName}",
            onClick = actions.onInstall,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            icon = ShadowIcons.Download,
        )
        state.installError?.let {
            InlineErrorText(text = it, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp))
        }
        if (state.installStep == 0) {
            Text(
                text = "Android will ask to allow installs from shadow the first time. Installing restarts " +
                    "shadow and ends an active connection.",
                style = Shadow.type.bodyS,
                color = colors.ink3,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
        } else {
            InstallStepCard(step = state.installStep, onAction = actions.onInstallStep)
        }
        SecondaryRow(actions.onReleaseNotes, "Later", actions.onDismiss, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun InstallStepCard(step: Int, onAction: () -> Unit) {
    val colors = Shadow.colors
    val finish = step == INSTALL_STEP_FINISH
    Column(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .fadeUpIn()
            .settingsCard()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwapText(
                text = if (finish) "STEP 2 OF 2" else "STEP 1 OF 2",
                style = Shadow.type.overline,
                color = colors.ink3,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StepSegment(if (finish) colors.mint else colors.amber)
                StepSegment(if (finish) colors.amber else colors.line2)
            }
        }
        SwapText(
            text = if (finish) "Finish in the Android installer" else "Allow installs from shadow",
            style = Shadow.type.titleS,
            color = colors.ink1,
            modifier = Modifier.padding(top = 8.dp),
        )
        SwapText(
            text = if (finish) {
                "It opened on its own once installs were allowed. Tap Update there. If you back out, Install " +
                    "stays here."
            } else {
                "Android opens a settings page. Turn on Allow from this source, then come back. If you skip " +
                    "it, nothing is installed."
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
            maxLines = 4,
            modifier = Modifier.padding(top = 4.dp),
        )
        ShadowButton(
            text = if (finish) "Open installer" else "Open settings",
            onClick = onAction,
            variant = ShadowButtonVariant.Tonal,
            size = ShadowButtonSize.Compact,
            trailingIcon = ShadowIcons.External,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun StepSegment(color: Color) {
    val animated by animateColorAsState(
        color,
        shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "step-segment",
    )
    Box(
        Modifier
            .width(20.dp)
            .heightIn(min = 4.dp, max = 4.dp)
            .background(animated, CircleShape),
    )
}

/** Coral alert: warning icon, 15/600 title, 13/18 message, optional coral progress of what is saved. */
@Composable
private fun AlertBox(title: String, message: String, progress: Float?) {
    val colors = Shadow.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.coralTint, ShadowShapes.Banner)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .padding(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                ShadowIcons.Warning,
                null,
                tint = colors.coralText,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.coralText,
                )
                Text(message, style = Shadow.type.bodyS, color = colors.ink2)
            }
        }
        if (progress != null) {
            Box(
                Modifier
                    .padding(start = 28.dp, top = 12.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.coral.copy(alpha = ALERT_TRACK_ALPHA)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(colors.coral, CircleShape),
                )
            }
        }
    }
}

/** "Release notes ↗" (secondary, fills) + an amber text action ("Later" / "Hide"). */
@Composable
private fun SecondaryRow(onReleaseNotes: () -> Unit, textLabel: String, onText: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShadowButton(
            text = "Release notes",
            onClick = onReleaseNotes,
            modifier = Modifier.weight(1f),
            variant = ShadowButtonVariant.Secondary,
            trailingIcon = ShadowIcons.External,
        )
        ShadowButton(
            text = textLabel,
            onClick = onText,
            variant = ShadowButtonVariant.Text,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * Opacity of a blinking element (1 ↔ 0.35, 1.2 s); steady under reduced motion or when idle. Apply it
 * with [layerAlpha], so the blink redraws the layer instead of recomposing every frame.
 */
@Composable
internal fun blinkAlpha(active: Boolean): State<Float> {
    if (!active || Shadow.reducedMotion) return SteadyAlpha
    val transition = rememberInfiniteTransition(label = "blink")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = BLINK_LOW,
        animationSpec = infiniteRepeatable(tween(ShadowMotion.Blink / 2), RepeatMode.Reverse),
        label = "blink-alpha",
    )
}

/** Draws with [alpha], read in the layer (draw phase). */
internal fun Modifier.layerAlpha(alpha: State<Float>): Modifier = graphicsLayer { this.alpha = alpha.value }

/** Full opacity, never changing. */
private object SteadyAlpha : State<Float> {
    override val value: Float get() = 1f
}

private enum class SheetTone { Amber, Mint, Coral }

@Composable
private fun SheetTone.tint(): Color = when (this) {
    SheetTone.Amber -> Shadow.colors.amberTint
    SheetTone.Mint -> Shadow.colors.mintTint
    SheetTone.Coral -> Shadow.colors.coralTint
}

@Composable
private fun SheetTone.ink(): Color = when (this) {
    SheetTone.Amber -> Shadow.colors.amberText
    SheetTone.Mint -> Shadow.colors.mintText
    SheetTone.Coral -> Shadow.colors.coralText
}

private fun UpdatePhase.versionName(release: AppUpdateInfo?): String? = when (this) {
    is UpdatePhase.Available -> update.versionName
    is UpdatePhase.Downloading -> versionName
    is UpdatePhase.Ready -> versionName
    is UpdatePhase.Stopped -> versionName ?: release?.versionName
    is UpdatePhase.Failed -> release?.versionName
    UpdatePhase.None -> null
}

internal data class ReleaseNoteLine(val text: String, val bullet: Boolean)

/** Plain-text release notes: one entry per non-blank line; "-", "*" and "•" lines become bullets. */
internal fun releaseNoteLines(notes: String): List<ReleaseNoteLine> = notes.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        val marker = BulletMarkers.firstOrNull { line.startsWith(it) }
        if (marker != null) {
            ReleaseNoteLine(line.removePrefix(marker).trim(), bullet = true)
        } else {
            ReleaseNoteLine(line, bullet = false)
        }
    }

private fun String.isDefaultReleaseTitle(versionName: String): Boolean =
    isBlank() || this == versionName || this == "v$versionName" ||
        this == "shadow-ssh $versionName" || this == "shadow $versionName"

private val BulletMarkers = listOf("- ", "* ", "• ")
private val NOTES_MAX_HEIGHT = 136.dp
private const val ENGINE_ENTRANCE_INDEX = 4
private const val INSTALL_STEP_ALLOW = 1
private const val INSTALL_STEP_FINISH = 2
private const val HALF_TURN = 180f
private const val CHEVRON_MS = 320
private const val BLINK_LOW = 0.35f
private const val ALERT_TRACK_ALPHA = 0.20f
