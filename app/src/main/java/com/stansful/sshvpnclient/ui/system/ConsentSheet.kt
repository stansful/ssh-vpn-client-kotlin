package com.stansful.sshvpnclient.ui.system

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.ShadowCheckbox
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.theme.Shadow

/** Which mode asks for consent: Auto (once) or Routes (until turned off). */
enum class ConsentKind {
    AUTO,
    ROUTES,
}

/**
 * Public-routes consent sheet shown by the shell's ConsentGate before Auto or Routes starts.
 * [onContinue] reports the "Don't show this again" choice (Routes only; always false for Auto);
 * [onNotNow] also covers Back and a tap outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(
    kind: ConsentKind,
    onContinue: (dontShowAgain: Boolean) -> Unit,
    onNotNow: () -> Unit,
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    SystemSheet(
        footnote = when (kind) {
            ConsentKind.AUTO -> "Not now, Back or a tap outside keeps your current mode."
            ConsentKind.ROUTES -> "Not now, Back or a tap outside returns you to Home."
        },
        onContinue = { onContinue(kind == ConsentKind.ROUTES && dontShowAgain) },
        onNotNow = onNotNow,
    ) {
        ConsentSheetContent(
            kind = kind,
            dontShowAgain = dontShowAgain,
            onDontShowAgainChange = { dontShowAgain = it },
        )
    }
}

/**
 * Everything above the buttons: header, the three risk rows and (Routes) the opt-out checkbox. The
 * artboard draws one sheet for both modes; Auto asks only once, so it has no opt-out.
 */
@Composable
internal fun ConsentSheetContent(
    kind: ConsentKind,
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
) {
    Column {
        SystemSheetHeader(
            icon = ShadowIcons.ShieldAlert,
            title = "Public routes, your call",
            body = "These routes come from public lists run by third parties. They can see and change " +
                "unencrypted traffic and may stop working at any time. Your SSH servers are never shared.",
        )
        SystemSheetList(
            items = listOf(
                SystemSheetItem(
                    title = "Lists change often",
                    caption = "Auto re-tests them on every pass",
                    leading = { SystemSheetIconMarker(ShadowIcons.Refresh) },
                ),
                SystemSheetItem(
                    title = "Prefer HTTPS apps and sites",
                    caption = "Encrypted traffic stays private on any route",
                    leading = { SystemSheetIconMarker(ShadowIcons.Lock) },
                ),
                SystemSheetItem(
                    title = "Use at your own risk",
                    caption = "The developer can’t vouch for them or your data",
                    leading = { SystemSheetIconMarker(ShadowIcons.Info) },
                ),
            ),
        )
        when (kind) {
            ConsentKind.ROUTES -> {
                DontShowAgainRow(checked = dontShowAgain, onCheckedChange = onDontShowAgainChange)
                // + the actions' 10 dp = the artboard's 12 dp under the checkbox row.
                Spacer(Modifier.height(2.dp))
            }
            // + the actions' 10 dp = 18 dp, the rhythm of the permission sheet.
            ConsentKind.AUTO -> Spacer(Modifier.height(8.dp))
        }
    }
}

/** The whole row toggles the checkbox (Role.Checkbox), 58 dp tall. */
@Composable
private fun DontShowAgainRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ShadowFocusIndication,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ShadowCheckbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.offset(y = (-1).dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Don’t show this again",
                style = Shadow.type.rowTitle,
                color = Shadow.colors.ink1,
            )
            Text(
                text = "Routes only · turn it back on in Settings",
                style = Shadow.type.caption,
                color = Shadow.colors.ink3,
            )
        }
    }
}
