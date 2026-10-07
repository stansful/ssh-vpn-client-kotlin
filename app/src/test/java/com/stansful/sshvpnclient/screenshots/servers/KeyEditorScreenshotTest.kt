package com.stansful.sshvpnclient.screenshots.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotScope
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.keyedit.EditKeyForm
import com.stansful.sshvpnclient.ui.keyedit.EditKeyUiState
import com.stansful.sshvpnclient.ui.servers.KeyEditorScreen
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class KeyEditorScreenshotTest : ScreenshotTest() {

    private val filled = EditKeyUiState(
        form = EditKeyForm(
            name = "media-server",
            privateKey = ServersFixtures.OPENSSH_KEY,
            passphrase = "tidal-lantern-42",
        ),
    )

    private val editing = EditKeyUiState(
        form = EditKeyForm(
            id = "work",
            name = "work-ed25519",
            privateKey = ServersFixtures.OPENSSH_KEY,
            note = "Deploy access to the office network",
        ),
        isEditing = true,
        usedBy = listOf("Office bastion"),
    )

    private fun render(
        name: String,
        state: EditKeyUiState,
        colors: ShadowColors = NightColors,
        interact: (ScreenshotScope.() -> Unit)? = null,
    ) {
        renderScreenshot(name, ScreenSize.PHONE, colors, interact = interact) {
            KeyEditorScreen(state = state, onBack = {}, onSave = {}, onFormChange = {})
        }
    }

    @Test
    fun add() {
        render("KeyEditor_Add_Night", EditKeyUiState())
        render("KeyEditor_Filled_Night", filled)
        render("KeyEditor_Filled_Day", filled, DayColors)
    }

    @Test
    fun revealed() {
        render(
            name = "KeyEditor_Revealed_Night",
            state = filled,
            interact = {
                onNodeWithContentDescription("Show private key").performClick()
                onNodeWithContentDescription("Show passphrase").performClick()
            },
        )
    }

    @Test
    fun pastedFromKeyboard() {
        // A whole key typed into the empty box at once (keyboard paste) is hidden right away.
        renderScreenshot(
            name = "KeyEditor_Pasted_Night",
            interact = { onAllNodes(hasSetTextAction())[1].performTextInput(ServersFixtures.OPENSSH_KEY) },
        ) {
            var state by remember { mutableStateOf(EditKeyUiState(form = EditKeyForm(name = "media-server"))) }
            KeyEditorScreen(
                state = state,
                onBack = {},
                onSave = {},
                onFormChange = { transform -> state = state.copy(form = transform(state.form)) },
            )
        }
    }

    @Test
    fun edit() {
        render("KeyEditor_Edit_Night", editing)
        render("KeyEditor_Edit_Day", editing, DayColors)
    }

    @Test
    fun errors() {
        render(
            "KeyEditor_PubError_Night",
            EditKeyUiState(
                form = EditKeyForm(name = "", privateKey = ServersFixtures.PUBLIC_KEY),
                errors = mapOf(
                    "name" to "Key name is required",
                    "privateKey" to "Paste the private key file, not the .pub public key",
                ),
            ),
        )
        render(
            "KeyEditor_FormatError_Night",
            EditKeyUiState(
                form = EditKeyForm(
                    name = "media-server",
                    privateKey = "-----BEGIN PRIVATE KEY-----\n" +
                        "MC4CAQAwBQYDK2VwBCIEIL9mWq1Lx4sT8yE3nR0aK6vG2hJ7cU5oP1dF9iZ4bN3X\n" +
                        "-----END PRIVATE KEY-----",
                ),
                errors = mapOf("privateKey" to "Invalid private key format"),
            ),
        )
        render("KeyEditor_SaveFailed_Night", filled.copy(message = "Could not persist encrypted secret data"))
        render("KeyEditor_Saving_Night", filled.copy(isSaving = true))
    }
}
