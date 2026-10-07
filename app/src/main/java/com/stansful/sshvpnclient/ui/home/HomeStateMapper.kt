package com.stansful.sshvpnclient.ui.home

import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.designsystem.OrbState
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.formatLatency
import com.stansful.sshvpnclient.ui.main.MainUiState
import com.stansful.sshvpnclient.ui.main.TunnelCheckResult
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectUiState
import java.util.Locale

/**
 * What Home is built from: the three activity-scoped ViewModel states plus Home's own transient
 * state (a mode switch in progress, a denied VPN permission, the retry clock, app labels).
 */
internal data class HomeSources(
    val main: MainUiState,
    val auto: SmartConnectUiState,
    val routes: OpenSourceUiState,
    val forcedMode: HomeMode? = null,
    val permissionDeniedMode: HomeMode? = null,
    val pendingSwitch: HomeMode? = null,
    val dismissedUpdateKey: String? = null,
    val checkedRouteId: String? = null,
    val nowElapsedMs: Long = 0L,
    val selectedAppLabels: List<String> = emptyList(),
    /**
     * The whole route library straight from the repository. `routes.profiles` is the Route
     * library's searched/filtered list, so a search there must not hide the active route on Home.
     */
    val routeLibrary: List<ProxyProfileSummary>? = null,
)

/** The route library Home works with (never the Route library screen's filtered view). */
internal fun HomeSources.libraryRoutes(): List<ProxyProfileSummary> = routeLibrary ?: routes.profiles

/** The active (selected) library route, whether or not a Route library search hides it. */
internal fun HomeSources.activeRoute(): ProxyProfileSummary? =
    if (routeLibrary != null) routeLibrary.firstOrNull(ProxyProfileSummary::isSelected) else routes.selectedProfile

/**
 * Routes mode can connect: `OpenSourceUiState.canStartOpenSource` for the unfiltered active route,
 * which must be current (an outdated route left the public list and can't connect).
 */
internal fun HomeSources.canStartRoutes(): Boolean = activeRoute()?.isStale == false &&
    routes.xrayCoreAvailable &&
    !routes.isChecking &&
    !routes.isRemovingUnavailable &&
    routes.vpnState.status != VpnConnectionStatus.DISCONNECTING

/** The mode that owns a running session (a tile start included), if any. */
internal fun HomeSources.activeOwnerMode(): HomeMode? {
    val vpn = main.vpnState
    val owner = vpn.sessionOwner
    return when {
        owner != null && vpn.status in ACTIVE_STATUSES -> HomeMode.of(owner)
        auto.isActive -> HomeMode.Auto
        else -> null
    }
}

/** The mode Home shows: a switch in progress, else the session owner, else the stored mode. */
internal fun HomeSources.displayedMode(): HomeMode =
    forcedMode ?: activeOwnerMode() ?: HomeMode.of(main.appSettings.activeGlobalTab)

/** True while the session of [mode] is connecting or connected (the "Switch to X?" condition). */
internal fun HomeSources.isSessionActive(mode: HomeMode): Boolean = when (mode) {
    HomeMode.Server -> main.canDisconnect
    HomeMode.Auto -> auto.isActive
    HomeMode.Routes -> routes.xrayConnected
}

internal fun buildHomeUiState(sources: HomeSources): HomeUiState {
    val mode = sources.displayedMode()
    val settings = sources.main.appSettings
    val base = when (mode) {
        HomeMode.Server -> serverState(sources)
        HomeMode.Auto -> autoState(sources)
        HomeMode.Routes -> routesState(sources)
    }
    val appRouting = appRoutingChip(settings)
    val showActivity = settings.showConnectionActivity
    val chips = buildList {
        addAll(base.leadingChips)
        add(appRouting)
        when (mode) {
            HomeMode.Routes -> add(HomeChip.RouteLibrary)
            HomeMode.Auto -> {
                val auto = sources.auto
                add(HomeChip.RoutePool(if (auto.isPicking()) 0 else auto.rankedProfiles.size))
            }
            HomeMode.Server -> Unit
        }
        if (showActivity) add(HomeChip.Activity)
    }
    val tabletChips = buildList {
        addAll(base.tabletChips)
        if (mode == HomeMode.Routes) add(HomeChip.RouteLibrary)
        add(appRouting)
        if (mode == HomeMode.Auto && showActivity) add(HomeChip.Activity)
    }
    val diagnostics = sources.main.vpnState.diagnostics
    val notes = buildList {
        if (base.permissionDenied) add(HomeNote.PermissionDenied)
        addAll(base.notes)
    }
    return HomeUiState(
        mode = mode,
        hero = base.hero,
        notes = notes,
        card = base.card,
        firstRun = base.firstRun,
        chips = chips,
        checkFailedServer = base.checkFailedServer,
        showCheckCaption = base.showCheckCaption,
        updateBanner = updateBanner(sources.main.updateState)?.takeIf { it.key != sources.dismissedUpdateKey },
        showActivity = showActivity,
        serverOptions = serverOptions(sources.main),
        routeOptions = routeOptions(sources),
        pool = poolSheet(sources.auto),
        pendingSwitch = sources.pendingSwitch,
        switchMessage = sources.pendingSwitch?.let { switchMessage(sources, mode) }.orEmpty(),
        showNoAppsDialog = sources.main.showNoSelectedAppsDialog ||
            sources.auto.showNoSelectedAppsDialog ||
            sources.routes.showNoSelectedAppsDialog,
        tabletChips = tabletChips,
        tabletChipNote = base.tabletChipNote,
        tabletAuto = tabletAuto(sources.auto),
        tabletServer = tabletServer(sources.main),
        tabletRoute = tabletRoute(sources),
        activityLines = diagnostics.takeLast(ACTIVITY_PREVIEW_LINES),
        activityLineCount = diagnostics.size,
        appRoutingSummary = appRoutingSummary(settings, sources.selectedAppLabels),
        appRoutingTag = appRouting.label,
    )
}

/** The per-mode part of [HomeUiState]. */
private data class ModeState(
    val hero: HomeHero,
    val card: HomeCard? = null,
    val notes: List<HomeNote> = emptyList(),
    val firstRun: Boolean = false,
    val permissionDenied: Boolean = false,
    val leadingChips: List<HomeChip> = emptyList(),
    val tabletChips: List<HomeChip> = emptyList(),
    val tabletChipNote: String = "",
    val checkFailedServer: String? = null,
    val showCheckCaption: Boolean = false,
)

