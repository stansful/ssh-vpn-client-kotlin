package com.stansful.sshvpnclient.screenshots.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.ShellTab
import com.stansful.sshvpnclient.screenshots.SystemBarsDp
import com.stansful.sshvpnclient.screenshots.TopLevelShellFrame
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.ConnectOrb
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.ItemCard
import com.stansful.sshvpnclient.ui.designsystem.LatencyMeter
import com.stansful.sshvpnclient.ui.designsystem.OrbState
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.StatusPill
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.TopLevelBar
import com.stansful.sshvpnclient.ui.designsystem.WordmarkTopBar
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.system.ConsentKind
import com.stansful.sshvpnclient.ui.system.ConsentSheet
import com.stansful.sshvpnclient.ui.system.VpnPermissionSheet
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.Shadow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * System.dc.html columns B and C: the public-routes consent sheet (Routes and Auto) and the VPN
 * permission pre-prompt, over stand-ins of the screens they open on.
 */
class SystemSheetsScreenshotTest : ScreenshotTest() {

    @Test
    fun consentRoutes() {
        renderScreenshot("System_ConsentRoutes_Night", ScreenSize.PHONE, NightColors) {
            RoutesBackdrop()
            ConsentSheet(kind = ConsentKind.ROUTES, onContinue = {}, onNotNow = {})
        }
        renderScreenshot(
            name = "System_ConsentRoutes_Checked_Night",
            interact = { onNodeWithText("Don’t show this again").performClick() },
        ) {
            RoutesBackdrop()
            ConsentSheet(kind = ConsentKind.ROUTES, onContinue = {}, onNotNow = {})
        }
        renderScreenshot("System_ConsentRoutes_Day", ScreenSize.PHONE, DayColors) {
            RoutesBackdrop()
            ConsentSheet(kind = ConsentKind.ROUTES, onContinue = {}, onNotNow = {})
        }
    }

    @Test
    fun consentAuto() {
        renderScreenshot("System_ConsentAuto_Night", ScreenSize.PHONE, NightColors) {
            HomeBackdrop()
            ConsentSheet(kind = ConsentKind.AUTO, onContinue = {}, onNotNow = {})
        }
    }

    @Test
    fun vpnPermission() {
        renderScreenshot("System_VpnPermission_Night", ScreenSize.PHONE, NightColors) {
            HomeBackdrop()
            VpnPermissionSheet(onContinue = {}, onNotNow = {})
        }
        renderScreenshot("System_VpnPermission_Day", ScreenSize.PHONE, DayColors) {
            HomeBackdrop()
            VpnPermissionSheet(onContinue = {}, onNotNow = {})
        }
    }

    /** A short phone (and the device insets): the content scrolls, the buttons stay. */
    @Test
    fun smallPhone() {
        renderScreenshot(
            name = "System_ConsentRoutes_Small_Night",
            size = ScreenSize(360, 640),
            systemBars = SystemBarsDp.Device,
        ) {
            RoutesBackdrop()
            ConsentSheet(kind = ConsentKind.ROUTES, onContinue = {}, onNotNow = {})
        }
    }

    /** With motion on, Continue slides the sheet out first, then reports the opt-out exactly once. */
    @Test
    fun continueReportsTheOptOutOnce() {
        val calls = mutableListOf<String>()
        renderScreenshot(
            name = "System_ConsentRoutes_AfterContinue_Night",
            reducedMotion = false,
            interact = {
                onNodeWithText("Don’t show this again").performClick()
                onNodeWithText("Continue").performClick()
                onNodeWithText("Continue").performClick()
            },
        ) {
            RoutesBackdrop()
            ClosingHost { close ->
                ConsentSheet(
                    kind = ConsentKind.ROUTES,
                    onContinue = { dontShowAgain ->
                        calls += "continue:$dontShowAgain"
                        close()
                    },
                    onNotNow = {
                        calls += "notNow"
                        close()
                    },
                )
            }
        }
        assertEquals(listOf("continue:true"), calls)
    }

