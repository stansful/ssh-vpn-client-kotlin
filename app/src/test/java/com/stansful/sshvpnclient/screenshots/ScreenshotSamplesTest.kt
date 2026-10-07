package com.stansful.sshvpnclient.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.ui.designsystem.ConnectOrb
import com.stansful.sshvpnclient.ui.designsystem.DesignSystemGallery
import com.stansful.sshvpnclient.ui.designsystem.IconGallery
import com.stansful.sshvpnclient.ui.designsystem.ListGroup
import com.stansful.sshvpnclient.ui.designsystem.ListRow
import com.stansful.sshvpnclient.ui.designsystem.OrbState
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.SectionHeader
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomNav
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomSheet
import com.stansful.sshvpnclient.ui.designsystem.ShadowChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenu
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuItem
import com.stansful.sshvpnclient.ui.designsystem.ShadowNavRail
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.designsystem.TopLevelBar
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.JetBrainsMonoFamily
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.toShadowColors
import org.junit.Test

/**
 * Reference renders of the design system and the shell chrome, and a living example of the harness.
 * Run: `gradlew :app:testDebugUnitTest --tests "*ScreenshotSamplesTest*"` → app/build/screenshots/.
 */
class ScreenshotSamplesTest : ScreenshotTest() {

    @Test
    fun designSystemGallery() {
        listOf("Night" to NightColors, "Day" to DayColors).forEach { (theme, colors) ->
            renderScreenshot("DesignSystem_$theme", ScreenSize.wrap(390), colors, SystemBarsDp.None) {
                DesignSystemGallery()
            }
        }
    }

    /** Every text style, plus the bundled fonts next to the platform fallbacks (they must differ). */
    /** Keyboard focus ring (ShadowFocusIndication) on a button, an icon button, a chip and a list row. */
    @Test
    fun focusRing() {
        (0 until FOCUS_SAMPLES).forEach { focused ->
            renderScreenshot("FocusRing_${focused}_Night", ScreenSize.wrap(390), NightColors, SystemBarsDp.None) {
                FocusRingSample(focused)
            }
        }
    }

    @Test
    fun typography() {
        renderScreenshot("Typography_Night", ScreenSize.wrap(390), NightColors, SystemBarsDp.None) {
            TypeSpecimen()
        }
    }

    @Test
    fun icons() {
        renderScreenshot("Icons_Night", ScreenSize.wrap(390), NightColors, SystemBarsDp.None) { IconGallery() }
    }

    @Test
    fun orbStates() {
        listOf("Night" to NightColors, "Day" to DayColors).forEach { (theme, colors) ->
            renderScreenshot("Orb_States_$theme", ScreenSize(ORB_CELL * 3, (ORB_CELL + 28) * 3 + 16), colors, SystemBarsDp.None) {
                OrbGrid()
            }
        }
    }

