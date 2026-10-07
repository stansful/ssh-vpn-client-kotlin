package com.stansful.sshvpnclient.screenshots.routes

import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.ProxyEditorState
import com.stansful.sshvpnclient.ui.opensource.RouteCounts
import com.stansful.sshvpnclient.ui.opensource.XrayCoreUpdateUiState
import com.stansful.sshvpnclient.ui.routes.RoutesScreenState
import java.util.Base64

/** The BRIEF §9 route library, as ViewModel state. */
internal object RoutesFixtures {
    val parser = ProxyShareLinkParser()

    const val NOW = 1_760_000_000_000L
    const val SYNCED_AT = NOW - 2 * 60 * 60 * 1_000L

    const val OSLO_LINK = "vless://3f1c9a2e-8b7d-4e21-9c55-0a6f3d2b71e4@203.0.113.77:443?security=reality" +
        "&type=tcp&sni=www.example.org&fp=chrome#Oslo%20%C2%B7%20NO%2006"
    const val AMSTERDAM_LINK = "vless://9d40c1a2-7b1e-4f0a-a1b2-77c0d2e9b1aa@203.0.113.24:443?security=reality" +
        "&type=tcp#Amsterdam%20%C2%B7%20NL%2003"
    const val AMSTERDAM_COPY = "vless://9d40c1a2-7b1e-4f0a-a1b2-77c0d2e9b1aa@203.0.113.24:443?security=reality" +
        "&type=tcp#Amsterdam%20backup"
    const val OSLO_SS = "ss://YWVzLTI1Ni1nY206c2VjcmV0@203.0.113.77:8388#Oslo%20%C2%B7%20NO%2006"
    const val CLIPBOARD_FILE = "content://com.android.providers.media.documents/document/image%3A1042"
    const val TALLINN_LINK = "trojan://p3Rt8vYw1QxZ6nKd@198.51.100.119:443?security=tls&sni=tll.example.net" +
        "#Tallinn%20%C2%B7%20EE%2004"
    const val HELSINKI_HY2 = "hy2://fake-auth-7Qm2@198.51.100.42:443?sni=hy.example.net&obfs=salamander" +
        "&obfs-password=fake-obfs-3Kp9#Helsinki%20%C2%B7%20FI%2002"

    private fun fingerprint(link: String): String =
        (parser.parse(link) as ProxyParseResult.Success).profile.fingerprint

    private fun route(
        id: String,
        name: String,
        protocol: ProxyProtocol,
        host: String,
        port: Int,
        transport: ProxyTransport,
        security: ProxySecurity,
        status: ProxyTestStatus,
        latency: Long? = null,
        selected: Boolean = false,
        pinned: Boolean = false,
        stale: Boolean = false,
        source: ProxyProfileSource = ProxyProfileSource.REMOTE,
        fingerprint: String = id,
        updatedAt: Long = NOW,
    ) = ProxyProfileSummary(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        transport = transport,
        security = security,
        flow = null,
        fingerprint = fingerprint,
        source = source,
        isSelected = selected,
        isPinned = pinned,
        isStale = stale,
        lastTestStatus = status,
        lastLatencyMs = latency,
        updatedAt = updatedAt,
    )

    val frankfurt = route(
        "fra", "Frankfurt · DE 11", ProxyProtocol.VLESS, "198.51.100.7", 8443, ProxyTransport.XHTTP,
        ProxySecurity.REALITY, ProxyTestStatus.AVAILABLE, latency = 112, selected = true, pinned = true,
    )
    val amsterdam = route(
        "ams", "Amsterdam · NL 03", ProxyProtocol.VLESS, "203.0.113.24", 443, ProxyTransport.RAW,
        ProxySecurity.REALITY, ProxyTestStatus.AVAILABLE, latency = 86, fingerprint = fingerprint(AMSTERDAM_LINK),
    )
    val paris = route(
        "par", "Paris · FR 07", ProxyProtocol.TROJAN, "203.0.113.61", 443, ProxyTransport.WEBSOCKET,
        ProxySecurity.TLS, ProxyTestStatus.AVAILABLE, latency = 205,
    )
    val vilnius = route(
        "vil", "Vilnius · LT 01", ProxyProtocol.VMESS, "198.51.100.83", 2096, ProxyTransport.GRPC,
        ProxySecurity.TLS, ProxyTestStatus.UNAVAILABLE,
    )
    val riga = route(
        "rig", "Riga · LV 02", ProxyProtocol.VLESS, "203.0.113.175", 443, ProxyTransport.XHTTP,
        ProxySecurity.REALITY, ProxyTestStatus.UNSUPPORTED,
    )
    val home = route(
        "home", "My home route", ProxyProtocol.VLESS, "192.0.2.44", 443, ProxyTransport.RAW,
        ProxySecurity.REALITY, ProxyTestStatus.NOT_TESTED, pinned = true, source = ProxyProfileSource.MANUAL,
    )
    val tallinn = route(
        "tal", "Tallinn · EE 04", ProxyProtocol.TROJAN, "198.51.100.119", 443, ProxyTransport.RAW,
        ProxySecurity.TLS, ProxyTestStatus.UNAVAILABLE, stale = true, fingerprint = fingerprint(TALLINN_LINK),
    )