    /** Auto asks once, so it has no opt-out and always reports false. */
    @Test
    fun autoHasNoOptOut() {
        val calls = mutableListOf<String>()
        renderScreenshot(
            name = "System_ConsentAuto_AfterContinue_Night",
            interact = {
                onAllNodesWithText("Don’t show this again").assertCountEquals(0)
                onNodeWithText("Continue").performClick()
            },
        ) {
            HomeBackdrop()
            ClosingHost { close ->
                ConsentSheet(
                    kind = ConsentKind.AUTO,
                    onContinue = { dontShowAgain ->
                        calls += "continue:$dontShowAgain"
                        close()
                    },
                    onNotNow = {
                        calls += "notNow"
                        close()
                    },
                )
            }
        }
        assertEquals(listOf("continue:false"), calls)
    }

    @Test
    fun notNowConnectsNothing() {
        val calls = mutableListOf<String>()
        renderScreenshot(
            name = "System_VpnPermission_AfterNotNow_Night",
            reducedMotion = false,
            interact = { onNodeWithText("Not now").performClick() },
        ) {
            HomeBackdrop()
            ClosingHost { close ->
                VpnPermissionSheet(
                    onContinue = {
                        calls += "continue"
                        close()
                    },
                    onNotNow = {
                        calls += "notNow"
                        close()
                    },
                )
            }
        }
        assertEquals(listOf("notNow"), calls)
    }

    @Test
    fun tablet() {
        renderScreenshot("System_VpnPermission_Tablet_Night", ScreenSize.TABLET, NightColors) {
            TopLevelShellFrame(ShellTab.Home) { HomeBackdropContent() }
            VpnPermissionSheet(onContinue = {}, onNotNow = {})
        }
    }
}

/** Like the shell: the sheet leaves composition as soon as it reports. */
@Composable
private fun ClosingHost(sheet: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(true) }
    if (open) sheet { open = false }
}

/** Stand-in of Home (Server, not connected) as drawn behind the permission sheet. */
@Composable
private fun HomeBackdrop() {
    TopLevelShellFrame(ShellTab.Home) { HomeBackdropContent() }
}

@Composable
private fun HomeBackdropContent() {
    Column(Modifier.fillMaxSize()) {
        WordmarkTopBar(dotColor = Shadow.colors.stateDotColor(StatusTone.Neutral), dotGlow = false) {
            ShadowIconButton(ShadowIcons.Activity, contentDescription = "Connection activity", onClick = {})
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
            ShadowSegmented(
                options = listOf(
                    SegmentOption("Auto", ShadowIcons.Auto),
                    SegmentOption("Server", ShadowIcons.Server),
                    SegmentOption("Routes", ShadowIcons.Routes),
                ),
                selectedIndex = 1,
                onSelect = {},
            )
            Text(
                text = "Tunnel through your own SSH server.",
                style = Shadow.type.bodyS,
                color = Shadow.colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(286.dp),
            contentAlignment = Alignment.Center,
        ) {
            ConnectOrb(state = OrbState.Off, label = "Connect", onClick = {})
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(label = "Not connected", tone = StatusTone.Neutral)
            Text("Connect to Home VPS", style = Shadow.type.titleM, color = Shadow.colors.ink1)
            Text("Your traffic goes through your own server", style = Shadow.type.bodyS, color = Shadow.colors.ink3)
        }
    }
}

/** Stand-in of the Routes library as drawn behind the consent sheet. */
@Composable
private fun RoutesBackdrop() {
    TopLevelShellFrame(ShellTab.Routes) {
        Column(Modifier.fillMaxSize()) {
            TopLevelBar(title = "Routes") {
                ShadowIconButton(ShadowIcons.Search, contentDescription = "Search", onClick = {})
                ShadowIconButton(ShadowIcons.Plus, contentDescription = "Add route", onClick = {})
                ShadowIconButton(ShadowIcons.More, contentDescription = "More actions", onClick = {})
            }
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InlineBanner(
                    message = "Public routes come from third parties. Use them at your own risk.",
                    tone = BannerTone.Warning,
                )
                listOf(
                    Triple("Frankfurt · DE 11", "vless · 198.51.100.7:8443", 112L),
                    Triple("Amsterdam · NL 03", "vless · 203.0.113.24:443", 86L),
                    Triple("Paris · FR 07", "trojan · 203.0.113.61:443", 205L),
                ).forEach { (name, subtitle, latency) ->
                    ItemCard(
                        title = name,
                        subtitle = subtitle,
                        icon = ShadowIcons.Routes,
                        onClick = {},
                        trailing = { LatencyMeter(latency) },
                        chevron = null,
                    )
                }
            }
        }
    }
}
