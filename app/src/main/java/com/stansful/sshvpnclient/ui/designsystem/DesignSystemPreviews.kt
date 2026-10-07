package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowTheme

@Preview(name = "Design system · Night", widthDp = 390, heightDp = 2600, backgroundColor = 0xFF0B0D10)
@Composable
private fun DesignSystemNightPreview() {
    GalleryFrame(NightColors) { DesignSystemGallery() }
}

@Preview(name = "Design system · Day", widthDp = 390, heightDp = 2600, backgroundColor = 0xFFF4F2EE)
@Composable
private fun DesignSystemDayPreview() {
    GalleryFrame(DayColors) { DesignSystemGallery() }
}

@Preview(name = "Orb states · Night", widthDp = 390, heightDp = 1500, backgroundColor = 0xFF0B0D10)
@Composable
private fun OrbStatesNightPreview() {
    GalleryFrame(NightColors) { OrbGallery() }
}

@Preview(name = "Orb states · Day", widthDp = 390, heightDp = 1500, backgroundColor = 0xFFF4F2EE)
@Composable
private fun OrbStatesDayPreview() {
    GalleryFrame(DayColors) { OrbGallery() }
}

@Preview(name = "Icons · Night", widthDp = 390, heightDp = 420, backgroundColor = 0xFF0B0D10)
@Composable
private fun IconsNightPreview() {
    GalleryFrame(NightColors) { IconGallery() }
}

@Composable
private fun GalleryFrame(colors: ShadowColors, content: @Composable () -> Unit) {
    ShadowTheme(colors = colors, reducedMotion = true) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Shadow.colors.bg),
        ) {
            content()
        }
    }
}

/** Every component in one column; `internal` so the JVM screenshot tests (src/test/.../screenshots) can render it. */
@Composable
internal fun DesignSystemGallery() {
    Column(Modifier.fillMaxWidth()) {
        WordmarkTopBar(dotColor = Shadow.colors.mint, windowInsets = WindowInsets(0)) {
            ShadowIconButton(ShadowIcons.Activity, contentDescription = "Connection activity", onClick = {})
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            ModeSwitchSample()
            SectionHeader("Buttons")
            ButtonSamples()
            SectionHeader("Chips · toggles")
            ChipAndToggleSamples()
            SectionHeader("Status")
            StatusSamples()
            SectionHeader("Inputs")
            InputSamples()
            SectionHeader("Banners")
            BannerSamples()
            SectionHeader("Lists")
            ListSamples()
            SectionHeader("Empty state")
            EmptyState(
                icon = ShadowIcons.Key,
                title = "No keys yet",
                message = "Add a private key to sign in to your servers without a password.",
            ) {
                PrimaryButton(text = "Add key", onClick = {}, icon = ShadowIcons.Plus)
            }
        }
        Box(Modifier.height(24.dp))
        SubScreenBar(
            title = "Terminal",
            onBack = {},
            subtitle = "root@vps.example.net",
            status = { StatusPill(label = "Connected", tone = StatusTone.Success) },
            showDivider = true,
            windowInsets = WindowInsets(0),
        ) {
            ShadowIconButton(ShadowIcons.More, contentDescription = "More actions", onClick = {})
        }
        Box(Modifier.height(24.dp))
        BottomNavSample()
    }
}