// region Server

private fun serverState(sources: HomeSources): ModeState {
    val main = sources.main
    val selected = main.selectedConfig
    val status = main.sshStatus
    val error = main.sshErrorMessage
    val denied = sources.permissionDeniedMode == HomeMode.Server || error == SSH_PERMISSION_DENIED
    val permissionError = error != null && error in PERMISSION_ERRORS
    val name = selected?.name ?: "your server"
    val active = status in ACTIVE_STATUSES

    if (!main.configsLoaded) {
        return ModeState(hero = idleHero(HomeMode.Server, headline = "", subline = "", enabled = false))
    }
    if (selected == null && !active) {
        val firstRun = main.configs.isEmpty()
        val hero = HomeHero(
            tone = StatusTone.Neutral,
            pillLabel = NOT_CONNECTED,
            headline = if (firstRun) "Add a server to start" else "Choose a server to start",
            subline = plain(
                if (firstRun) "Or switch to Auto — no server needed" else "Pick one of your saved servers",
            ),
            orb = OrbState.Disabled,
            orbLabel = CONNECT,
            orbDescription = if (firstRun) "Connect — add a server first" else "Connect — choose a server first",
        )
        return ModeState(
            hero = hero,
            firstRun = firstRun,
            card = if (firstRun) {
                null
            } else {
                noServerCard()
            },
            tabletChips = serverTabletChips(main, connected = false),
            tabletChipNote = "Check tunnel unlocks once a server is connected.",
        )
    }

    val hero = when {
        status == VpnConnectionStatus.CONNECTING -> busyHero(
            pill = "Connecting",
            headline = "Connecting to $name",
            subline = plain(NOT_PROTECTED_YET),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Connecting,
            description = "Stop — Server mode, $name",
        )
        status == VpnConnectionStatus.CONNECTED -> HomeHero(
            tone = StatusTone.Success,
            pillLabel = "Connected",
            headline = "Connected to $name",
            subline = plain("SSH tunnel up · DNS over SSH"),
            orb = OrbState.Connected,
            orbLabel = DISCONNECT,
            orbDescription = "Disconnect — Server mode, $name",
            tabletSubline = plain("SSH tunnel up · DNS goes through the server"),
        )
        status == VpnConnectionStatus.RECONNECTING -> busyHero(
            pill = "Reconnecting",
            headline = "Reconnecting to $name",
            subline = plain("Connection dropped · trying again"),
            orb = OrbState.Reconnecting,
            label = DISCONNECT,
            description = "Disconnect — Server is reconnecting",
        )
        status == VpnConnectionStatus.DISCONNECTING -> stoppingHero("Closing the tunnel to $name")
        status == VpnConnectionStatus.ERROR && !permissionError -> errorHero(
            headline = "Couldn't connect to $name",
            description = "Connect — couldn't connect to $name",
            enabled = main.canConnect,
        )
        else -> idleHero(
            mode = HomeMode.Server,
            headline = "Connect to $name",
            subline = "Your traffic goes through your own server",
            enabled = main.canConnect,
            description = "Connect — Server mode, $name",
        )
    }
    val notes = buildList {
        if (status == VpnConnectionStatus.RECONNECTING) add(HomeNote.ServerReconnecting)
        if (status == VpnConnectionStatus.ERROR && error != null && !permissionError) {
            add(failureNote(error, HomeMode.Server))
        }
        if (error == VPN_PERMISSION_REQUIRED) add(HomeNote.Info(PERMISSION_REQUIRED_NOTE))
    }
    val connected = status == VpnConnectionStatus.CONNECTED
    val checkState = main.checkState()
    val checkChip = HomeChip.CheckTunnel(checkState, main.tunnelCheckLatencyMs, enabled = connected)
    val leading = buildList {
        if (connected) {
            add(checkChip)
            if (main.appSettings.showTerminalOnMain) add(HomeChip.Terminal())
        }
    }
    val card = selected?.let { config ->
        HomeCard(
            overline = "Server",
            title = config.name,
            subtitle = config.address(),
            icon = CardIcon.Server,
            tone = hero.tone,
            target = CardTarget.ChooseServer,
            contentDescription = "Server: ${config.name}, ${config.address()}. Change server",
        )
    } ?: noServerCard()
    return ModeState(
        hero = hero,
        card = card,
        notes = notes,
        permissionDenied = denied,
        leadingChips = leading,
        tabletChips = serverTabletChips(main, connected),
        tabletChipNote = serverChipNote(main, name, connected, checkState),
        checkFailedServer = name.takeIf { connected && checkState == CheckState.Failed },
        showCheckCaption = connected && checkState != CheckState.Failed,
    )
}

/** The selected server was deleted or never picked (a session may still run through the old one). */
private fun noServerCard() = HomeCard(
    overline = "Server",
    title = "No server selected",
    subtitle = "Tap to choose one",
    subtitleMono = false,
    icon = CardIcon.Server,
    tone = StatusTone.Neutral,
    target = CardTarget.ChooseServer,
    contentDescription = "Server: none selected. Choose a server",
)

private fun serverTabletChips(main: MainUiState, connected: Boolean): List<HomeChip> = buildList {
    add(HomeChip.CheckTunnel(main.checkState(), main.tunnelCheckLatencyMs, enabled = connected))
    if (main.appSettings.showTerminalOnMain) add(HomeChip.Terminal(enabled = connected))
}

private fun serverChipNote(main: MainUiState, name: String, connected: Boolean, check: CheckState): String {
    val tools = if (main.appSettings.showTerminalOnMain) "Check tunnel and Terminal unlock" else "Check tunnel unlocks"
    return when {
        !connected -> "$tools once $name is connected."
        check == CheckState.Running -> "Trying the server through the tunnel…"
        check == CheckState.Ok -> {
            val target = main.tunnelCheckTarget ?: "The server"
            val latency = main.tunnelCheckLatencyMs?.let { " in $it ms" }.orEmpty()
            "$target answered through the tunnel$latency."
        }
        check == CheckState.Failed -> "The server didn't forward traffic. See Connection activity for details."
        else -> "Check tunnel opens a test connection through your server."
    }
}

