package com.stansful.sshvpnclient.screenshots.home

import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.SmartConnectState
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.ui.home.HomeMode
import com.stansful.sshvpnclient.ui.home.HomeSources
import com.stansful.sshvpnclient.ui.home.HomeUiState
import com.stansful.sshvpnclient.ui.home.buildHomeUiState
import com.stansful.sshvpnclient.ui.main.MainUiState
import com.stansful.sshvpnclient.ui.main.TunnelCheckResult
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.XrayCoreUpdateUiState
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectUiState
import com.stansful.sshvpnclient.ui.smartconnect.SmartXrayCoreUpdateUiState

/** BRIEF §9 sample data, fed through the real Home mapper. */
internal object HomeFixtures {
    val homeVps = server(
        "home",
        "Home VPS",
        "root",
        "vps.example.net",
        22,
        AuthType.PASSWORD,
        fingerprint = "SHA256:abc",
    )
    val officeBastion = server(
        "office",
        "Office bastion",
        "deploy",
        "198.51.100.10",
        2222,
        AuthType.PRIVATE_KEY,
        key = "work-ed25519",
        fingerprint = "SHA256:def",
    )
    val raspberryPi = server("pi", "Raspberry Pi", "pi", "home.example.org", 22, AuthType.PRIVATE_KEY, key = "pi-rsa")
    val servers = listOf(homeVps, officeBastion, raspberryPi)

    val pool = listOf(
        route("nl03", "Amsterdam · NL 03", ProxyProtocol.VLESS, "203.0.113.24", 443, 86),
        route("de11", "Frankfurt · DE 11", ProxyProtocol.VLESS, "198.51.100.7", 8443, 112),
        route("fi02", "Helsinki · FI 02", ProxyProtocol.TROJAN, "203.0.113.91", 443, 164),
        route("pl05", "Warsaw · PL 05", ProxyProtocol.VMESS, "198.51.100.52", 2053, 238),
        route("se01", "Stockholm · SE 01", ProxyProtocol.VLESS, "203.0.113.140", 443, 311),
        route("tr04", "Istanbul · TR 04", ProxyProtocol.TROJAN, "198.51.100.200", 443, 540),
    ) + (1..18).map { index ->
        route("x$index", "Route $index", ProxyProtocol.VLESS, "192.0.2.$index", 443, 600L + index)
    }

    val library = listOf(
        route(
            "lib-de11", "Frankfurt · DE 11", ProxyProtocol.VLESS, "198.51.100.7", 8443, 112,
            selected = true, pinned = true, transport = ProxyTransport.XHTTP, security = ProxySecurity.REALITY,
        ),
        route(
            "lib-nl03",
            "Amsterdam · NL 03",
            ProxyProtocol.VLESS,
            "203.0.113.24",
            443,
            86,
            security = ProxySecurity.REALITY,
        ),
        route(
            "lib-fr07",
            "Paris · FR 07",
            ProxyProtocol.TROJAN,
            "203.0.113.61",
            443,
            205,
            transport = ProxyTransport.WEBSOCKET,
            security = ProxySecurity.TLS,
        ),
        route(
            "lib-lt01",
            "Vilnius · LT 01",
            ProxyProtocol.VMESS,
            "203.0.113.77",
            443,
            null,
            status = ProxyTestStatus.UNAVAILABLE,
        ),
        route(
            "lib-home", "My home route", ProxyProtocol.VLESS, "192.0.2.44", 443, null,
            pinned = true, status = ProxyTestStatus.NOT_TESTED, source = ProxyProfileSource.MANUAL,
        ),
    )

    private const val ABI = "arm64-v8a"

    /** BRIEF §9: `Universal AAR · 41.2 MiB`. */
    private val engineRelease = XrayCoreRelease(
        versionName = "v26.9.30",
        title = "Xray",
        releaseUrl = "https://example.com",
        runtimeAbi = ABI,
        assets = listOf(XrayCoreAsset(ABI, "Universal AAR", "https://example.com", 43_200_000L, null)),
    )

    val diagnostics = listOf(
        "13:47:02 Starting Auto",
        "13:47:02 VPN app routing mode: selected-apps; 4 selected applications",
        "13:47:04 Auto source refreshed: added 4, updated 346, duplicates 2, invalid 0, unsupported 3",
        "13:47:14 Removed 326 unavailable routes",
        "13:47:14 Connecting to Amsterdam · NL 03",
        "13:47:15 Auto verified Amsterdam · NL 03 through YouTube",
        "13:58:40 Auto health check postponed during an active transfer; forced check within 300s",
        "14:02:09 WARNING: SSH host identity is not verified because no fingerprint is configured",
        "14:02:11 VPN connection is connected",
    )

    fun settings(
        tab: GlobalTab,
        activity: Boolean = true,
        terminal: Boolean = true,
        selectedApps: Set<String> = emptySet(),
    ) = AppSettings(
        activeGlobalTab = tab,
        showLogsOnMain = activity,
        showTerminalOnMain = terminal,
        vpnMode = if (selectedApps.isEmpty()) VpnMode.PROXY else VpnMode.SELECTED_APPS,
        selectedAppPackages = selectedApps,
        smartConnectConsentVersion = 99,
        showOpenSourceWarningOnEnter = false,
    )