@Composable
private fun ModeSwitchSample() {
    var mode by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(top = 10.dp)) {
        ShadowSegmented(
            options = listOf(
                SegmentOption("Auto", ShadowIcons.Auto),
                SegmentOption("Server", ShadowIcons.Server),
                SegmentOption("Routes", ShadowIcons.Routes),
            ),
            selectedIndex = mode,
            onSelect = { mode = it },
        )
        Text(
            text = "Finds and holds the fastest public route for you.",
            style = Shadow.type.bodyS,
            color = Shadow.colors.ink3,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ButtonSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Connect", onClick = {}, icon = ShadowIcons.Power, modifier = Modifier.weight(1f))
            PrimaryButton("Save server", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Cancel", onClick = {}, size = ShadowButtonSize.Small)
            DangerTonalButton("Delete key", onClick = {})
            ShadowTextButton("Release notes", onClick = {}, trailingIcon = ShadowIcons.External)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Download engine", onClick = {}, icon = ShadowIcons.Download, size = ShadowButtonSize.Compact)
            PrimaryButton("Connect", onClick = {}, size = ShadowButtonSize.Mini)
            SecondaryButton("Try again", onClick = {}, size = ShadowButtonSize.Regular)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Manage servers", onClick = {}, modifier = Modifier.weight(1f))
            ShadowIconButton(ShadowIcons.Plus, "Add server", onClick = {}, style = ShadowIconButtonStyle.Tonal)
            ShadowIconButton(
                ShadowIcons.Close,
                "Close",
                onClick = {},
                style = ShadowIconButtonStyle.Filled,
                size = 40.dp,
                iconSize = 18.dp,
            )
        }
        ShadowButton(text = "Checking…", onClick = {}, loading = true, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipAndToggleSamples() {
    var on by remember { mutableStateOf(true) }
    var checked by remember { mutableStateOf(true) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ShadowChip("Check all", onClick = {}, icon = ShadowIcons.Shield)
        ShadowFilterChip("Pinned only", selected = true, onClick = {}, icon = ShadowIcons.Pin)
        ShadowFilterChip("All", selected = true, onClick = {}, count = "128")
        ShadowFilterChip("Available", selected = false, onClick = {}, count = "41")
        ShadowChip("Checking…", onClick = {}, tone = ChipTone.Progress, loading = true)
        ShadowChip("Tunnel OK · 84 ms", onClick = {}, tone = ChipTone.Success, icon = ShadowIcons.Shield)
        ShadowChip("Check failed", onClick = {}, tone = ChipTone.Error, icon = ShadowIcons.Warning)
        ShadowSwitch(checked = on, onCheckedChange = { on = it }, contentDescription = "Show connection activity")
        ShadowSwitch(checked = false, onCheckedChange = {}, contentDescription = "Terminal")
        ShadowCheckbox(checked = checked, onCheckedChange = { checked = it })
        ShadowCheckbox(checked = true, onCheckedChange = {}, tone = CheckboxTone.Sky)
        ShadowCheckbox(checked = false, onCheckedChange = {})
        ShadowRadio(selected = true, onClick = {})
        ShadowRadio(selected = false, onClick = {})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("Not connected", StatusTone.Neutral)
            StatusPill("Testing routes", StatusTone.Progress)
            StatusPill("Connected", StatusTone.Success)
            StatusPill("Outdated", StatusTone.Error)
            StatusPill("Pinned", StatusTone.Info, icon = ShadowIcons.Pin)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LatencyMeter(86)
            LatencyMeter(238)
            LatencyMeter(540)
            LatencyMeter(null)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaTag("vless", uppercase = true, size = MetaTagSize.Compact, container = Shadow.colors.surface2)
            MetaTag("Manual", size = MetaTagSize.Compact, outlined = true)
            MetaTag("Active", tone = StatusTone.Progress, size = MetaTagSize.Compact, icon = ShadowIcons.Check)
            MetaTag("In use", tone = StatusTone.Success, size = MetaTagSize.Compact, dot = true)
            MetaTag("Password", icon = ShadowIcons.Lock)
            MetaTag("Unavailable", tone = StatusTone.Error, size = MetaTagSize.Large)
            MetaTag("Not checked", size = MetaTagSize.Large, outlined = true)
        }
        ShadowProgressBar(progress = 0.37f)
        ShadowProgressBar(progress = 1f, complete = true)
        ShadowProgressBar(progress = null)
    }
}

@Composable
private fun InputSamples() {
    var host by remember { mutableStateOf("vps.example.net") }
    var port by remember { mutableStateOf("70000") }
    var password by remember { mutableStateOf("hunter2-hunter2") }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ShadowTextField(
            value = host,
            onValueChange = { host = it },
            label = "Host",
            helper = "Domain or IP address",
            mono = true,
        )
        ShadowTextField(
            value = port,
            onValueChange = { port = it },
            label = "Port",
            error = "Use a port from 1 to 65535",
            mono = true,
        )
        SecretTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            helper = "Hidden until you tap the eye",
            copyLabel = "Password",
        )
        ShadowSearchField(query = query, onQueryChange = { query = it }, placeholder = "Search apps")
        ShadowSearchField(
            query = "",
            onQueryChange = {},
            placeholder = "Name, host, protocol or transport",
            onClose = {},
        )
    }
}

