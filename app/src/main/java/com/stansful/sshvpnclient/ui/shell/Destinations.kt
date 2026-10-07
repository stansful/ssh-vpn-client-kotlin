package com.stansful.sshvpnclient.ui.shell

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * String routes of the app. Top-level destinations (bottom nav / rail): [HOME], [SERVERS], [ROUTES],
 * [SETTINGS]. Everything else is a sub-screen with a back bar. Build routes with the functions
 * below; never concatenate route strings by hand.
 */
object Destinations {
    const val HOME = "home"
    const val ROUTES = "routes"
    const val TERMINAL = "terminal"
    const val ACTIVITY = "activity"
    const val APPEARANCE = "appearance"
    const val APP_ROUTING = "app-routing"

    /** Id placeholder of the "add" editors (`server-edit/new`, `key-edit/new`). */
    const val NEW_ID = "new"

    const val ARG_TAB = "tab"
    const val ARG_SECTION = "section"
    const val ARG_CONFIG_ID = "configId"
    const val ARG_KEY_ID = "keyId"

    /** Servers screen tabs (`servers?tab=`). */
    const val TAB_SERVERS = "servers"
    const val TAB_KEYS = "keys"

    /** Settings sections to scroll to (`settings?section=`). */
    const val SECTION_ENGINE = "engine"
    const val SECTION_UPDATES = "updates"

    internal const val SERVERS_BASE = "servers"
    internal const val SETTINGS_BASE = "settings"
    internal const val SERVER_EDIT_BASE = "server-edit"
    internal const val KEY_EDIT_BASE = "key-edit"

    /** Route patterns as registered in the NavHost (what `destination.route` reports). */
    const val SERVERS = "$SERVERS_BASE?$ARG_TAB={$ARG_TAB}"
    const val SETTINGS = "$SETTINGS_BASE?$ARG_SECTION={$ARG_SECTION}"
    const val SERVER_EDIT = "$SERVER_EDIT_BASE/{$ARG_CONFIG_ID}"
    const val KEY_EDIT = "$KEY_EDIT_BASE/{$ARG_KEY_ID}"

    /** Patterns of the destinations that show the bottom nav / rail. */
    val topLevel: Set<String> = setOf(HOME, SERVERS, ROUTES, SETTINGS)

    /** `servers` or `servers?tab=keys`. */
    fun servers(tab: String? = null): String =
        if (tab == null) SERVERS_BASE else "$SERVERS_BASE?$ARG_TAB=${Uri.encode(tab)}"

    /** `settings` or `settings?section=engine`. */
    fun settings(section: String? = null): String =
        if (section == null) SETTINGS_BASE else "$SETTINGS_BASE?$ARG_SECTION=${Uri.encode(section)}"

    /** Server editor; `null` opens the "add server" form. */
    fun serverEdit(configId: String? = null): String =
        "$SERVER_EDIT_BASE/${Uri.encode(configId ?: NEW_ID)}"

    /** Key editor; `null` opens the "add key" form. */
    fun keyEdit(keyId: String? = null): String = "$KEY_EDIT_BASE/${Uri.encode(keyId ?: NEW_ID)}"

    fun isTopLevel(routePattern: String?): Boolean = routePattern in topLevel

    /**
     * Routes another app may ask `MainActivity` to show (the quick tile sends [HOME]): only the
     * top-level destinations without arguments. Editors, the terminal and anything unknown are refused.
     */
    private val externallyOpenable: Set<String> = setOf(HOME, SERVERS_BASE, ROUTES, SETTINGS_BASE)

    /** [route] when an outside request may open it, `null` otherwise. */
    fun externalRouteOrNull(route: String?): String? = route?.takeIf { it in externallyOpenable }
}

/**
 * Switches to a top-level destination the way the bottom nav does: keeps Home at the root (so Back
 * from any other top-level destination returns Home), saves and restores each destination's own
 * stack. A route with arguments (`servers?tab=keys`) opens fresh so the argument applies.
 *
 * Sub-screens on top (editors, terminal, activity…) are closed first and never saved, so a later
 * switch can't bring them back. Home is reached by popping back to it, never by restoring a saved
 * stack onto it.
 */
fun NavHostController.navigateTopLevel(route: String) {
    dropSubScreens()
    val start = graph.findStartDestination()
    if (route == start.route) {
        popBackStack(start.id, inclusive = false, saveState = true)
        return
    }
    val hasArguments = '?' in route
    navigate(route) {
        popUpTo(start.id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = !hasArguments
    }
}

/** Pops (without saving) every sub-screen above the top-most top-level destination. */
private fun NavHostController.dropSubScreens() {
    while (true) {
        val current = currentBackStackEntry ?: return
        if (Destinations.isTopLevel(current.destination.route) || previousBackStackEntry == null) return
        if (!popBackStack()) return
    }
}

/** Opens a sub-screen (or any non-top-level route) on top of the current destination. */
fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

/**
 * Back from a sub-screen. Ignores taps while a transition is still running (a second tap would
 * otherwise pop the screen underneath) and never pops the last entry.
 */
fun NavHostController.navigateBack(): Boolean {
    val current = currentBackStackEntry ?: return false
    if (!current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
    if (previousBackStackEntry == null) return false
    return popBackStack()
}

/** `null` for the "new" placeholder, the decoded id otherwise. */
internal fun String?.editorIdOrNull(): String? = this?.takeUnless { it == Destinations.NEW_ID }