    val helsinki = route(
        "hel", "Helsinki · FI 02", ProxyProtocol.HYSTERIA2, "198.51.100.42", 443, ProxyTransport.HYSTERIA,
        ProxySecurity.TLS, ProxyTestStatus.AVAILABLE, latency = 64, fingerprint = fingerprint(HELSINKI_HY2),
    )

    val library = listOf(frankfurt, amsterdam, paris, helsinki, vilnius, riga, home, tallinn)
    val counts = RouteCounts(
        total = 128,
        available = 41,
        unavailable = 63,
        unsupported = 9,
        notChecked = 15,
        pinned = 2,
    )

    fun routes(
        profiles: List<ProxyProfileSummary> = library,
        all: List<ProxyProfileSummary> = library,
        counts: RouteCounts = this.counts,
        engine: Boolean = true,
        vpn: VpnConnectionState = VpnConnectionState(),
        riskExpanded: Boolean = false,
        transform: OpenSourceUiState.() -> OpenSourceUiState = { this },
    ): OpenSourceUiState = OpenSourceUiState(
        profiles = profiles,
        allProfileIds = all.mapTo(linkedSetOf()) { it.id },
        unavailableUnpinnedCount = 63,
        appSettings = AppSettings(openSourceRiskBannerExpanded = riskExpanded),
        vpnState = vpn,
        xrayCoreAvailable = engine,
        counts = counts,
        activeProfile = all.firstOrNull { it.isSelected },
        libraryLoaded = true,
        library = all,
    ).transform()

    fun screen(routes: OpenSourceUiState, clipboardHasText: Boolean = true, serverName: String? = null) =
        RoutesScreenState(
            routes = routes,
            nowMillis = NOW,
            listSyncedAt = SYNCED_AT,
            serverName = serverName,
            clipboardHasText = clipboardHasText,
        )