    fun ssh(status: VpnConnectionStatus, error: String? = null): VpnConnectionState {
        val active = status != VpnConnectionStatus.ERROR && status != VpnConnectionStatus.DISCONNECTED
        return VpnConnectionState(
            status = status,
            activeConfigId = homeVps.id,
            errorMessage = error,
            activeTransport = VpnTransportType.SSH.takeIf { active },
            sessionOwner = VpnSessionOwner.SHADOW_SSH.takeIf { active },
            diagnostics = diagnostics,
        )
    }

    fun main(
        settings: AppSettings,
        vpn: VpnConnectionState = VpnConnectionState(diagnostics = diagnostics),
        selected: SshConfigSummary? = homeVps,
        configs: List<SshConfigSummary> = servers,
        check: TunnelCheckResult = TunnelCheckResult.IDLE,
        checkRunning: Boolean = false,
        update: AppUpdateState = AppUpdateState(),
    ) = MainUiState(
        vpnState = vpn,
        selectedConfig = selected,
        appSettings = settings,
        isTunnelCheckRunning = checkRunning,
        tunnelCheckResult = check,
        tunnelCheckLatencyMs = 84L.takeIf { check == TunnelCheckResult.SUCCESS },
        tunnelCheckTarget = "127.0.0.1:22".takeIf { check == TunnelCheckResult.SUCCESS },
        updateState = update,
        configs = configs,
        configsLoaded = true,
    )

    fun auto(
        settings: AppSettings,
        workflow: SmartConnectState = SmartConnectState(),
        vpn: VpnConnectionState = VpnConnectionState(),
        coreAvailable: Boolean = true,
        retryStartedAt: Long? = null,
    ) = SmartConnectUiState(
        rankedProfiles = pool,
        selectedProfile = pool.first(),
        workflow = workflow,
        appSettings = settings,
        vpnState = vpn,
        xrayCoreAvailable = coreAvailable,
        xrayCoreChecked = true,
        xrayCoreUpdate = SmartXrayCoreUpdateUiState(runtimeAbi = ABI, release = engineRelease),
        retryWaitStartedAtMs = retryStartedAt,
    )

    fun routes(
        settings: AppSettings,
        vpn: VpnConnectionState = VpnConnectionState(),
        coreAvailable: Boolean = true,
    ) = OpenSourceUiState(
        profiles = library,
        allProfileIds = library.map { it.id }.toSet(),
        appSettings = settings,
        vpnState = vpn,
        xrayCoreAvailable = coreAvailable,
        xrayCoreUpdateState = XrayCoreUpdateUiState(runtimeAbi = ABI, release = engineRelease),
    )

    fun connectedAuto() = SmartConnectState(
        phase = SmartConnectPhase.CONNECTED,
        desiredActive = true,
        checkTotal = 350,
        checkCompleted = 350,
        availableCount = 24,
        catalogSize = 24,
        activeProfileId = "nl03",
        activeProfileName = "Amsterdam · NL 03",
        activeProfileLatencyMs = 86,
        lastHealthLatencyMs = 92,
    )

    fun state(
        tab: GlobalTab,
        main: MainUiState? = null,
        auto: SmartConnectUiState? = null,
        routes: OpenSourceUiState? = null,
        configure: HomeSources.() -> HomeSources = { this },
        settings: AppSettings = settings(tab),
    ): HomeUiState = buildHomeUiState(
        HomeSources(
            main = main ?: main(settings),
            auto = auto ?: auto(settings),
            routes = routes ?: routes(settings),
        ).configure(),
    )

    val readyUpdate = AppUpdateState(downloadState = AppUpdateDownloadState.ReadyToInstall("3.5.0", "content://update"))

    fun HomeSources.denied(mode: HomeMode) = copy(permissionDeniedMode = mode)

    private fun server(
        id: String,
        name: String,
        user: String,
        host: String,
        port: Int,
        auth: AuthType,
        key: String? = null,
        fingerprint: String? = null,
    ) = SshConfigSummary(
        id = id,
        name = name,
        host = host,
        port = port,
        username = user,
        authType = auth,
        privateKeyId = key?.let { "key-$it" },
        keyName = key,
        fingerprint = fingerprint,
        keepAliveIntervalSec = 30,
        enableUdpForwarding = false,
        note = null,
        isSelected = id == "home",
        updatedAt = 0L,
    )

    private fun route(
        id: String,
        name: String,
        protocol: ProxyProtocol,
        host: String,
        port: Int,
        latency: Long?,
        selected: Boolean = false,
        pinned: Boolean = false,
        status: ProxyTestStatus = if (latency != null) ProxyTestStatus.AVAILABLE else ProxyTestStatus.NOT_TESTED,
        transport: ProxyTransport = ProxyTransport.RAW,
        security: ProxySecurity = ProxySecurity.REALITY,
        source: ProxyProfileSource = ProxyProfileSource.REMOTE,
    ) = ProxyProfileSummary(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        transport = transport,
        security = security,
        flow = null,
        fingerprint = "fp-$id",
        source = source,
        isSelected = selected,
        isPinned = pinned,
        isStale = false,
        lastTestStatus = status,
        lastLatencyMs = latency,
        updatedAt = 0L,
    )
}
