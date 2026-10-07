package com.stansful.sshvpnclient.ui.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons

/**
 * Pre-prompt shown before Android's VPN connection request. [onContinue] opens the system dialog;
 * [onNotNow] (also Back / tap outside) keeps the user where they are and connects nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnPermissionSheet(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
) {
    SystemSheet(
        footnote = "Not now keeps you on Home. Nothing connects.",
        onContinue = onContinue,
        onNotNow = onNotNow,
    ) {
        VpnPermissionSheetContent()
    }
}

/** Everything above the buttons: header, the two numbered steps and the "asks again" note. */
@Composable
internal fun VpnPermissionSheetContent() {
    Column {
        SystemSheetHeader(
            icon = ShadowIcons.Lock,
            title = "Allow shadow to create a VPN",
            body = "Android will ask you to confirm. shadow routes traffic only while you’re connected.",
        )
        SystemSheetList(
            items = listOf(
                SystemSheetItem(
                    title = "Tap Continue",
                    caption = "Android shows its connection request",
                    leading = { SystemSheetStepMarker(number = 1, current = true) },
                ),
                SystemSheetItem(
                    title = "Confirm it",
                    caption = "shadow connects right after",
                    leading = { SystemSheetStepMarker(number = 2, current = false) },
                ),
            ),
        )
        SystemSheetNote(
            icon = ShadowIcons.Info,
            text = "Android asks again only if another VPN app takes over.",
        )
        // + the actions' 10 dp = the artboard's 18 dp above "Continue".
        Spacer(Modifier.height(8.dp))
    }
}