    @Test
    fun bottomNav() {
        listOf("Night" to NightColors, "Day" to DayColors).forEach { (theme, colors) ->
            renderScreenshot("BottomNav_$theme", ScreenSize(390, 160), colors, SystemBarsDp.None) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    ShadowBottomNav(items = ShellNavItems, selectedKey = ShellTab.Routes.key, onSelect = {})
                }
            }
        }
    }

    @Test
    fun navRail() {
        listOf("Night" to NightColors, "Day" to DayColors).forEach { (theme, colors) ->
            renderScreenshot("NavRail_$theme", ScreenSize(240, 800), colors, SystemBarsDp.None) {
                Row(Modifier.fillMaxSize()) {
                    ShadowNavRail(
                        items = ShellNavItems,
                        selectedKey = ShellTab.Home.key,
                        onSelect = {},
                        dotColor = Shadow.colors.stateDotColor(StatusTone.Success),
                    )
                }
            }
        }
    }

    /** The shell layout with stub destinations: phone (bottom nav) and tablet (rail + two panes). */
    @Test
    fun shellScaffold() {
        renderScreenshot("Shell_Phone_Night", ScreenSize.PHONE, NightColors) {
            TopLevelShellFrame(ShellTab.Settings) { StubTopLevelScreen("Settings") }
        }
        renderScreenshot("Shell_Phone_Day", ScreenSize.PHONE, DayColors, SystemBarsDp.Device) {
            TopLevelShellFrame(ShellTab.Servers) { StubTopLevelScreen("Servers") }
        }
        renderScreenshot("Shell_Tablet_Night", ScreenSize.TABLET, NightColors) {
            TopLevelShellFrame(ShellTab.Home, railTone = StatusTone.Success) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { StubTopLevelScreen("Home") }
                    Box(Modifier.weight(1f)) { StubTopLevelScreen("Details") }
                }
            }
        }
        renderScreenshot("Shell_Phone_Ocean", ScreenSize.PHONE, CustomThemeColors.ocean().toShadowColors()) {
            TopLevelShellFrame(ShellTab.Home) { StubTopLevelScreen("Home") }
        }
    }

    /** Sub-screen + toast: the toast is shown through LocalToaster from `interact`. */
    @Test
    fun subScreenWithToast() {
        val toaster = ToasterState()
        renderScreenshot(
            name = "SubScreen_Toast_Night",
            colors = NightColors,
            toaster = toaster,
            interact = { runOnUiThread { toaster.show("Copied to clipboard") } },
        ) {
            Column(Modifier.fillMaxSize()) {
                SubScreenBar(title = "Connection activity", onBack = {})
                StubRows()
            }
        }
    }

    /** Overlays: ModalBottomSheet (ShadowBottomSheet) and Dialog windows are captured with the screen. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun overlays() {
        renderScreenshot("Overlay_Sheet_Night", ScreenSize.PHONE, NightColors) {
            TopLevelShellFrame(ShellTab.Home) { StubTopLevelScreen("Home") }
            ShadowBottomSheet(
                onDismissRequest = {},
                title = "Switch to Routes?",
                subtitle = "Only one VPN can run at a time.",
                actions = {
                    PrimaryButton("Stop & switch", onClick = {}, modifier = Modifier.fillMaxWidth())
                },
            ) {
                Text(
                    "Server mode is connected. Stop it to start Routes.",
                    style = Shadow.type.body,
                    color = Shadow.colors.ink2,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        // With a gesture bar: only the top edge has the hairline (no side lines, none above the bar).
        renderScreenshot("Overlay_Sheet_Device_Night", ScreenSize.PHONE, NightColors, systemBars = SystemBarsDp.Device) {
            TopLevelShellFrame(ShellTab.Home) { StubTopLevelScreen("Home") }
            ShadowBottomSheet(onDismissRequest = {}, title = "Switch to Routes?", subtitle = "Only one VPN can run at a time.") {
                Text(
                    "Server mode is connected. Stop it to start Routes.",
                    style = Shadow.type.body,
                    color = Shadow.colors.ink2,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        renderScreenshot("Overlay_Dialog_Day", ScreenSize.PHONE, DayColors) {
            TopLevelShellFrame(ShellTab.Servers) { StubTopLevelScreen("Servers") }
            ShadowDialog(
                onDismissRequest = {},
                title = "Delete server?",
                message = "vps.example.net will be removed from this phone.",
                confirmLabel = "Delete server",
                onConfirm = {},
                destructive = true,
                icon = ShadowIcons.Trash,
            )
        }
    }

    /** Popups (DropdownMenu → ShadowMenu) are separate windows too and are captured. */
    @Test
    fun menu() {
        renderScreenshot("Overlay_Menu_Night", ScreenSize.PHONE, NightColors) {
            Column(Modifier.fillMaxSize()) {
                TopLevelBar(title = "Servers") {
                    Box {
                        ShadowIconButton(ShadowIcons.More, contentDescription = "More", onClick = {})
                        ShadowMenu(expanded = true, onDismissRequest = {}) {
                            ShadowMenuItem("Edit server", onClick = {}, icon = ShadowIcons.Edit)
                            ShadowMenuItem("Copy host", onClick = {}, icon = ShadowIcons.Copy)
                            ShadowMenuItem("Delete server", onClick = {}, icon = ShadowIcons.Trash, destructive = true)
                        }
                    }
                }
                StubRows()
            }
        }
    }

    /**
     * reducedMotion = false: infinite animations keep running, the capture is taken at exactly
     * `settleMillis` of virtual time (deterministic, no hang).
     */
    @Test
    fun motionFrame() {
        renderScreenshot(
            name = "Orb_Connecting_Motion_Night",
            size = ScreenSize(ORB_CELL, ORB_CELL),
            systemBars = SystemBarsDp.None,
            reducedMotion = false,
            settleMillis = 700,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ConnectOrb(state = OrbState.Connecting, label = "Stop", onClick = {})
            }
        }
    }

    /** `interact` can drive the UI before the capture (here: open a sheet by tapping a row). */
    @Test
    fun interaction() {
        renderScreenshot(
            name = "Interaction_Night",
            interact = { onNodeWithText("Appearance").performClick() },
        ) {
            ClickToOpenSheet()
        }
    }
}

