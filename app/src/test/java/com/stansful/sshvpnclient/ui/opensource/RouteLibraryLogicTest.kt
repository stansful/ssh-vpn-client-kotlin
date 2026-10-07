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
import com.stansful.sshvpnclient.ui.routes.PreviewVerdict
import com.stansful.sshvpnclient.ui.routes.RouteState
import com.stansful.sshvpnclient.ui.routes.analyzeBatch
import com.stansful.sshvpnclient.ui.routes.analyzeLink
import com.stansful.sshvpnclient.ui.routes.checkAllBlockReason
import com.stansful.sshvpnclient.ui.routes.connectionBar
import com.stansful.sshvpnclient.ui.routes.formatAgo
import com.stansful.sshvpnclient.ui.routes.maskLink
import com.stansful.sshvpnclient.ui.routes.maskLinks
import com.stansful.sshvpnclient.ui.routes.namesByFingerprint
import com.stansful.sshvpnclient.ui.routes.parseLine
import com.stansful.sshvpnclient.ui.routes.previewPlaceholder
import com.stansful.sshvpnclient.ui.routes.previewVerdict
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

    @Test
    fun `hysteria 2 links are routes and hysteria v1 links are not supported`() {
        val short = parser.parseLine("hy2://secret@203.0.113.5:443?sni=hy.example.org#Helsinki") as LinkParse.Route
        assertEquals(ProxyProtocol.HYSTERIA2, short.profile.protocol)
        assertEquals(ProxyTransport.HYSTERIA, short.profile.transport)
        assertEquals(ProxySecurity.TLS, short.profile.security)
        val long = parser.parseLine("HYSTERIA2://secret@203.0.113.5") as LinkParse.Route
        assertEquals(ProxyTransport.HYSTERIA, long.profile.transport)
        assertEquals(443, long.profile.port)
        assertTrue(long.unnamed)

        assertEquals(LinkParse.UnsupportedScheme("hysteria"), parser.parseLine("hysteria://203.0.113.5:443?auth=pw"))
        assertEquals(LinkParse.Invalid(LinkProblem.NoPort), parser.parseLine("hy2://secret@203.0.113.5:99999"))
        assertEquals(LinkParse.Invalid(LinkProblem.NoHost), parser.parseLine("hy2://secret@"))
        assertEquals(
            LinkParse.Invalid(LinkProblem.UnsupportedObfs),
            parser.parseLine("hy2://secret@203.0.113.5:443?obfs=xplus"),
        )
        assertEquals(
            LinkParse.Invalid(LinkProblem.NoObfsPassword),
            parser.parseLine("hy2://secret@203.0.113.5:443?obfs=salamander"),
        )
    }

    @Test
    fun `a hysteria 2 link that skips certificate checks without a pin is flagged but still acceptable`() {
        fun analyze(link: String) = parser.analyzeLink(link, emptyMap(), editing = false)
        val insecure = analyze("hy2://secret@203.0.113.5:443?insecure=1#Self-signed")
        val pinned = analyze("hy2://secret@203.0.113.5:443?insecure=1&pinSHA256=${"ab".repeat(32)}")

        assertTrue(insecure.acceptable)
        assertEquals(true, insecure.route?.certificateCheckWarning)
        assertEquals(false, pinned.route?.certificateCheckWarning)
        assertEquals(false, analyze("hy2://secret@203.0.113.5:443").route?.certificateCheckWarning)
        assertEquals(
            false,
            analyze("vless://id@203.0.113.5:443?security=tls&allowInsecure=1").route?.certificateCheckWarning,
        )
    }

    @Test
    fun `masking hides hysteria auth and secret query values`() {
        assertEquals(
            "hy2://••••••••@203.0.113.5:443?obfs=salamander&obfs-password=••••••••#Me@home",
            maskLink("hy2://p@ss@203.0.113.5:443?obfs=salamander&obfs-password=hunter2#Me@home"),
        )
        assertEquals(
            "hysteria2://••••••••@[2001:db8::1]:443,20000-50000/?sni=hy.example.org",
            maskLink("hysteria2://user:pass@[2001:db8::1]:443,20000-50000/?sni=hy.example.org"),
        )
        assertEquals(
            "hysteria://203.0.113.5:443?auth=••••••••&upmbps=10",
            maskLink("hysteria://203.0.113.5:443?auth=topsecret&upmbps=10"),
        )
        val masked = maskLink("HY2://203.0.113.5:443/?OBFS-PASSWORD=hunter2&Auth=letmein")
        assertFalse(masked.contains("hunter2") || masked.contains("letmein"))
    }

    @Test
    fun `batch analysis folds hy2 and hysteria2 copies and sorts out links the engine can't run`() {
        val batch = parser.analyzeBatch(
            listOf(
                "hy2://secret@203.0.113.5:443?sni=hy.example.org#Helsinki",
                "hysteria2://secret@203.0.113.5:443/?peer=hy.example.org#Helsinki%20copy",
                "hysteria://203.0.113.6:443?auth=topsecret#Old",
                "hy2://secret@203.0.113.7:443?obfs=xplus#Xplus",
            ).joinToString("\n"),
            emptyMap(),
        )

        assertEquals(1, batch.newCount)
        assertEquals("hysteria2 · 203.0.113.5:443", batch.rows[0].subtitle)
        assertEquals(BatchCategory.Duplicate, batch.rows[1].category)
        assertEquals("Repeated in this paste", batch.rows[1].subtitle)
        assertEquals(BatchCategory.Unsupported, batch.rows[2].category)
        assertEquals("Old", batch.rows[2].name)
        assertEquals("hysteria:// not supported", batch.rows[2].label)
        assertFalse(batch.rows[2].subtitle.contains("topsecret"))
        assertEquals(BatchCategory.Unsupported, batch.rows[3].category)
        assertEquals("Obfuscation not supported", batch.rows[3].label)
    }

    @Test
    fun `search finds hysteria 2 routes by its scheme and the hy2 alias`() {
        val hysteria = route("h", ProxyTestStatus.NOT_TESTED).copy(
            protocol = ProxyProtocol.HYSTERIA2,
            transport = ProxyTransport.HYSTERIA,
            security = ProxySecurity.TLS,
        )

        assertTrue(hysteria.matchesNormalized("hy2"))
        assertTrue(hysteria.matchesNormalized("Hysteria2"))
        assertTrue(hysteria.matchesNormalized("hysteria"))
        assertFalse(route("v", ProxyTestStatus.NOT_TESTED).matchesNormalized("hy2"))
    }

    @Test
    fun `saving a link the engine can't run says so`() {
        val unsupported = ProxyImportResult(
            added = 0,
            updated = 0,
            duplicates = 0,
            invalid = 0,
            unsupported = 1,
            total = 1,
            unsupportedSkipped = 1,
        )
        val unreadable = unsupported.copy(invalid = 1, unsupported = 0, unsupportedSkipped = 0)

        assertEquals(
            "This link type isn’t supported.",
            editorSaveError(unsupported, editing = false, routeExists = false),
        )
        assertEquals(
            "This link type isn’t supported.",
            editorSaveError(unsupported.copy(invalid = 1), editing = true, routeExists = true),
        )
        // A route with an unknown transport is saved, though it can't connect.
        assertNull(
            editorSaveError(unsupported.copy(added = 1, unsupportedSkipped = 0), editing = false, routeExists = false),
        )
        assertEquals(
            "This link can’t be read. Check it and try again.",
            editorSaveError(unreadable, editing = false, routeExists = false),
        )
        assertEquals(
            "This route was deleted, so the change can’t be saved.",
            editorSaveError(unreadable, editing = true, routeExists = false),
        )
    }

    @Test
    fun `the import notice calls only links left out not supported`() {
        // One saved line whose transport the engine doesn't know: imported, nothing skipped.
        assertEquals(
            emptyList<String>(),
            importSkippedNotes(ProxyImportResult(1, 0, 0, 0, unsupported = 1, total = 1)),
        )
        // The same line again is a duplicate, not also "not supported".
        assertEquals(
            listOf("1 duplicate skipped"),
            importSkippedNotes(ProxyImportResult(0, 0, 1, 0, unsupported = 1, total = 1)),
        )
        assertEquals(
            listOf("2 duplicates skipped", "1 couldn't be read", "1 not supported"),
            importSkippedNotes(ProxyImportResult(0, 0, 2, 1, unsupported = 1, total = 4, unsupportedSkipped = 1)),
        )
    }

    @Test
    fun `batch rows flag a hysteria 2 link that skips certificate checks without a pin`() {
        val batch = parser.analyzeBatch(
            listOf(
                "hy2://secret@203.0.113.5:443?insecure=1#Self-signed",
                "hy2://secret@203.0.113.6:443?insecure=1&pinSHA256=${"ab".repeat(32)}#Pinned",
                "hy2://secret@203.0.113.5:443/?insecure=1#Copy",
            ).joinToString("\n"),
            emptyMap(),
        )

        assertEquals(2, batch.newCount)
        assertEquals(BatchCategory.New, batch.rows[0].category)
        assertTrue(batch.rows[0].certificateCheckWarning)
        assertEquals("hysteria2 · 203.0.113.5:443 · needs pinSHA256", batch.rows[0].subtitle)
        assertFalse(batch.rows[1].certificateCheckWarning)
        assertEquals("hysteria2 · 203.0.113.6:443", batch.rows[1].subtitle)
        assertEquals(BatchCategory.Duplicate, batch.rows[2].category)
        assertFalse(batch.rows[2].certificateCheckWarning)
    }

    @Test
    fun `the preview checks the certificate warning after duplicates and unknown transports`() {
        fun verdict(link: String, duplicateOf: String? = null) =
            (parser.parseLine(link) as LinkParse.Route).previewVerdict(duplicateOf)
        val insecure = "hy2://secret@203.0.113.5:443?insecure=1#Self-signed"

        assertEquals(PreviewVerdict.CheckCertificate, verdict(insecure))
        assertEquals("Check certificate", PreviewVerdict.CheckCertificate.text)
        assertEquals(PreviewVerdict.AlreadyAdded, verdict(insecure, duplicateOf = "Helsinki"))
        assertEquals(PreviewVerdict.LooksGood, verdict("hy2://secret@203.0.113.5:443#Helsinki"))
        assertEquals(
            PreviewVerdict.LooksGood,
            verdict("hy2://secret@203.0.113.5:443?insecure=1&pinSHA256=${"ab".repeat(32)}"),
        )
        assertEquals(PreviewVerdict.CantConnect, verdict("vless://id@h.example:443?type=quic"))
    }

    @Test
    fun `masking hides hysteria auth with spaces slashes and other characters`() {
        assertEquals("hy2://••••••••@203.0.113.5:443", maskLink("hy2://user:pa/ss@203.0.113.5:443"))
        assertEquals("hy2://••••••••@203.0.113.5:443#Home", maskLink("hy2://my secret@203.0.113.5:443#Home"))
        assertEquals("hy2://••••••••@203.0.113.5/?sni=a", maskLink("hy2://aB3/x+Yz==@203.0.113.5/?sni=a"))
        assertEquals(
            "hysteria2://••••••••@203.0.113.5:443?obfs-password=••••••••",
            maskLink("hysteria2://pa?ss@203.0.113.5:443?obfs-password=hunter2"),
        )
        // Over-masking is fine: an `@` in a plain query value moves the end of the auth mask.
        assertFalse(maskLink("hy2://secret@203.0.113.5:443?sni=a@b").contains("secret"))
    }

    @Test
    fun `masking hides secret query values whole whatever the key spelling`() {
        assertEquals(
            "hy2://••••••••@203.0.113.5:443?obfs=salamander&obfs%2Dpassword=••••••••&sni=hy.example.org",
            maskLink("hy2://pw@203.0.113.5:443?obfs=salamander&obfs%2Dpassword=hunter2&sni=hy.example.org"),
        )
        assertEquals(
            "hy2://••••••••@203.0.113.5:443?obfs-password=••••••••#Home",
            maskLink("hy2://pw@203.0.113.5:443?obfs-password=correct horse battery#Home"),
        )
        // An `@` inside a secret value neither ends the auth mask nor shows the rest of the value.
        assertEquals(
            "hy2://••••••••@203.0.113.5:443?auth=••••••••&sni=a",
            maskLink("hy2://pw@203.0.113.5:443?auth=let@me in&sni=a"),
        )
        assertEquals(
            "hy2://203.0.113.5:443?obfs-password=••••••••&%20Auth+=••••••••",
            maskLink("hy2://203.0.113.5:443?obfs-password=a?b/c&%20Auth+=x y"),
        )
        assertEquals(
            "vless://••••••••@203.0.113.1:443?security=tls&auth=••••••••#One",
            maskLink("vless://secret@203.0.113.1:443?security=tls&auth=pw#One"),
        )
        // Each line on its own: a secret value can't swallow the next link, which keeps its own mask.
        assertEquals(
            "hy2://••••••••@203.0.113.5:443?auth=••••••••\nvless://••••••••@203.0.113.1:443",
            maskLinks("hy2://pw@203.0.113.5:443?auth=a b\nvless://secret@203.0.113.1:443"),
        )
    }

    @Test
    fun `the preview placeholder explains a link the engine can't run`() {
        fun placeholder(text: String) = parser.analyzeLink(text, emptyMap(), editing = false).previewPlaceholder()

        assertEquals(
            "No preview for links the engine can’t run.",
            placeholder("hy2://secret@203.0.113.7:443?obfs=xplus"),
        )
        assertEquals("No preview for hysteria:// links.", placeholder("hysteria://203.0.113.6:443?auth=pw"))
        assertEquals(
            "Nothing to preview until the link can be read.",
            placeholder("hy2://secret@203.0.113.7:443?obfs=salamander"),
        )
        assertEquals("A preview appears as soon as the link can be read.", placeholder(""))
    }

    @Test
    fun `hysteria 2 links with a short obfs password a bad pin or a bad ech name the problem`() {
        val short = "hy2://secret@203.0.113.5:443?obfs=salamander&obfs-password=abc"
        val badPin = "hy2://secret@203.0.113.5:443?pinSHA256=not-hex"
        val badEch = "hy2://secret@203.0.113.5:443?ech=not*base64"

        assertEquals(LinkParse.Invalid(LinkProblem.ShortObfsPassword), parser.parseLine(short))
        assertEquals(LinkParse.Invalid(LinkProblem.BadPin), parser.parseLine(badPin))
        assertEquals(LinkParse.Invalid(LinkProblem.BadEch), parser.parseLine(badEch))
        val batch = parser.analyzeBatch(listOf(short, badPin, badEch).joinToString("\n"), emptyMap())
        assertEquals(3, batch.count(BatchCategory.Invalid))
        assertEquals(listOf("Short obfs-password", "Bad pinSHA256", "Bad ech"), batch.rows.map { it.label })
    }

    @Test
    fun `an unnamed hysteria 2 link the engine can't run is named by its scheme`() {
        val batch = parser.analyzeBatch(
            listOf(
                "hy2://secret@203.0.113.7:443?obfs=xplus",
                "HYSTERIA2://secret@203.0.113.8:443?obfs=xplus",
                "hy2://secret@203.0.113.9:443?obfs=salamander",
            ).joinToString("\n"),
            emptyMap(),
        )

        assertEquals(BatchCategory.Unsupported, batch.rows[0].category)
        assertEquals("hy2:// link", batch.rows[0].name)
        assertEquals("hysteria2:// link", batch.rows[1].name)
        assertEquals(BatchCategory.Invalid, batch.rows[2].category)
        assertEquals("Unreadable line", batch.rows[2].name)
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
