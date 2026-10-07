package com.stansful.sshvpnclient.ui.home

import androidx.compose.runtime.Immutable
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.designsystem.OrbState
import com.stansful.sshvpnclient.ui.designsystem.StatusTone

/** Home's connection modes, in the order of the mode switch. */
internal enum class HomeMode(
    val tab: GlobalTab,
    val label: String,
    val hint: String,
) {
    Auto(GlobalTab.SMART_CONNECT, "Auto", "Finds and holds the fastest public route for you."),
    Server(GlobalTab.SHADOW_SSH, "Server", "Tunnel through your own SSH server."),
    Routes(GlobalTab.OPEN_SOURCE, "Routes", "Connect through a route you pick from your library."),
    ;

    companion object {
        fun of(tab: GlobalTab): HomeMode = entries.first { it.tab == tab }

        fun of(owner: VpnSessionOwner): HomeMode = when (owner) {
            VpnSessionOwner.SHADOW_SSH -> Server
            VpnSessionOwner.OPEN_SOURCE -> Routes
            VpnSessionOwner.SMART_CONNECT -> Auto
        }
    }
}

/** A run of text; [mono] parts are numbers/hosts set in JetBrains Mono 12/500 ink-2. */
@Immutable
internal data class TextPart(val text: String, val mono: Boolean = false)

internal fun plain(text: String): List<TextPart> = listOf(TextPart(text))

/** Color of the status subline. */
internal enum class SublineTone {
    /** ink-3. */
    Muted,

    /** amber-muted: "Traffic is not protected yet". */
    Warning,
}

/** Orb, pill, headline and subline of the current mode. */
@Immutable
internal data class HomeHero(
    val tone: StatusTone,
    val pillLabel: String,
    val pillClock: String? = null,
    val pillBlink: Boolean = tone == StatusTone.Progress,
    val headline: String,
    val subline: List<TextPart>,
    val sublineTone: SublineTone = SublineTone.Muted,
    val orb: OrbState,
    val orbLabel: String,
    val orbDescription: String,
    val orbEnabled: Boolean = orb != OrbState.Disabled && orb != OrbState.Stopping,
    /** TabletHome's wording where it differs from the phone's. */
    val tabletHeadline: String? = null,
    val tabletSubline: List<TextPart>? = null,
)

/** Icon of a [HomeCard]. */
internal enum class CardIcon { Auto, Server, Routes, Clock }

/** What tapping the mode card opens. */
internal enum class CardTarget { RoutePool, ChooseServer, QuickSwitch, RouteLibrary }

/** The card under the status ("Auto-picked route", "Server", "Active route"). */
@Immutable
internal data class HomeCard(
    val overline: String,
    val title: String,
    val subtitle: String,
    val subtitleMono: Boolean = true,
    val subtitleWarning: Boolean = false,
    val icon: CardIcon,
    val tone: StatusTone,
    val latencyMs: Long? = null,
    val showMeter: Boolean = false,
    val target: CardTarget,
    val contentDescription: String,
)

/** Action offered by a failure note. */
internal enum class NoteAction { EditServer, PickApps, OpenActivity }

/** Messages between the status and the card. */
@Immutable
internal sealed interface HomeNote {
    /** "VPN permission was denied. Tap Connect to ask again." */
    data object PermissionDenied : HomeNote

    /** Xray engine missing, with the download size when known. */
    data class EngineMissing(val sizeLabel: String?) : HomeNote

    /** A connection error: [message] as reported, an optional fix. */
    data class Failure(val title: String, val message: String, val action: NoteAction?) : HomeNote

    /** Amber: traffic goes direct while Auto searches. */
    data object AutoSearching : HomeNote

    /** Sky: how the last Auto pass ended. */
    data class LastPass(val tested: Int) : HomeNote

    /** Sky: apps stay on the VPN while the server reconnects. */
    data object ServerReconnecting : HomeNote

    /** Sky: a neutral notice. */
    data class Info(val message: String) : HomeNote
}

/**
 * The note carries its own fix button (Download engine, Edit server, Pick apps). HomeStates
 * "Routes · error": the phone then drops the chip row so the orb keeps its full slot.
 */
internal val HomeNote.hasButton: Boolean
    get() = when (this) {
        is HomeNote.EngineMissing -> true
        is HomeNote.Failure -> action == NoteAction.EditServer || action == NoteAction.PickApps
        else -> false
    }

/** Result shown by the Check tunnel chip. */
internal enum class CheckState { Idle, Running, Ok, Failed }

/** Quick actions under the card. */
@Immutable
internal sealed interface HomeChip {
    data class CheckTunnel(val state: CheckState, val latencyMs: Long?, val enabled: Boolean = true) : HomeChip

