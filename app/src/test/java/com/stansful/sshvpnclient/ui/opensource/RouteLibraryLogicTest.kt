package com.stansful.sshvpnclient.ui.opensource

import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import com.stansful.sshvpnclient.ui.routes.BarButton
import com.stansful.sshvpnclient.ui.routes.BatchCategory
import com.stansful.sshvpnclient.ui.routes.LinkParse
import com.stansful.sshvpnclient.ui.routes.LinkProblem
import com.stansful.sshvpnclient.ui.routes.RouteState
import com.stansful.sshvpnclient.ui.routes.analyzeBatch
import com.stansful.sshvpnclient.ui.routes.analyzeLink
import com.stansful.sshvpnclient.ui.routes.checkAllBlockReason
import com.stansful.sshvpnclient.ui.routes.connectionBar
import com.stansful.sshvpnclient.ui.routes.formatAgo
import com.stansful.sshvpnclient.ui.routes.maskLink
import com.stansful.sshvpnclient.ui.routes.namesByFingerprint
import com.stansful.sshvpnclient.ui.routes.parseLine
import com.stansful.sshvpnclient.ui.routes.removeUnavailableNote
import com.stansful.sshvpnclient.ui.routes.routeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Library logic of the redesigned Routes screen: counts, filters, connection bar, link analysis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteLibraryLogicTest {
    private val parser = ProxyShareLinkParser()

    @Test
    fun `counts treat outdated available routes as not available`() {
        val counts = routeCounts(
            listOf(
                route("a", ProxyTestStatus.AVAILABLE),
                route("b", ProxyTestStatus.AVAILABLE, stale = true),
                route("c", ProxyTestStatus.UNAVAILABLE, pinned = true),
                route("d", ProxyTestStatus.UNSUPPORTED),
                route("e", ProxyTestStatus.NOT_TESTED),
            ),
        )

        assertEquals(
            RouteCounts(total = 5, available = 1, unavailable = 1, unsupported = 1, notChecked = 1, pinned = 1),
            counts,
        )
    }

    @Test
    fun `route state prefers checking then outdated then the last result`() {
        val stale = route("a", ProxyTestStatus.AVAILABLE, stale = true, latency = 90)

        assertEquals(RouteState.Checking, stale.routeState(checkingRouteId = "a"))
        assertEquals(RouteState.Outdated, stale.routeState(checkingRouteId = null))
        assertEquals(RouteState.Available(90), stale.copy(isStale = false).routeState(null))
        assertEquals(RouteState.NotChecked, route("b", ProxyTestStatus.RUNNING).routeState(null))
    }

    @Test
    fun `import and check summaries read like the design`() {
        assertEquals(
            "0 new · 120 updated · 3 duplicates · 1 invalid",
            importSummary(ProxyImportResult(0, 120, 3, 1, 0, 124)),
        )
        assertEquals(
            "1 new · 0 updated · 1 duplicate · 0 invalid · 2 unsupported",
            importSummary(ProxyImportResult(1, 0, 1, 0, 2, 2)),
        )
        val results = listOf(
            ProxyTunnelTestResult("a", ProxyTestStatus.AVAILABLE, 80),
            ProxyTunnelTestResult("b", ProxyTestStatus.UNAVAILABLE),
            ProxyTunnelTestResult("c", ProxyTestStatus.NOT_TESTED),
        )
        assertEquals("1 available · 1 unavailable · 0 unsupported · 1 timed out", checkSummary(results))
        assertEquals("9.4 s", formatSeconds(9_412))
    }

    @Test
    fun `ago is minutes hours or days`() {
        val now = 10_000_000_000L
        assertEquals("just now", formatAgo(now, now - 30_000))
        assertEquals("12 min ago", formatAgo(now, now - 12 * 60_000))
        assertEquals("2 h ago", formatAgo(now, now - 2 * 3_600_000))
        assertEquals("3 d ago", formatAgo(now, now - 3 * 86_400_000L))
    }

    @Test
    fun `check all explains why it is off`() {
        val ready = OpenSourceUiState(xrayCoreAvailable = true, counts = RouteCounts(total = 3))

        assertNull(ready.checkAllBlockReason())
        assertEquals(
            "Install the Xray engine to run checks",
            ready.copy(xrayCoreAvailable = false).checkAllBlockReason(),
        )
        assertEquals(
            "Disconnect to run checks",
            ready.copy(vpnState = xray(VpnSessionOwner.SMART_CONNECT)).checkAllBlockReason(),
        )
        assertEquals("Add routes to check them", ready.copy(counts = RouteCounts()).checkAllBlockReason())
    }

    @Test
    fun `remove unavailable note names the blocker`() {
        val ready = OpenSourceUiState(unavailableUnpinnedCount = 4)

        assertEquals("Pinned routes are kept", ready.removeUnavailableNote())
        assertEquals("Available when the refresh ends", ready.copy(isSyncing = true).removeUnavailableNote())
        assertEquals(
            "Disconnect first",
            ready.copy(vpnState = xray(VpnSessionOwner.OPEN_SOURCE)).removeUnavailableNote(),
        )
        assertEquals("Nothing to remove", ready.copy(unavailableUnpinnedCount = 0).removeUnavailableNote())
    }

    @Test
    fun `connection bar follows the session and the active route`() {
        val active = route("fra", ProxyTestStatus.AVAILABLE, selected = true)
        val other = route("ams", ProxyTestStatus.AVAILABLE)
        val base = OpenSourceUiState(
            profiles = listOf(active, other),
            library = listOf(active, other),
            allProfileIds = setOf("fra", "ams"),
            activeProfile = active,
            xrayCoreAvailable = true,
        )

        assertEquals(BarButton.Connect, base.connectionBar(null).button)
        assertEquals(BarButton.GetEngine, base.copy(xrayCoreAvailable = false).connectionBar(null).button)
        assertEquals(BarButton.AskStopCheck, base.copy(isChecking = true).connectionBar(null).button)
        assertEquals(
            BarButton.Disabled,
            base.copy(activeProfile = active.copy(isStale = true)).connectionBar(null).button,
        )

        val connectedElsewhere = base.copy(vpnState = xray(VpnSessionOwner.OPEN_SOURCE, configId = "ams"))
        val bar = connectedElsewhere.connectionBar(null)
        assertEquals(BarButton.Reconnect, bar.button)
        assertEquals("Next: fra", bar.route)

        val server = base.copy(
            vpnState = VpnConnectionState(
                status = VpnConnectionStatus.CONNECTED,
                activeConfigId = "vps",
                activeTransport = VpnTransportType.SSH,
                sessionOwner = VpnSessionOwner.SHADOW_SSH,
            ),
        )
        assertEquals(BarButton.Switch, server.connectionBar("Home VPS").button)
        assertEquals("Server mode connected · Home VPS", server.connectionBar("Home VPS").status)
    }

    @Test
    fun `link analysis finds the problem or the duplicate`() {
        val link = "vless://id-1@203.0.113.77:443?security=reality&type=tcp#Oslo"
        val parsed = parser.parseLine(link) as LinkParse.Route
        val known = mapOf(parsed.profile.fingerprint to "Oslo old")

        assertEquals(
            "Oslo old",
            parser.analyzeLink(link.replace("#Oslo", "#Renamed"), known, editing = false).duplicateOf,
        )
        assertTrue(parser.analyzeLink(link, emptyMap(), editing = false).acceptable)
        assertEquals(LinkParse.UnsupportedScheme("ss"), parser.parseLine("ss://abc@1.2.3.4:8388"))
        assertEquals(LinkParse.Invalid(LinkProblem.NoPort), parser.parseLine("vless://id@203.0.113.77"))
        assertEquals(LinkParse.Invalid(LinkProblem.NoPassword), parser.parseLine("trojan://203.0.113.77:443"))
        assertEquals(LinkParse.Invalid(LinkProblem.NotLink), parser.parseLine("hello"))
        assertEquals(2, parser.analyzeLink("# comment\n$link\n$link", known, editing = false).linkCount)
        assertEquals(3, parser.analyzeLink("# comment\n$link\n$link", known, editing = true).linkCount)
        val quic = parser.analyzeLink("vless://id@h.example:443?type=quic", emptyMap(), editing = false)
        assertEquals(true, quic.route?.unknownTransport)
        val unnamed = parser.analyzeLink("vless://id@203.0.113.9:443", emptyMap(), editing = false)
        assertEquals(true, unnamed.route?.unnamed)
    }

    @Test
    fun `batch analysis sorts lines into new duplicate unsupported and unreadable`() {
        val first = "vless://a@203.0.113.1:443#One"
        val known = listOf(
            route("x", ProxyTestStatus.NOT_TESTED).copy(
                name = "Known",
                fingerprint = (parser.parseLine("vless://k@203.0.113.2:443") as LinkParse.Route).profile.fingerprint,
            ),
        ).namesByFingerprint()
        val batch = parser.analyzeBatch(
            listOf(
                "# list",
                first,
                "$first-again",
                "vless://k@203.0.113.2:443#Copy",
                "ss://x@1.2.3.4:1",
                "nope",
            ).joinToString("\n"),
            known,
        )

        assertEquals(1, batch.comments)
        assertEquals(1, batch.newCount)
        assertEquals(2, batch.count(BatchCategory.Duplicate))
        assertEquals("Repeated in this paste", batch.rows[1].subtitle)
        assertEquals("Same as Known", batch.rows[2].subtitle)
        assertEquals("ss:// not supported", batch.rows[3].label)
        assertEquals("Not a link", batch.rows[4].label)
    }

    @Test
    fun `masking hides credentials only`() {
        assertEquals("vless://••••••••@203.0.113.1:443#One", maskLink("vless://secret@203.0.113.1:443#One"))
        assertEquals("vmess://••••••••••••", maskLink("vmess://eyJhIjoxfQ=="))
        assertFalse(maskLink("trojan://pw@h:1").contains("pw"))
    }

    private fun xray(owner: VpnSessionOwner, configId: String? = null) = VpnConnectionState(
        status = VpnConnectionStatus.CONNECTED,
        activeConfigId = configId,
        activeTransport = VpnTransportType.XRAY,
        sessionOwner = owner,
    )

    private fun route(
        id: String,
        status: ProxyTestStatus,
        stale: Boolean = false,
        pinned: Boolean = false,
        selected: Boolean = false,
        latency: Long? = null,
    ) = ProxyProfileSummary(
        id = id,
        name = id,
        protocol = ProxyProtocol.VLESS,
        host = "203.0.113.1",
        port = 443,
        transport = ProxyTransport.RAW,
        security = ProxySecurity.REALITY,
        flow = null,
        fingerprint = id,
        source = ProxyProfileSource.REMOTE,
        isSelected = selected,
        isPinned = pinned,
        isStale = stale,
        lastTestStatus = status,
        lastLatencyMs = latency,
        updatedAt = 0,
    )
}