    val routesConnected = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeConfigId = "fra",
        activeTransport = VpnTransportType.XRAY,
        sessionOwner = VpnSessionOwner.OPEN_SOURCE,
    )

    val routesConnecting = routesConnected.copy(status = VpnConnectionStatus.CONNECTING)
    val routesReconnecting = routesConnected.copy(status = VpnConnectionStatus.RECONNECTING)

    /** A Routes start failed for the active route (no transport, the route's id, a message). */
    val routesFailed = VpnConnectionState(
        status = VpnConnectionStatus.ERROR,
        activeConfigId = "fra",
        errorMessage = "Another VPN runtime is still stopping; try again",
    )

    val autoConnected = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeConfigId = "auto-ams",
        activeTransport = VpnTransportType.XRAY,
        sessionOwner = VpnSessionOwner.SMART_CONNECT,
    )

    /** The Xray engine release the Settings check found (41.2 MiB for arm64-v8a). */
    val engineRelease = XrayCoreUpdateUiState(
        runtimeAbi = "arm64-v8a",
        release = XrayCoreRelease(
            versionName = "v26.9.30",
            title = "v26.9.30",
            releaseUrl = "https://example.org/xray/v26.9.30",
            runtimeAbi = "arm64-v8a",
            assets = listOf(
                XrayCoreAsset(
                    abi = "arm64-v8a",
                    name = "libXray.aar",
                    downloadUrl = "https://example.org/xray/libXray.aar",
                    sizeBytes = 43_201_331L,
                    sha256Digest = null,
                    universal = true,
                ),
            ),
        ),
    )

    /** Twelve more checked public routes, for a library long enough to scroll. */
    val more = listOf(
        "Oslo · NO 06" to 141L, "Vienna · AT 05" to 233L, "Zurich · CH 06" to 98L, "Lisbon · PT 08" to 312L,
        "Bergen · NO 02" to 77L, "Prague · CZ 03" to 189L, "Gdansk · PL 09" to 402L, "Kyiv · UA 01" to 655L,
        "Madrid · ES 04" to 120L, "Dublin · IE 02" to 164L, "Sofia · BG 03" to 280L, "Milan · IT 07" to 133L,
    ).mapIndexed { index, (name, latency) ->
        route(
            "more-$index", name, ProxyProtocol.VLESS, "203.0.113.${100 + index}", 443, ProxyTransport.RAW,
            ProxySecurity.REALITY, ProxyTestStatus.AVAILABLE, latency = latency,
        )
    }

    val serverConnected = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeConfigId = "home-vps",
        activeTransport = VpnTransportType.SSH,
        sessionOwner = VpnSessionOwner.SHADOW_SSH,
    )

    fun checking(done: Int, total: Int, soFar: Int): OpenSourceUiState.() -> OpenSourceUiState = {
        copy(isChecking = true, checkCompleted = done, checkTotal = total, checkAvailableSoFar = soFar)
    }

    fun editor(text: String, profileId: String? = null, error: String? = null) =
        ProxyEditorState(profileId = profileId, rawUri = text, error = error)

    private fun vmess(name: String, host: String, port: Int, net: String): String {
        val json = """{"v":"2","ps":"$name","add":"$host","port":"$port",""" +
            """"id":"e41b7c90-5d2e-4c1a-9f3e-7a8b6c5d4e3f",""" +
            """"aid":"0","net":"$net","tls":"tls"}"""
        return "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** RouteImport.dc.html's copied batch. */
    val clipboardBatch = listOf(
        "# Shared list · 3 Oct",
        "vless://5b2e7d1c-a91c-4b2e-8f3a-1c2d3e4f5a6b@203.0.113.88:443?security=reality&type=tcp&sni=www.example.com" +
            "#Bergen%20%C2%B7%20NO%2002",
        "vless://c07a9e3d-3d58-4c07-9e3d-58c07a9e3d58@198.51.100.31:443?security=reality&type=xhttp" +
            "#Copenhagen%20%C2%B7%20DK%2004",
        "trojan://k9TqxW2p@203.0.113.104:443?security=tls&type=ws#Gdansk%20%C2%B7%20PL%2009",
        vmess("Prague · CZ 03", "198.51.100.85", 443, "grpc"),
        AMSTERDAM_COPY,
        "trojan://Zr4m8LqA@198.51.100.140:443?security=tls&type=grpc#Zurich%20%C2%B7%20CH%2006",
        "ss://YWVzLTI1Ni1nY206c2VjcmV0@203.0.113.9:8388#Kyiv%20%C2%B7%20UA%2001",
        "hy2://fake-auth-Lw8r@203.0.113.66:8443?sni=hy.example.com#Tampere%20%C2%B7%20FI%2003",
        "hysteria://203.0.113.70:443?protocol=udp&auth=fake-auth-v1&upmbps=50#Turku%20%C2%B7%20FI%2004",
        "vless://71fdb2e4-71fd-4b2e-a71f-db2e471fdb2e@203.0.113.52:8443?security=reality&type=xhttp" +
            "#Reykjavik%20%C2%B7%20IS%2001",
        "vless://c07a9e3d-3d58-4c07-9e3d-58c07a9e3d58@198.51.100.31:443?security=reality&type=xhttp#Copenhagen%20copy",
        "trojan://Pp7vn3Rs@203.0.113.17:443?security=tls&type=tcp#Vienna%20%C2%B7%20AT%2005",
        vmess("Bratislava · SK 02", "198.51.100.118", 2053, "ws"),
        "vless://e3c819af-e3c8-49af-b3c8-19afe3c819af@198.51.100.66:2053?security=tls&type=ws" +
            "#Lisbon%20%C2%B7%20PT%2008",
    ).joinToString("\n")
}
