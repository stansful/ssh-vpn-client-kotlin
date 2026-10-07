package com.stansful.sshvpnclient.ui.shell

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [navigateTopLevel] against a real NavHost with the shell's route patterns: a jump to Home never
 * brings back the sub-screen the user left, and later Home taps never reopen old sub-screens.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TopLevelNavigationTest {
    @Test
    fun `back to home from a sub-screen pushed on home shows home`() = withNav { nav ->
        nav.go { navigateTo(Destinations.TERMINAL) }
        nav.go { navigateTopLevel(Destinations.HOME) }
        assertEquals(listOf(Destinations.HOME), nav.stack())
    }

    @Test
    fun `a top-level jump from a sub-screen does not leave it behind for home`() = withNav { nav ->
        nav.go { navigateTo(Destinations.ACTIVITY) }
        nav.go { navigateTopLevel(Destinations.servers()) }
        assertEquals(listOf(Destinations.HOME, Destinations.SERVERS), nav.stack())
        nav.go { navigateTopLevel(Destinations.HOME) }
        assertEquals(listOf(Destinations.HOME), nav.stack())
    }

    @Test
    fun `home after a tab trip and a sub-screen shows home`() = withNav { nav ->
        nav.go { navigateTopLevel(Destinations.settings()) }
        nav.go { navigateTopLevel(Destinations.HOME) }
        nav.go { navigateTo(Destinations.ACTIVITY) }
        nav.go { navigateTopLevel(Destinations.HOME) }
        assertEquals(listOf(Destinations.HOME), nav.stack())
    }

    @Test
    fun `a sub-screen opened from settings is not reopened by the home tab`() = withNav { nav ->
        nav.go { navigateTo(Destinations.TERMINAL) }
        nav.go { navigateTopLevel(Destinations.settings()) }
        nav.go { navigateTopLevel(Destinations.HOME) }
        assertEquals(listOf(Destinations.HOME), nav.stack())
        nav.go { navigateTopLevel(Destinations.settings()) }
        assertEquals(listOf(Destinations.HOME, Destinations.SETTINGS), nav.stack())
    }

    @Test
    fun `an editor over home is closed by home`() = withNav { nav ->
        nav.go { navigateTo(Destinations.serverEdit("x")) }
        nav.go { navigateTopLevel(Destinations.HOME) }
        assertEquals(listOf(Destinations.HOME), nav.stack())
    }

    @Test
    fun `tabs keep their own stack and arguments open fresh`() = withNav { nav ->
        nav.go { navigateTopLevel(Destinations.servers()) }
        nav.go { navigateTo(Destinations.serverEdit("x")) }
        nav.go { navigateTopLevel(Destinations.ROUTES) }
        assertEquals(listOf(Destinations.HOME, Destinations.ROUTES), nav.stack())
        nav.go { navigateTopLevel(Destinations.servers()) }
        assertEquals(listOf(Destinations.HOME, Destinations.SERVERS), nav.stack())
        nav.go { navigateTopLevel(Destinations.servers(Destinations.TAB_KEYS)) }
        assertEquals(listOf(Destinations.HOME, Destinations.SERVERS), nav.stack())
    }

    @Test
    fun `outside requests may open only top-level routes`() {
        assertEquals(Destinations.HOME, Destinations.externalRouteOrNull(Destinations.HOME))
        assertEquals("servers", Destinations.externalRouteOrNull(Destinations.servers()))
        assertEquals(Destinations.ROUTES, Destinations.externalRouteOrNull(Destinations.ROUTES))
        assertEquals("settings", Destinations.externalRouteOrNull(Destinations.settings()))
        assertNull(Destinations.externalRouteOrNull(Destinations.TERMINAL))
        assertNull(Destinations.externalRouteOrNull(Destinations.serverEdit("x")))
        assertNull(Destinations.externalRouteOrNull(Destinations.keyEdit()))
        assertNull(Destinations.externalRouteOrNull("x"))
        assertNull(Destinations.externalRouteOrNull(""))
        assertNull(Destinations.externalRouteOrNull(null))
    }

    /** Runs navigation on the UI thread and lets the NavHost settle. */
    private class Nav(
        private val test: AndroidComposeUiTest<ComponentActivity>,
        private val controller: NavHostController,
    ) {
        fun go(action: NavHostController.() -> Unit) {
            test.runOnUiThread { controller.action() }
            test.waitForIdle()
        }

        /** Routes of the back stack, without the graph entry. */
        fun stack(): List<String?> = controller.currentBackStack.value
            .filter { entry -> entry.destination.parent != null }
            .map { entry -> entry.destination.route }
    }

    private fun withNav(block: (Nav) -> Unit) {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        runAndroidComposeUiTest(ComponentActivity::class.java) {
            lateinit var nav: NavHostController
            setContent {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = Destinations.HOME) {
                    composable(Destinations.HOME) {}
                    composable(Destinations.SERVERS, arguments = listOf(optional(Destinations.ARG_TAB))) {}
                    composable(Destinations.ROUTES) {}
                    composable(Destinations.SETTINGS, arguments = listOf(optional(Destinations.ARG_SECTION))) {}
                    composable(
                        Destinations.SERVER_EDIT,
                        arguments = listOf(navArgument(Destinations.ARG_CONFIG_ID) { type = NavType.StringType }),
                    ) {}
                    composable(Destinations.TERMINAL) {}
                    composable(Destinations.ACTIVITY) {}
                }
            }
            waitForIdle()
            block(Nav(this, nav))
        }
    }

    private fun optional(name: String) = navArgument(name) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
}