    data class Terminal(val enabled: Boolean = true) : HomeChip

    data class AppRouting(val label: String, val tabletLabel: String, val contentDescription: String) : HomeChip

    data object RouteLibrary : HomeChip

    data class RoutePool(val count: Int) : HomeChip

    data object Activity : HomeChip

    data class CheckRoute(val state: CheckState, val latencyMs: Long?, val enabled: Boolean) : HomeChip
}

/** Slim update banner under the header. */
@Immutable
internal data class UpdateBanner(
    val kind: Kind,
    val version: String,
    val percent: Int? = null,
) {
    enum class Kind { Available, Downloading, Ready }

    /** Identity used to remember a dismissal. */
    val key: String get() = "$kind:$version"
}

/** One row of the "Choose server" sheet and the tablet's "Your servers". */
@Immutable
internal data class ServerOption(
    val id: String,
    val name: String,
    val address: String,
    val signIn: String,
    val verified: Boolean,
    val selected: Boolean,
)

/** One row of "Quick switch" (pinned and fastest library routes). */
@Immutable
internal data class RouteOption(
    val id: String,
    val name: String,
    val subtitle: String,
    val latencyMs: Long?,
    val pinned: Boolean,
    val selected: Boolean,
    /** Left the public list: shown as Outdated, can't connect. */
    val outdated: Boolean = false,
)

/** Tag of the auto-picked row in the route pool. */
internal enum class PickTag(val label: String) {
    InUse("In use"),
    Verifying("Verifying"),
    LastPick("Last pick"),
}

/** The auto-picked route in the pool sheet. */
@Immutable
internal data class PoolPick(
    val name: String,
    val subtitle: String,
    val pickedAtMs: Long?,
    val tag: PickTag,
    val liveMs: Long?,
    val liveRunning: Boolean,
)

/** Another working route in the pool sheet. */
@Immutable
internal data class PoolRow(
    val rank: Int,
    val name: String,
    val subtitle: String,
    val latencyMs: Long?,
)

/** "How Auto works": which of the six steps is current. */
internal enum class AutoRunState(val label: String) {
    Stopped("Stopped"),
    Searching("Searching"),
    Connected("Connected"),
}

/** Content of the Auto "Route pool" sheet. */
@Immutable
internal data class PoolSheet(
    val subtitleStrong: String,
    val subtitleRest: String,
    val workingCount: Int,
    val pick: PoolPick?,
    val others: List<PoolRow>,
    val moreCount: Int,
    val currentStep: Int,
    val runState: AutoRunState,
)

/** The tablet's Auto detail card. */
@Immutable
internal data class TabletAuto(
    val caption: String,
    val testingLabel: String?,
    val testingFraction: Float?,
    val pickedAt: String,
    val live: String,
    val liveTone: StatusTone,
    val showUnprotected: Boolean,
    val poolCaption: String,
    val pool: List<PoolRow>,
    val pickedRank: Int?,
)

/** The tablet's selected-server card. */
@Immutable
internal data class TabletServer(
    val name: String,
    val address: String,
    val signIn: String,
    val fingerprintSaved: Boolean,
    val statusLabel: String?,
    val statusTone: StatusTone,
    val hint: String,
)

/** The tablet's active-route card. */
@Immutable
internal data class TabletRoute(
    val name: String,
    val subtitle: String,
    val latencyMs: Long?,
    val transport: String,
    val pinned: Boolean,
    val manual: Boolean,
    val note: String,
    val hint: String,
    val counts: String,
)

/** Everything Home renders. */
@Immutable
internal data class HomeUiState(
    val mode: HomeMode,
    val hero: HomeHero,
    val notes: List<HomeNote> = emptyList(),
    val card: HomeCard? = null,
    val firstRun: Boolean = false,
    val chips: List<HomeChip> = emptyList(),
    val checkFailedServer: String? = null,
    val showCheckCaption: Boolean = false,
    val updateBanner: UpdateBanner? = null,
    val showActivity: Boolean = false,
    val serverOptions: List<ServerOption> = emptyList(),
    val routeOptions: List<RouteOption> = emptyList(),
    val pool: PoolSheet? = null,
    val pendingSwitch: HomeMode? = null,
    val switchMessage: String = "",
    val showNoAppsDialog: Boolean = false,
    val tabletChips: List<HomeChip> = emptyList(),
    val tabletChipNote: String = "",
    val tabletAuto: TabletAuto? = null,
    val tabletServer: TabletServer? = null,
    val tabletRoute: TabletRoute? = null,
    val activityLines: List<String> = emptyList(),
    val activityLineCount: Int = 0,
    val appRoutingSummary: String = "",
    val appRoutingTag: String = "",
)
