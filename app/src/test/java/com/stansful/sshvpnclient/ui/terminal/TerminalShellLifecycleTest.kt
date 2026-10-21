package com.stansful.sshvpnclient.ui.terminal

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * [TerminalShellLifecycleEffect] against real Activity transitions. Robolectric's recreate() marks the
 * Activity as changing configurations before pausing and stopping it, as ActivityThread does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TerminalShellLifecycleTest {
    private val events = mutableListOf<String>()

    @Test
    fun `a configuration change keeps the shell for the recreated screen`() {
        val controller = launchTerminal()

        controller.recreate()
        controller.get().showTerminal()

        assertEquals(listOf("start", "stop", "start"), events)
    }

    @Test
    fun `going to the background closes the shell`() {
        val controller = launchTerminal()

        controller.pause().stop()

        assertEquals(listOf("start", "stop", "leave"), events)
    }

    @Test
    fun `leaving the screen closes the shell`() {
        val controller = launchTerminal()

        // What the NavHost does to the Terminal destination when the user navigates away. Disposed
        // directly: a recomposition would need frames that Compose's process-wide UI dispatcher
        // only delivers in the first Robolectric test of a run.
        controller.get().composeView().disposeComposition()

        assertEquals(listOf("start", "stop", "leave"), events)
    }

    @Test
    fun `finishing the activity closes the shell`() {
        val controller = launchTerminal()

        controller.get().finish()
        controller.pause().stop().destroy()

        assertEquals(listOf("start", "stop", "leave"), events)
    }

    private fun launchTerminal(): ActivityController<ComponentActivity> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().showTerminal()
        assertEquals(listOf("start"), events)
        return controller
    }

    private fun ComponentActivity.showTerminal() {
        setContent {
            TerminalShellLifecycleEffect(
                key = Unit,
                onStart = { events += "start" },
                onStop = { events += "stop" },
                onLeave = { events += "leave" },
            )
        }
        idle()
    }

    private fun ComponentActivity.composeView(): ComposeView =
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ComposeView

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