private fun MainUiState.checkState(): CheckState = when {
    isTunnelCheckRunning -> CheckState.Running
    tunnelCheckResult == TunnelCheckResult.SUCCESS -> CheckState.Ok
    tunnelCheckResult == TunnelCheckResult.FAILURE -> CheckState.Failed
    else -> CheckState.Idle
}

private fun SshConfigSummary.address(): String = "$username@$host:$port"

private fun SshConfigSummary.signInLabel(): String = when (authType) {
    AuthType.PASSWORD -> "Password"
    AuthType.PRIVATE_KEY -> keyName?.let { "Key · $it" } ?: "Key"
}

private fun serverOptions(main: MainUiState): List<ServerOption> = main.configs.map { config ->
    ServerOption(
        id = config.id,
        name = config.name,
        address = config.address(),
        signIn = if (config.authType == AuthType.PASSWORD) "Password" else "Key",
        verified = !config.fingerprint.isNullOrBlank(),
        selected = config.id == main.selectedConfig?.id,
    )
}

private fun tabletServer(main: MainUiState): TabletServer? {
    val config = main.selectedConfig ?: return null
    val status = main.sshStatus
    return TabletServer(
        name = config.name,
        address = config.address(),
        signIn = config.signInLabel(),
        fingerprintSaved = !config.fingerprint.isNullOrBlank(),
        statusLabel = when (status) {
            VpnConnectionStatus.CONNECTED -> "Connected"
            VpnConnectionStatus.CONNECTING -> "Connecting"
            VpnConnectionStatus.RECONNECTING -> "Reconnecting"
            else -> null
        },
        statusTone = if (status == VpnConnectionStatus.CONNECTED) StatusTone.Success else StatusTone.Progress,
        hint = if (main.canDisconnect) {
            "Picking another one reconnects through it"
        } else {
            "${main.configs.size} saved · tap one to use it"
        },
    )
}

// endregion

// region Auto

private fun autoState(sources: HomeSources): ModeState {
    val auto = sources.auto
    val workflow = auto.workflow
    val phase = workflow.phase
    val message = auto.visibleMessage
    val denied = sources.permissionDeniedMode == HomeMode.Auto || message == SMART_PERMISSION_DENIED
    // The quick tile couldn't start Auto without the permission: not a failure, Connect asks for it.
    val permissionRequired = !auto.isActive && message == VPN_PERMISSION_REQUIRED
    val failed = !auto.isActive && phase == SmartConnectPhase.ERROR && !permissionRequired
    val engineMissing = auto.xrayCoreChecked && !auto.xrayCoreAvailable ||
        message?.contains(ENGINE_MISSING_MARKER) == true
    // Settings is installing the engine: Connect waits for it (SmartConnectUiState.canStart).
    val engineInstalling = !auto.isActive && auto.xrayCoreUpdate.isDownloading
    // Until the engine has been probed once, Connect keeps its normal look (no 40 % flash on launch).
    val canStart = auto.canStart || !auto.xrayCoreChecked && !auto.isActive
    val routeName = workflow.activeProfileName ?: auto.selectedProfile?.name ?: "the fastest route"
    val searching = auto.isActive && phase in SEARCHING_PHASES || auto.isActive && phase == SmartConnectPhase.IDLE

    val hero = when {
        failed -> errorHero(
            headline = "Auto couldn't connect",
            description = "Connect — Auto couldn't connect",
            enabled = canStart,
        )
        !auto.isActive -> idleHero(
            mode = HomeMode.Auto,
            headline = "Find the fastest route",
            subline = "Checks the public list and keeps the best route alive",
            enabled = canStart,
        )
        else -> activeAutoHero(sources, routeName)
    }
    val notes = buildList {
        if (engineMissing && !engineInstalling) {
            add(HomeNote.EngineMissing(auto.xrayCoreUpdate.compatibleAsset?.sizeBytes?.let(::formatMebibytes)))
        }
        if (failed && message != null && !message.contains(ENGINE_MISSING_MARKER)) {
            add(failureNote(message, HomeMode.Auto))
        }
        if (permissionRequired) add(HomeNote.Info(PERMISSION_REQUIRED_NOTE))
        if (searching) add(HomeNote.AutoSearching)
        if (auto.isActive && phase == SmartConnectPhase.RETRY_WAIT && workflow.checkCompleted > 0) {
            add(HomeNote.LastPass(workflow.checkCompleted))
        }
        if (engineInstalling) add(HomeNote.Info(ENGINE_INSTALLING_NOTE))
        if (!auto.isActive && phase != SmartConnectPhase.ERROR) {
            autoInfoNote(message)?.takeUnless { engineInstalling && it == HomeNote.Info(ENGINE_INSTALLING_NOTE) }
                ?.let(::add)
        }
    }
    return ModeState(
        hero = hero,
        card = autoCard(auto, hero.tone),
        notes = notes,
        permissionDenied = denied,
        tabletChipNote = "Auto tests and switches routes on its own. Nothing to run by hand.",
    )
}

