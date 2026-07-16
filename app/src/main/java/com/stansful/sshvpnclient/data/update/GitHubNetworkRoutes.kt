package com.stansful.sshvpnclient.data.update

import java.util.concurrent.CancellationException

/**
 * Runs GitHub I/O on a validated physical network first, then on Android's default route.
 *
 * A null route is the default-route sentinel. When the selected physical network already is the
 * default network, the single bound physical attempt covers both routes without a duplicate call.
 */
internal inline fun <Route : Any, Result> withPhysicalFirstRouteFallback(
    physicalRoute: Route?,
    defaultRoute: Route?,
    request: (Route?) -> Result,
): Result {
    val routes = physicalFirstRouteOrder(physicalRoute, defaultRoute)
    var firstFailure: Exception? = null

    for ((index, route) in routes.withIndex()) {
        try {
            return request(route)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val previousFailure = firstFailure
            if (previousFailure == null) firstFailure = error
            if (index == routes.lastIndex) {
                previousFailure?.let(error::addSuppressed)
                throw error
            }
        }
    }

    error("GitHub request did not have an available route")
}

internal fun <Route : Any> physicalFirstRouteOrder(
    physicalRoute: Route?,
    defaultRoute: Route?,
): List<Route?> = when {
    physicalRoute == null -> listOf(null)
    physicalRoute == defaultRoute -> listOf(physicalRoute)
    else -> listOf(physicalRoute, null)
}