@Composable
private fun BannerSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InlineBanner("Server deleted", BannerTone.Success)
        InlineBanner("Server didn't answer", BannerTone.Error, onDismiss = {})
        InlineBanner("Changes apply right away", BannerTone.Info)
        InlineBanner(
            message = "Auto re-tests the pool and switches routes on its own when the active one stops answering.",
            tone = BannerTone.Info,
            tintedMessage = true,
        )
        InlineBanner(
            message = "Download it once — Auto and Routes share it.",
            tone = BannerTone.Error,
            title = "Xray engine isn't installed.",
            actionLabel = "Download engine",
            onAction = {},
        )
        InlineBanner("Traffic not protected", BannerTone.Warning)
    }
}

@Composable
private fun ListSamples() {
    var activity by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListGroup {
            ListRow(
                title = "App routing",
                subtitle = "Only selected apps",
                leadingIcon = ShadowIcons.Apps,
                value = "4 apps",
                showChevron = true,
                onClick = {},
            )
            SwitchRow(
                title = "Show connection activity",
                subtitle = "Every step, in plain words",
                icon = ShadowIcons.Activity,
                checked = activity,
                onCheckedChange = { activity = it },
            )
            SwitchRow(
                title = "Terminal",
                subtitle = "Adds a shell to Home in Server mode",
                icon = ShadowIcons.Server,
                badge = "Server mode",
                checked = false,
                onCheckedChange = {},
            )
        }
        ListGroup {
            CheckboxRow(
                title = "Telegram",
                subtitle = "org.telegram.messenger",
                subtitleMono = true,
                checked = true,
                onCheckedChange = {},
            )
            CheckboxRow(
                title = "Maps",
                subtitle = "com.google.android.apps.maps",
                subtitleMono = true,
                badge = "System",
                checked = false,
                onCheckedChange = {},
            )
        }
        ItemCard(
            title = "Amsterdam · NL 03",
            subtitle = "vless · 203.0.113.24:443",
            icon = ShadowIcons.Auto,
            iconTint = Shadow.colors.mintText,
            onClick = {},
            chevron = ShadowIcons.ChevronDown,
            trailing = { LatencyMeter(86) },
        )
        ShadowCard(selected = true) {
            Text("All apps", style = Shadow.type.titleS, color = Shadow.colors.ink1)
            Text(
                text = "Everything on this phone goes through the VPN.",
                style = Shadow.type.bodyS,
                color = Shadow.colors.ink3,
            )
        }
    }
}

@Composable
private fun BottomNavSample() {
    var selected by remember { mutableStateOf("home") }
    ShadowBottomNav(
        items = SampleNavItems,
        selectedKey = selected,
        onSelect = { selected = it.key },
        windowInsets = WindowInsets(0),
    )
}

@Composable
internal fun OrbGallery() {
    val states = listOf(
        OrbState.Off to "Connect",
        OrbState.Progress(0.37f) to "Stop",
        OrbState.Connected to "Disconnect",
        OrbState.Reconnecting to "Disconnect",
        OrbState.Error to "Connect",
        OrbState.Disabled to "Connect",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        states.forEach { (state, label) ->
            Box(Modifier.height(232.dp), contentAlignment = Alignment.Center) {
                ConnectOrb(state = state, label = label, onClick = {})
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconGallery() {
    FlowRow(
        modifier = Modifier.padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShadowIcons.all.forEach { (name, icon) ->
            Column(
                modifier = Modifier.width(62.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, contentDescription = name, tint = Shadow.colors.ink1, modifier = Modifier.size(24.dp))
                Text(name, style = Shadow.type.caption, color = Shadow.colors.ink3, maxLines = 1)
            }
        }
    }
}

private val SampleNavItems = listOf(
    ShadowNavItem("home", "Home", ShadowIcons.Home),
    ShadowNavItem("servers", "Servers", ShadowIcons.Server),
    ShadowNavItem("routes", "Routes", ShadowIcons.Routes),
    ShadowNavItem("settings", "Settings", ShadowIcons.Settings),
)