private fun activeAutoHero(sources: HomeSources, routeName: String): HomeHero {
    val auto = sources.auto
    val workflow = auto.workflow
    // "Restoring Smart Connect", "Waiting for Android to restore Smart Connect".
    val restoring = workflow.message?.contains("restor", ignoreCase = true) == true
    return when (workflow.phase) {
        SmartConnectPhase.WAITING_FOR_NETWORK -> busyHero(
            pill = "Waiting for network",
            headline = "Waiting for a network",
            subline = plain("Auto continues once you're online"),
            orb = OrbState.Connecting,
        )
        SmartConnectPhase.REFRESHING -> busyHero(
            pill = "Updating list",
            headline = "Updating the route list",
            subline = plain(NOT_PROTECTED_YET),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Connecting,
        )
        SmartConnectPhase.CHECKING -> busyHero(
            pill = "Testing routes",
            headline = "Testing routes",
            subline = listOf(
                TextPart(workflow.checkCompleted.toString(), mono = true),
                TextPart(" of "),
                TextPart(workflow.checkTotal.toString(), mono = true),
                TextPart(" tested"),
            ),
            orb = OrbState.Progress(auto.checkingProgress ?: 0f),
            description = "Stop — Auto is testing routes",
        ).copy(tabletHeadline = "Finding the fastest route")
        SmartConnectPhase.CLEANING -> busyHero(
            pill = "Cleaning up",
            headline = "Dropping dead routes",
            subline = plain("Removing unavailable routes"),
            orb = OrbState.Connecting,
        )
        SmartConnectPhase.SELECTING -> busyHero(
            pill = "Picking",
            headline = "Picking the fastest",
            subline = listOf(
                TextPart(workflow.availableCount.toString(), mono = true),
                TextPart(" working routes in the pool"),
            ),
            orb = OrbState.Connecting,
        )
        SmartConnectPhase.CONNECTING -> busyHero(
            pill = "Connecting",
            headline = "Connecting to $routeName",
            subline = plain(NOT_PROTECTED_YET),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Connecting,
        )
        SmartConnectPhase.VERIFYING -> busyHero(
            pill = "Verifying",
            headline = "Verifying the route",
            subline = plain("Opening youtube.com through the tunnel"),
            orb = OrbState.Connecting,
        )
        SmartConnectPhase.CONNECTED -> HomeHero(
            tone = StatusTone.Success,
            pillLabel = "Connected",
            headline = "Connected via $routeName",
            subline = plain(
                workflow.lastHealthLatencyMs?.let { "Verified · live check $it ms" } ?: "Verified",
            ),
            orb = OrbState.Connected,
            orbLabel = DISCONNECT,
            orbDescription = "Disconnect — Auto mode",
        )
        SmartConnectPhase.FAILING_OVER -> busyHero(
            pill = "Switching route",
            headline = "$routeName stopped answering",
            subline = plain("Finding a replacement · traffic is not protected"),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Reconnecting,
            label = DISCONNECT,
            description = "Disconnect — Auto is switching routes",
        )
        SmartConnectPhase.RETRY_WAIT -> retryHero(sources)
        SmartConnectPhase.STOPPING -> stoppingHero("Stopping Auto")
        SmartConnectPhase.ERROR -> errorHero(
            headline = "Auto couldn't connect",
            description = "Stop — Auto couldn't connect",
            enabled = true,
        ).copy(orbLabel = STOP)
        SmartConnectPhase.STARTING,
        SmartConnectPhase.IDLE,
        -> busyHero(
            pill = "Starting",
            headline = if (restoring) "Restoring Auto" else "Starting Auto",
            subline = plain(NOT_PROTECTED_YET),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Connecting,
        )
    }
}

