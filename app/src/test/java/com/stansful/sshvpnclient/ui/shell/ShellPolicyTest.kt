package com.stansful.sshvpnclient.ui.shell

import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {
    @Test
    fun `auto consent is asked until the current version is accepted`() {
        assertTrue(autoConsentRequired(AppSettings(smartConnectConsentVersion = 0)))
        assertFalse(
            autoConsentRequired(AppSettings(smartConnectConsentVersion = OpenSourcePolicy.CONSENT_VERSION)),
        )
    }

    @Test
    fun `auto consent is skipped when its warning is turned off`() {
        assertFalse(
            autoConsentRequired(
                AppSettings(smartConnectConsentVersion = 0, showSmartConnectWarningOnEnter = false),
            ),
        )
    }

    @Test
    fun `routes consent is asked while the warning is on and not yet acknowledged in this process`() {
        val settings = AppSettings(
            showOpenSourceWarningOnEnter = true,
            openSourceConsentVersion = OpenSourcePolicy.CONSENT_VERSION,
        )

        assertTrue(routesConsentRequired(settings, acknowledgedThisProcess = false))
        assertFalse(routesConsentRequired(settings, acknowledgedThisProcess = true))
        assertFalse(
            routesConsentRequired(
                settings.copy(showOpenSourceWarningOnEnter = false),
                acknowledgedThisProcess = false,
            ),
        )
    }

    @Test
    fun `routes consent is asked once until accepted even with the warning turned off`() {
        val neverAccepted = AppSettings(showOpenSourceWarningOnEnter = false, openSourceConsentVersion = 0)

        assertTrue(routesConsentRequired(neverAccepted, acknowledgedThisProcess = false))
        assertTrue(routesConsentRequired(neverAccepted, acknowledgedThisProcess = true))
        assertFalse(
            routesConsentRequired(
                neverAccepted.copy(openSourceConsentVersion = OpenSourcePolicy.CONSENT_VERSION),
                acknowledgedThisProcess = false,
            ),
        )
    }

    @Test
    fun `connection activity is on when any legacy log flag is on`() {
        assertFalse(AppSettings().showConnectionActivity)
        assertTrue(AppSettings(showLogsOnMain = true).showConnectionActivity)
        assertTrue(AppSettings(showLogsOnOpenSource = true).showConnectionActivity)
        assertTrue(AppSettings(showLogsOnSmartConnect = true).showConnectionActivity)
    }

    @Test
    fun `window width classes follow the 600 and 840 dp breakpoints`() {
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.of(599.dp))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.of(600.dp))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.of(839.dp))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.of(840.dp))
    }

    @Test
    fun `top level patterns and editor placeholder`() {
        assertTrue(Destinations.isTopLevel(Destinations.HOME))
        assertTrue(Destinations.isTopLevel(Destinations.SERVERS))
        assertTrue(Destinations.isTopLevel(Destinations.ROUTES))
        assertTrue(Destinations.isTopLevel(Destinations.SETTINGS))
        assertFalse(Destinations.isTopLevel(Destinations.SERVER_EDIT))
        assertFalse(Destinations.isTopLevel(Destinations.TERMINAL))
        assertFalse(Destinations.isTopLevel(null))
        assertNull(Destinations.NEW_ID.editorIdOrNull())
        assertEquals("abc", "abc".editorIdOrNull())
    }
}
