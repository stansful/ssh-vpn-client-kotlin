package com.stansful.sshvpnclient.screenshots.servers

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotScope
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.configedit.EditConfigForm
import com.stansful.sshvpnclient.ui.configedit.EditConfigUiState
import com.stansful.sshvpnclient.ui.servers.ServerEditorScreen
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class ServerEditorScreenshotTest : ScreenshotTest() {

    /** The artboard's default: Save pressed with port 70000. */
    private val portError = EditConfigUiState(
        form = EditConfigForm(
            name = "Home VPS",
            host = "vps.example.net",
            port = "70000",
            username = "root",
            password = "night-owl 2049",
        ),
        keys = ServersFixtures.keySummaries,
        keyTraits = ServersFixtures.keyTraits,
        errors = mapOf("port" to "Port must be between 1 and 65535"),
        attempted = true,
    )

    private val keyAuth = EditConfigUiState(
        form = EditConfigForm(
            id = "office",
            name = "Office bastion",
            host = "198.51.100.10",
            port = "2222",
            username = "deploy",
            authType = AuthType.PRIVATE_KEY,
            privateKeyId = "work",
            fingerprint = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g",
            keepAliveIntervalSec = "60",
            note = "Jump host for the office network",
        ),
        keys = ServersFixtures.keySummaries,
        keyTraits = ServersFixtures.keyTraits,
        isEditing = true,
    )

    private fun render(
        name: String,
        state: EditConfigUiState,
        colors: ShadowColors = NightColors,
        size: ScreenSize = TALL,
        interact: (ScreenshotScope.() -> Unit)? = null,
    ) {
        renderScreenshot(name, size, colors, interact = interact) {
            ServerEditorScreen(
                state = state,
                onBack = {},
                onSave = {},
                onFormChange = {},
                onAuthTypeChange = {},
                onKeySelect = {},
                onAddKey = {},
                onOpenActivity = {},
            )
        }
    }

    @Test
    fun newServer() {
        render("ServerEditor_New_Night", EditConfigUiState(keys = ServersFixtures.keySummaries))
        render("ServerEditor_New_Phone_Night", EditConfigUiState(), size = ScreenSize.PHONE)
    }

    @Test
    fun validation() {
        render("ServerEditor_PortError_Night", portError)
        render("ServerEditor_PortError_Day", portError, DayColors)
        render(
            "ServerEditor_ManyErrors_Night",
            EditConfigUiState(
                form = EditConfigForm(host = "root@vps.example.net", port = "", fingerprint = "abc"),
                errors = mapOf(
                    "name" to "Name is required",
                    "port" to "Port must be between 1 and 65535",
                    "username" to "Username is required",
                    "password" to "Password is required",
                ),
                attempted = true,
            ),
        )
    }

    @Test
    fun keySignIn() {
        render("ServerEditor_Key_Night", keyAuth)
        render(
            name = "ServerEditor_KeyPickerOpen_Night",
            state = keyAuth,
            interact = { onNodeWithContentDescription("Key: work-ed25519, OpenSSH").performClick() },
        )
        render(
            "ServerEditor_NoKeys_Night",
            EditConfigUiState(
                form = EditConfigForm(
                    name = "Raspberry Pi",
                    host = "home.example.org",
                    username = "pi",
                    authType = AuthType.PRIVATE_KEY,
                ),
                errors = mapOf("privateKeyId" to "Private key is required"),
                attempted = true,
            ),
        )
    }

    @Test
    fun saveStates() {
        val valid = portError.copy(form = portError.form.copy(port = "22"), errors = emptyMap())
        render("ServerEditor_Saving_Night", valid.copy(isSaving = true), size = ScreenSize.PHONE)
        render("ServerEditor_Saved_Night", valid.copy(isSaved = true), size = ScreenSize.PHONE)
        render(
            "ServerEditor_SaveFailed_Night",
            valid.copy(message = "Could not persist encrypted secret data"),
            size = ScreenSize.PHONE,
        )
    }

    private companion object {
        /** The artboard's height: the whole form without scrolling. */
        val TALL = ScreenSize(390, 1500)
    }
}