private fun retryHero(sources: HomeSources): HomeHero {
    val auto = sources.auto
    val delayMs = auto.workflow.retryDelayMs ?: 0L
    val elapsed = auto.retryWaitStartedAtMs?.let { (sources.nowElapsedMs - it).coerceAtLeast(0L) } ?: 0L
    val leftMs = (delayMs - elapsed).coerceAtLeast(0L)
    val leftSeconds = (leftMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val fraction = if (delayMs > 0L) leftMs.toFloat() / delayMs.toFloat() else 0f
    return HomeHero(
        tone = StatusTone.Progress,
        pillLabel = "Retrying in",
        pillClock = String.format(Locale.US, "%d:%02d", leftSeconds / 60, leftSeconds % 60),
        pillBlink = false,
        headline = "No working route yet",
        subline = listOf(
            TextPart("Next pass in "),
            TextPart(leftSeconds.toString(), mono = true),
            TextPart(" s"),
        ),
        orb = OrbState.Waiting(fraction),
        orbLabel = STOP,
        orbDescription = "Stop — Auto is waiting to retry",
    )
}

private fun autoCard(auto: SmartConnectUiState, tone: StatusTone): HomeCard {
    val workflow = auto.workflow
    val phase = workflow.phase
    val profile = auto.activeOrSelectedProfile()
    val picking = auto.isActive && (phase in PICKING_PHASES || phase == SmartConnectPhase.IDLE)
    fun card(
        title: String,
        subtitle: String,
        mono: Boolean,
        warning: Boolean = false,
        icon: CardIcon = CardIcon.Auto,
        meter: Boolean = false,
    ) = HomeCard(
        overline = "Auto-picked route",
        title = title,
        subtitle = subtitle,
        subtitleMono = mono,
        subtitleWarning = warning,
        icon = icon,
        tone = tone,
        latencyMs = workflow.activeProfileLatencyMs ?: profile?.lastLatencyMs,
        showMeter = meter,
        target = CardTarget.RoutePool,
        contentDescription = "Auto-picked route: $title. Show route pool",
    )
    return when {
        auto.isActive && phase == SmartConnectPhase.RETRY_WAIT -> card(
            title = "No route yet",
            subtitle = "Traffic isn't protected while waiting",
            mono = false,
            warning = true,
            icon = CardIcon.Clock,
        )
        picking -> when (phase) {
            SmartConnectPhase.CHECKING -> card(
                "Choosing a route…",
                "${workflow.checkCompleted} of ${workflow.checkTotal} tested so far",
                mono = true,
            )
            SmartConnectPhase.CLEANING -> card("Choosing a route…", "Removing unavailable routes", mono = false)
            SmartConnectPhase.SELECTING ->
                card("Choosing a route…", "${workflow.availableCount} working so far", mono = true)
            else -> card("Choosing a route…", "Updating the list", mono = false)
        }
        profile != null -> card(
            title = workflow.activeProfileName ?: profile.name,
            subtitle = profile.routeSubtitle(),
            mono = true,
            meter = true,
        )
        else -> card("No route yet", "Auto picks one when you connect", mono = false)
    }
}

/** Auto is testing or waiting: the pool count of the last pass would be stale. */
private fun SmartConnectUiState.isPicking(): Boolean =
    isActive && (workflow.phase in PICKING_PHASES || workflow.phase == SmartConnectPhase.RETRY_WAIT)

private fun SmartConnectUiState.activeOrSelectedProfile(): ProxyProfileSummary? =
    rankedProfiles.firstOrNull { it.id == workflow.activeProfileId } ?: selectedProfile

private fun autoInfoNote(message: String?): HomeNote? = when {
    message == null ||
        message == SMART_PERMISSION_DENIED ||
        message == "Smart Connect stopped" ||
        message.contains(ENGINE_MISSING_MARKER) -> null
    message == "VPN permission revoked by Android" ->
        HomeNote.Info(
            "Android turned the VPN off, usually because another VPN app started. Tap Connect to start Auto again.",
        )
    message.startsWith("Smart Connect restore cancelled") ->
        HomeNote.Info("Auto didn't resume because another connection was running.")
    message == "VPN permission is required to restore Smart Connect" ->
        HomeNote.Info("Auto needs the VPN permission to resume. Tap Connect to allow it.")
    message == "Tap Start to restore Smart Connect" -> HomeNote.Info("Tap Connect to resume Auto.")
    message == "Wait until the Xray core installation finishes" -> HomeNote.Info(ENGINE_INSTALLING_NOTE)
    else -> HomeNote.Info(message)
}

private fun poolSheet(auto: SmartConnectUiState): PoolSheet {
    val workflow = auto.workflow
    val phase = workflow.phase
    val ranked = auto.rankedProfiles
    val pickProfile = auto.activeOrSelectedProfile()
    val pick = pickProfile?.let { profile ->
        PoolPick(
            name = workflow.activeProfileName?.takeIf { workflow.activeProfileId == profile.id } ?: profile.name,
            subtitle = profile.routeSubtitle(),
            pickedAtMs = workflow.activeProfileLatencyMs?.takeIf { workflow.activeProfileId == profile.id }
                ?: profile.lastLatencyMs,
            tag = when {
                auto.isActive && phase == SmartConnectPhase.CONNECTED -> PickTag.InUse
                auto.isActive && (phase == SmartConnectPhase.CONNECTING || phase == SmartConnectPhase.VERIFYING) ->
                    PickTag.Verifying
                else -> PickTag.LastPick
            },
            liveMs = workflow.lastHealthLatencyMs.takeIf { auto.isActive && phase == SmartConnectPhase.CONNECTED },
            liveRunning = auto.isActive && phase == SmartConnectPhase.CONNECTED,
        )
    }
    val others = ranked.withIndex()
        .filter { it.value.id != pickProfile?.id }
        .take(POOL_OTHER_ROWS)
        .map { (index, profile) -> PoolRow(index + 1, profile.name, profile.routeSubtitle(), profile.lastLatencyMs) }
    val shown = others.size + if (ranked.any { it.id == pickProfile?.id }) 1 else 0
    val searchingBeforeCheck = auto.isActive &&
        (phase in BEFORE_CHECK_PHASES || phase == SmartConnectPhase.IDLE)
    val (strong, rest) = when {
        searchingBeforeCheck -> "Updating the list" to " · every route is tested next"
        auto.isActive && phase == SmartConnectPhase.CHECKING -> "Testing now" to " · results are saved at clean-up"
        workflow.checkTotal > 0 && workflow.availableCount > 0 ->
            "${workflow.availableCount} of ${workflow.checkTotal} routes" to " passed the last test"
        ranked.isNotEmpty() -> "${ranked.size} working routes" to " from the last test"
        else -> "No routes yet" to " · Auto tests the public list when you connect"
    }
    return PoolSheet(
        subtitleStrong = strong,
        subtitleRest = rest,
        workingCount = ranked.size,
        pick = pick,
        others = others,
        moreCount = (ranked.size - shown).coerceAtLeast(0),
        currentStep = autoStep(auto),
        runState = when {
            auto.isActive && phase == SmartConnectPhase.CONNECTED -> AutoRunState.Connected
            auto.isActive -> AutoRunState.Searching
            else -> AutoRunState.Stopped
        },
    )
}

private fun autoStep(auto: SmartConnectUiState): Int {
    if (!auto.isActive) return -1
    return when (auto.workflow.phase) {
        SmartConnectPhase.IDLE,
        SmartConnectPhase.STARTING,
        SmartConnectPhase.WAITING_FOR_NETWORK,
        SmartConnectPhase.REFRESHING,
        SmartConnectPhase.FAILING_OVER,
        -> 0
        SmartConnectPhase.CHECKING -> 1
        SmartConnectPhase.CLEANING -> 2
        SmartConnectPhase.SELECTING -> 3
        SmartConnectPhase.CONNECTING, SmartConnectPhase.VERIFYING -> 4
        SmartConnectPhase.CONNECTED -> 5
        SmartConnectPhase.RETRY_WAIT,
        SmartConnectPhase.STOPPING,
        SmartConnectPhase.ERROR,
        -> -1
    }
}

private fun tabletAuto(auto: SmartConnectUiState): TabletAuto {
    val workflow = auto.workflow
    val phase = workflow.phase
    val testing = auto.isActive && phase == SmartConnectPhase.CHECKING
    val connected = auto.isActive && phase == SmartConnectPhase.CONNECTED
    val verifying = auto.isActive && phase == SmartConnectPhase.VERIFYING
    val pick = auto.activeOrSelectedProfile()
    val top = auto.rankedProfiles.take(TABLET_POOL_ROWS)
    val pickedIndex = top.indexOfFirst { it.id == pick?.id }
    return TabletAuto(
        caption = when {
            connected -> "In use now"
            auto.isActive -> "Picking the lowest latency"
            else -> "Kept from your last session"
        },
        testingLabel = if (testing) "Testing routes ${workflow.checkCompleted}/${workflow.checkTotal}" else null,
        testingFraction = if (testing) auto.checkingProgress ?: 0f else null,
        pickedAt = if (testing) "—" else formatLatency(workflow.activeProfileLatencyMs ?: pick?.lastLatencyMs),
        live = when {
            connected -> formatLatency(workflow.lastHealthLatencyMs)
            verifying -> "Verifying…"
            else -> "—"
        },
        liveTone = when {
            connected && workflow.lastHealthLatencyMs != null -> StatusTone.Success
            verifying -> StatusTone.Progress
            else -> StatusTone.Neutral
        },
        showUnprotected = auto.isActive && phase in SEARCHING_PHASES,
        poolCaption = when {
            testing -> "Re-testing · ${workflow.checkCompleted} of ${workflow.checkTotal} tested"
            workflow.checkTotal > 0 && workflow.availableCount > 0 ->
                "${workflow.availableCount} working of ${workflow.checkTotal} tested"
            else -> "${auto.rankedProfiles.size} working routes"
        },
        pool = top.mapIndexed { index, profile ->
            PoolRow(index + 1, profile.name, profile.routeSubtitle(), profile.lastLatencyMs)
        },
        pickedRank = if (pickedIndex >= 0) pickedIndex + 1 else null,
    )
}

// endregion

// region Routes

private fun routesState(sources: HomeSources): ModeState {
    val routes = sources.routes
    val vpn = routes.vpnState
    val profile = sources.activeRoute()
    val library = sources.libraryRoutes()
    val ownsSession = vpn.sessionOwner == VpnSessionOwner.OPEN_SOURCE && vpn.status in ACTIVE_STATUSES
    // Like `openSourceErrorMessage`, for the unfiltered active route.
    val error = vpn.errorMessage.takeIf {
        profile != null &&
            vpn.status == VpnConnectionStatus.ERROR &&
            vpn.activeTransport == null &&
            vpn.activeConfigId == profile.id
    }
    val denied = sources.permissionDeniedMode == HomeMode.Routes
    // Both ViewModels probe the same engine; the Auto one says when the first probe has finished.
    val engineInstalled = routes.xrayCoreAvailable || sources.auto.xrayCoreAvailable
    val engineMissing = sources.auto.xrayCoreChecked && !engineInstalled ||
        error?.contains(ENGINE_MISSING_MARKER) == true
    val canStart = sources.canStartRoutes()
    if (sources.routeLibrary == null && routes.allProfileIds.isEmpty() && !ownsSession) {
        // The library has not been read yet: don't flash "Your route library is empty".
        return ModeState(hero = idleHero(HomeMode.Routes, headline = "", subline = "", enabled = false))
    }
    val name = profile?.name ?: "this route"
    val permissionError = error != null && error in PERMISSION_ERRORS

    val hero = when {
        ownsSession && vpn.status == VpnConnectionStatus.CONNECTING -> busyHero(
            pill = "Connecting",
            headline = "Starting $name",
            subline = plain(NOT_PROTECTED_YET),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Connecting,
            description = "Stop — Routes mode, $name",
        )
        ownsSession && vpn.status == VpnConnectionStatus.CONNECTED -> HomeHero(
            tone = StatusTone.Success,
            pillLabel = "Connected",
            headline = "Connected via $name",
            subline = plain(
                "Engine running · last check " +
                    (profile?.lastLatencyMs?.let { "$it ms" } ?: "not run"),
            ),
            orb = OrbState.Connected,
            orbLabel = DISCONNECT,
            orbDescription = "Disconnect — Routes mode, $name",
            tabletSubline = plain(
                "Xray engine running · " +
                    (profile?.lastLatencyMs?.let { "last check $it ms" } ?: "route not checked yet"),
            ),
        )
        ownsSession && vpn.status == VpnConnectionStatus.RECONNECTING -> busyHero(
            pill = "Reconnecting",
            headline = "Reconnecting via $name",
            subline = plain(NOT_PROTECTED_YET),
            sublineTone = SublineTone.Warning,
            orb = OrbState.Reconnecting,
            label = DISCONNECT,
            description = "Disconnect — Routes is reconnecting",
        )
        ownsSession -> stoppingHero("Stopping the engine")
        profile == null -> HomeHero(
            tone = StatusTone.Neutral,
            pillLabel = NOT_CONNECTED,
            headline = if (library.isEmpty()) "Your route library is empty" else "Pick a route to start",
            subline = plain(
                if (library.isEmpty()) {
                    "Add or refresh routes in the Route library"
                } else {
                    "Choose one in your route library"
                },
            ),
            orb = OrbState.Disabled,
            orbLabel = CONNECT,
            orbDescription = "Connect — pick a route first",
        )
        profile.isStale -> HomeHero(
            tone = StatusTone.Neutral,
            pillLabel = NOT_CONNECTED,
            headline = "$name is outdated",
            subline = plain("It left the public list · pick a current route"),
            orb = OrbState.Disabled,
            orbLabel = CONNECT,
            orbDescription = "Connect — $name is outdated, pick a current route",
        )
        error != null && !permissionError -> errorHero(
            headline = "$name didn't start",
            description = "Connect — $name didn't start",
            enabled = canStart,
        )
        else -> idleHero(
            mode = HomeMode.Routes,
            headline = "Connect via $name",
            subline = if (routes.isChecking) {
                "Route checks are running · connect when they finish"
            } else {
                "Routed through the engine with this route"
            },
            enabled = canStart,
            description = "Connect — Routes mode, $name",
        ).let { idle ->
            if (routes.isChecking) {
                idle
            } else {
                idle.copy(tabletSubline = plain("Routed through the Xray engine with this route"))
            }
        }
    }
    val notes = buildList {
        if (engineMissing) {
            val update = routes.xrayCoreUpdateState
            val asset = update.release?.assets?.firstOrNull { it.abi == update.runtimeAbi }
            add(HomeNote.EngineMissing(asset?.sizeBytes?.let(::formatMebibytes)))
        }
        if (!ownsSession && error != null && !permissionError && !error.contains(ENGINE_MISSING_MARKER)) {
            add(failureNote(error, HomeMode.Routes))
        }
        if (error == VPN_PERMISSION_REQUIRED) add(HomeNote.Info(PERMISSION_REQUIRED_NOTE))
    }
    val card = if (profile != null && profile.isStale && !ownsSession) {
        HomeCard(
            overline = "Active route",
            title = profile.name,
            subtitle = "Outdated · pick a current route",
            subtitleMono = false,
            subtitleWarning = true,
            icon = CardIcon.Routes,
            tone = StatusTone.Neutral,
            target = CardTarget.QuickSwitch,
            contentDescription = "Active route: ${profile.name}, outdated. Pick a current route",
        )
    } else if (profile != null) {
        HomeCard(
            overline = "Active route",
            title = profile.name,
            subtitle = profile.routeSubtitle(),
            icon = CardIcon.Routes,
            tone = hero.tone,
            latencyMs = profile.lastLatencyMs,
            showMeter = true,
            target = CardTarget.QuickSwitch,
            contentDescription = "Active route: ${profile.name}, " +
                "last check ${formatLatency(profile.lastLatencyMs)}. Show options",
        )
    } else {
        HomeCard(
            overline = "Active route",
            title = "No route selected",
            subtitle = "Pick one in the route library",
            subtitleMono = false,
            icon = CardIcon.Routes,
            tone = StatusTone.Neutral,
            target = if (library.isEmpty()) CardTarget.RouteLibrary else CardTarget.QuickSwitch,
            contentDescription = "Active route: none selected. Choose a route",
        )
    }
    val check = routeCheckState(sources)
    val checkEnabled = !ownsSession && profile != null && engineInstalled && !routes.anyXrayRuntimeActive
    return ModeState(
        hero = hero,
        card = card,
        notes = notes,
        permissionDenied = denied,
        tabletChips = listOf(
            HomeChip.CheckRoute(check, profile?.lastLatencyMs, enabled = checkEnabled || check == CheckState.Running),
        ),
        tabletChipNote = when {
            profile == null -> "Pick a route to check it."
            ownsSession -> "Disconnect to check routes. The engine is busy with this connection."
            check == CheckState.Running -> "Asking YouTube through $name…"
            check == CheckState.Ok -> "$name answered YouTube in ${formatLatency(profile.lastLatencyMs)}."
            check == CheckState.Failed -> "$name didn't answer YouTube."
            else -> "Check route asks YouTube through $name and records its latency."
        },
    )
}

private fun routeCheckState(sources: HomeSources): CheckState {
    val routes = sources.routes
    val profile = sources.activeRoute() ?: return CheckState.Idle
    return when {
        routes.isChecking -> CheckState.Running
        sources.checkedRouteId != profile.id -> CheckState.Idle
        profile.lastTestStatus == ProxyTestStatus.AVAILABLE -> CheckState.Ok
        profile.lastTestStatus == ProxyTestStatus.UNAVAILABLE ||
            profile.lastTestStatus == ProxyTestStatus.UNSUPPORTED -> CheckState.Failed
        else -> CheckState.Idle
    }
}

private fun routeOptions(sources: HomeSources): List<RouteOption> {
    val profiles = sources.libraryRoutes()
    // Outdated routes can't connect: only the active one is listed (marked), none is offered.
    val pinned = profiles.filter { it.isPinned && !it.isStale }.take(QUICK_SWITCH_PINNED)
    val fastest = profiles.asSequence()
        .filter { !it.isPinned && !it.isStale && it.lastTestStatus == ProxyTestStatus.AVAILABLE }
        .sortedBy { it.lastLatencyMs ?: Long.MAX_VALUE }
        .take(QUICK_SWITCH_FASTEST)
        .toList()
    val selected = sources.activeRoute()
    val list = buildList {
        if (selected != null && selected !in pinned && selected !in fastest) add(selected)
        addAll(pinned)
        addAll(fastest)
    }
    return list.map { profile ->
        RouteOption(
            id = profile.id,
            name = profile.name,
            subtitle = profile.routeSubtitle(),
            latencyMs = profile.lastLatencyMs,
            pinned = profile.isPinned,
            selected = profile.id == selected?.id,
            outdated = profile.isStale,
        )
    }
}

private fun tabletRoute(sources: HomeSources): TabletRoute? {
    val routes = sources.routes
    val profile = sources.activeRoute() ?: return null
    val profiles = sources.libraryRoutes()
    fun count(status: ProxyTestStatus) = profiles.count { it.lastTestStatus == status }
    val notChecked = count(ProxyTestStatus.NOT_TESTED) + count(ProxyTestStatus.RUNNING)
    return TabletRoute(
        name = profile.name,
        subtitle = profile.routeSubtitle(),
        latencyMs = profile.lastLatencyMs,
        transport = profile.transportLabel(),
        pinned = profile.isPinned,
        manual = profile.source != ProxyProfileSource.REMOTE,
        note = if (profile.lastLatencyMs == null) "Not checked yet" else "Latency from last check",
        hint = if (routes.xrayConnected) {
            "Picking another one restarts the engine with it"
        } else {
            "Pinned first, then by latency"
        },
        counts = "${profiles.size} routes · ${count(ProxyTestStatus.AVAILABLE)} available · " +
            "${count(ProxyTestStatus.UNAVAILABLE)} unavailable · " +
            "${count(ProxyTestStatus.UNSUPPORTED)} unsupported · " +
            "$notChecked not checked",
    )
}

private fun ProxyProfileSummary.transportLabel(): String {
    val transportPart = transport.xrayValue.uppercase(Locale.US)
    if (security == ProxySecurity.NONE) return transportPart
    return "$transportPart · ${security.xrayValue.uppercase(Locale.US)}"
}

// endregion

// region Shared

private fun ProxyProfileSummary.routeSubtitle(): String = "${protocol.scheme} · $host:$port"

private fun idleHero(
    mode: HomeMode,
    headline: String,
    subline: String,
    enabled: Boolean,
    description: String = "Connect — ${mode.label} mode",
) = HomeHero(
    tone = StatusTone.Neutral,
    pillLabel = NOT_CONNECTED,
    headline = headline,
    subline = plain(subline),
    orb = if (enabled) OrbState.Off else OrbState.Disabled,
    orbLabel = CONNECT,
    orbDescription = description,
)

private fun busyHero(
    pill: String,
    headline: String,
    subline: List<TextPart>,
    orb: OrbState,
    sublineTone: SublineTone = SublineTone.Muted,
    label: String = STOP,
    description: String = "$label — Auto mode",
) = HomeHero(
    tone = StatusTone.Progress,
    pillLabel = pill,
    headline = headline,
    subline = subline,
    sublineTone = sublineTone,
    orb = orb,
    orbLabel = label,
    orbDescription = description,
)

private fun stoppingHero(subline: String) = HomeHero(
    tone = StatusTone.Progress,
    pillLabel = "Disconnecting",
    headline = "Disconnecting",
    subline = plain(subline),
    orb = OrbState.Stopping,
    orbLabel = "Stopping",
    orbDescription = "Stopping",
    orbEnabled = false,
)

private fun errorHero(headline: String, description: String, enabled: Boolean) = HomeHero(
    tone = StatusTone.Error,
    pillLabel = "Couldn't connect",
    headline = headline,
    subline = plain("Not connected · traffic goes direct"),
    orb = OrbState.Error,
    orbLabel = CONNECT,
    orbDescription = description,
    orbEnabled = enabled,
)

/** Error text from the domain, with the fix the user can reach from Home. */
internal fun failureNote(message: String, mode: HomeMode): HomeNote.Failure {
    val sentence = if (message.endsWith(".")) message else "$message."
    return when {
        mode == HomeMode.Server && message.startsWith("Selected SSH key not found") ->
            HomeNote.Failure(
                "The key for this server is missing.",
                "Pick another key or add it again.",
                NoteAction.EditServer,
            )
        mode == HomeMode.Server && message.startsWith("Fingerprint mismatch") ->
            HomeNote.Failure(
                sentence,
                "The server's identity doesn't match the saved fingerprint.",
                NoteAction.EditServer,
            )
        mode == HomeMode.Server && message.startsWith("Authentication failed") ->
            HomeNote.Failure(sentence, "Check the password or key in the server's settings.", NoteAction.EditServer)
        mode == HomeMode.Server && message.startsWith("Invalid private key") ->
            HomeNote.Failure(sentence, "Check the key and its passphrase.", NoteAction.EditServer)
        message == "No apps selected" ->
            HomeNote.Failure(sentence, "Pick at least one app in App routing.", NoteAction.PickApps)
        else -> HomeNote.Failure(sentence, "See Connection activity for details.", NoteAction.OpenActivity)
    }
}

private fun appRoutingChip(settings: AppSettings): HomeChip.AppRouting {
    val label = if (settings.vpnMode == VpnMode.SELECTED_APPS) {
        val count = settings.selectedAppPackages.size
        if (count == 1) "1 app" else "$count apps"
    } else {
        "All apps"
    }
    return HomeChip.AppRouting(
        label = label,
        tabletLabel = "App routing · $label",
        contentDescription = "App routing: $label",
    )
}

private fun appRoutingSummary(settings: AppSettings, labels: List<String>): String =
    if (settings.vpnMode == VpnMode.SELECTED_APPS) {
        if (labels.isEmpty()) "Only selected apps" else "Only selected apps · ${labels.joinToString(", ")}"
    } else {
        "All apps · every app uses the VPN"
    }

private fun updateBanner(update: AppUpdateState): UpdateBanner? = when (val download = update.downloadState) {
    is AppUpdateDownloadState.ReadyToInstall -> UpdateBanner(UpdateBanner.Kind.Ready, download.versionName)
    is AppUpdateDownloadState.Downloading ->
        UpdateBanner(UpdateBanner.Kind.Downloading, download.versionName, download.progressPercent)
    else -> update.availableUpdate?.let { UpdateBanner(UpdateBanner.Kind.Available, it.versionName) }
}

private fun switchMessage(sources: HomeSources, current: HomeMode): String {
    val connected = when (current) {
        HomeMode.Server -> sources.main.sshStatus == VpnConnectionStatus.CONNECTED
        HomeMode.Auto -> sources.auto.workflow.phase == SmartConnectPhase.CONNECTED
        HomeMode.Routes -> sources.routes.vpnState.status == VpnConnectionStatus.CONNECTED
    }
    val state = if (connected) "connected" else "connecting"
    val target = when (current) {
        HomeMode.Server -> sources.main.selectedConfig?.name?.let { " to $it" }.orEmpty()
        else -> ""
    }
    return "${current.label} is $state$target. Only one connection can run at a time, so switching stops it first."
}

internal fun formatMebibytes(sizeBytes: Long): String =
    String.format(Locale.US, "%.1f MiB", sizeBytes.toDouble() / (1_024.0 * 1_024.0))

private val ACTIVE_STATUSES = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.CONNECTED,
    VpnConnectionStatus.RECONNECTING,
    VpnConnectionStatus.DISCONNECTING,
)

