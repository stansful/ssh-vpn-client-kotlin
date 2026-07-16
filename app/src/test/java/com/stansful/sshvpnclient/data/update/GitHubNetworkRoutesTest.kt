package com.stansful.sshvpnclient.data.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubNetworkRoutesTest {
    @Test
    fun `physical route precedes a different default route`() {
        assertEquals(
            listOf("wifi", null),
            physicalFirstRouteOrder(physicalRoute = "wifi", defaultRoute = "vpn"),
        )
    }

    @Test
    fun `physical route is not duplicated when it already is default`() {
        assertEquals(
            listOf("wifi"),
            physicalFirstRouteOrder(physicalRoute = "wifi", defaultRoute = "wifi"),
        )
    }

    @Test
    fun `default route is used once when no physical route is available`() {
        assertEquals(
            listOf<String?>(null),
            physicalFirstRouteOrder(physicalRoute = null, defaultRoute = "vpn"),
        )
    }

    @Test
    fun `request falls back to default after physical route failure`() {
        val visitedRoutes = mutableListOf<String?>()

        val result = withPhysicalFirstRouteFallback(
            physicalRoute = "wifi",
            defaultRoute = "vpn",
        ) { route ->
            visitedRoutes += route
            if (route == "wifi") throw IOException("wifi failed")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("wifi", null), visitedRoutes)
    }

    @Test
    fun `successful physical request does not touch default route`() {
        val visitedRoutes = mutableListOf<String?>()

        val result = withPhysicalFirstRouteFallback(
            physicalRoute = "cellular",
            defaultRoute = "vpn",
        ) { route ->
            visitedRoutes += route
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("cellular"), visitedRoutes)
    }
}