@Composable
private fun TypeSpecimen() {
    val type = Shadow.type
    val styles = listOf(
        "display" to type.display, "titleL" to type.titleL, "titleM" to type.titleM, "titleS" to type.titleS,
        "body" to type.body, "bodyMedium" to type.bodyMedium, "rowTitle" to type.rowTitle, "bodyS" to type.bodyS,
        "caption" to type.caption, "label" to type.label, "segment" to type.segment, "button" to type.button,
        "OVERLINE" to type.overline, "navLabel" to type.navLabel, "wordmark" to type.wordmark,
        "mono 0O1lI 203.0.113.24:443" to type.mono, "monoMedium 86 ms" to type.monoMedium,
        "monoS vless · 443" to type.monoS, "monoInput 0O1lI" to type.monoInput,
    )
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        styles.forEach { (name, style) -> Text(name, style = style, color = Shadow.colors.ink1) }
        Spacer(Modifier.height(12.dp))
        val big = type.titleL.copy(fontSize = 34.sp, lineHeight = 40.sp)
        Text("Onest  Rag 0O1lI", style = big, color = Shadow.colors.amberText)
        Text("Roboto Rag 0O1lI", style = big.copy(fontFamily = FontFamily.SansSerif), color = Shadow.colors.ink3)
        Text("JBMono Rag 0O1lI", style = big.copy(fontFamily = JetBrainsMonoFamily), color = Shadow.colors.mintText)
        Text("Fallbk Rag 0O1lI", style = big.copy(fontFamily = FontFamily.Monospace), color = Shadow.colors.ink3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(400, 500, 600, 700).forEach { weight ->
                Text("Wg$weight", style = type.titleM.copy(fontWeight = FontWeight(weight)), color = Shadow.colors.ink1)
            }
        }
    }
}
private const val ORB_CELL = 300

@Composable
private fun OrbGrid() {
    val states = listOf(
        "Off" to OrbState.Off,
        "Connecting" to OrbState.Connecting,
        "Progress 37%" to OrbState.Progress(0.37f),
        "Waiting 60%" to OrbState.Waiting(0.6f),
        "Connected" to OrbState.Connected,
        "Reconnecting" to OrbState.Reconnecting,
        "Stopping" to OrbState.Stopping,
        "Error" to OrbState.Error,
        "Disabled" to OrbState.Disabled,
    )
    Column(Modifier.padding(top = 8.dp)) {
        states.chunked(3).forEach { row ->
            Row {
                row.forEach { (name, state) ->
                    Column(
                        modifier = Modifier.width(ORB_CELL.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(ORB_CELL.dp), contentAlignment = Alignment.Center) {
                            ConnectOrb(state = state, label = orbLabel(state), onClick = {})
                        }
                        Text(name.uppercase(), style = Shadow.type.overline, color = Shadow.colors.ink3)
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}

private fun orbLabel(state: OrbState): String = when (state) {
    OrbState.Connected, OrbState.Reconnecting -> "Disconnect"
    is OrbState.Progress, is OrbState.Waiting, OrbState.Connecting -> "Stop"
    else -> "Connect"
}

@Composable
private fun StubTopLevelScreen(title: String) {
    Column(Modifier.fillMaxSize()) {
        TopLevelBar(title = title) {
            ShadowIconButton(
                ShadowIcons.Plus,
                contentDescription = "Add",
                onClick = {},
            )
        }
        StubRows()
    }
}

@Composable
private fun StubRows() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Stub destination")
        ListGroup {
            ListRow(title = "Amsterdam · NL 03", subtitle = "vless · 203.0.113.24:443", subtitleMono = true, onClick = {})
            ListRow(title = "App routing", subtitle = "All apps", leadingIcon = ShadowIcons.Apps, showChevron = true, onClick = {})
            ListRow(title = "Appearance", value = "Night", leadingIcon = ShadowIcons.Palette, showChevron = true, onClick = {})
        }
        Text(
            "Mono 13/18 · 198.51.100.7:22 · ssh-ed25519 AAAAC3Nz",
            style = Shadow.type.mono,
            color = Shadow.colors.ink2,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClickToOpenSheet() {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopLevelBar(title = "Settings", windowInsets = WindowInsets(0))
        Box(Modifier.padding(horizontal = 20.dp)) {
            ListGroup {
                ListRow(title = "Appearance", value = "Night", showChevron = true, onClick = { open = true })
            }
        }
    }
    if (open) {
        ShadowBottomSheet(onDismissRequest = { open = false }, title = "Appearance") {
            Text("Opened by interact { performClick() }", modifier = Modifier.padding(20.dp))
        }
    }
}

private const val FOCUS_SAMPLES = 4

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FocusRingSample(focused: Int) {
    val requesters = remember { List(FOCUS_SAMPLES) { FocusRequester() } }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(focused) {
        inputMode.requestInputMode(InputMode.Keyboard)
        requesters[focused].requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PrimaryButton(text = "Connect", onClick = {}, modifier = Modifier.focusRequester(requesters[0]))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShadowIconButton(
                icon = ShadowIcons.Search,
                contentDescription = "Search",
                onClick = {},
                modifier = Modifier.focusRequester(requesters[1]),
            )
            ShadowChip(label = "Route library", onClick = {}, modifier = Modifier.focusRequester(requesters[2]))
        }
        ListGroup {
            ListRow(
                title = "App routing",
                subtitle = "Only selected apps",
                showChevron = true,
                onClick = {},
                modifier = Modifier.focusRequester(requesters[3]),
            )
        }
    }
}