/** Phases in which Auto's traffic bypasses the tunnel while it looks for a route. */
private val SEARCHING_PHASES = setOf(
    SmartConnectPhase.STARTING,
    SmartConnectPhase.WAITING_FOR_NETWORK,
    SmartConnectPhase.REFRESHING,
    SmartConnectPhase.CHECKING,
    SmartConnectPhase.CLEANING,
    SmartConnectPhase.SELECTING,
    SmartConnectPhase.CONNECTING,
    SmartConnectPhase.FAILING_OVER,
)

/** Phases in which Auto has not picked a route yet. */
private val PICKING_PHASES = setOf(
    SmartConnectPhase.STARTING,
    SmartConnectPhase.WAITING_FOR_NETWORK,
    SmartConnectPhase.REFRESHING,
    SmartConnectPhase.CHECKING,
    SmartConnectPhase.CLEANING,
    SmartConnectPhase.SELECTING,
)

private val BEFORE_CHECK_PHASES = setOf(
    SmartConnectPhase.STARTING,
    SmartConnectPhase.WAITING_FOR_NETWORK,
    SmartConnectPhase.REFRESHING,
)

/** Shown by MainViewModel / SmartConnectViewModel when Android's VPN dialog was declined. */
private const val SSH_PERMISSION_DENIED = "VPN permission denied"
private const val SMART_PERMISSION_DENIED = "VPN permission is required for Smart Connect"
private const val VPN_PERMISSION_REQUIRED = "VPN permission required"
private val PERMISSION_ERRORS = setOf(SSH_PERMISSION_DENIED, VPN_PERMISSION_REQUIRED)
private const val PERMISSION_REQUIRED_NOTE = "shadow needs the VPN permission. Tap Connect to allow it."
private const val ENGINE_INSTALLING_NOTE = "The Xray engine is still installing. Connect when it's done."
private const val ENGINE_MISSING_MARKER = "Xray runtime core is not installed"
private const val NOT_CONNECTED = "Not connected"
private const val NOT_PROTECTED_YET = "Traffic is not protected yet"
private const val CONNECT = "Connect"
private const val STOP = "Stop"
private const val DISCONNECT = "Disconnect"
private const val ACTIVITY_PREVIEW_LINES = 8
private const val POOL_OTHER_ROWS = 5
private const val TABLET_POOL_ROWS = 6
private const val QUICK_SWITCH_PINNED = 4
private const val QUICK_SWITCH_FASTEST = 4
private const val MILLIS_PER_SECOND = 1_000L

// endregion
