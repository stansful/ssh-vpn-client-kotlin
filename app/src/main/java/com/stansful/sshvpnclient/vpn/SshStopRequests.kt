package com.stansful.sshvpnclient.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the Server-mode stops SshVpnService received that were not a restart's own: Home's
 * Disconnect, the notification's, the quick tile, another mode taking the VPN over, Android revoking
 * it. A restart notes the count before it stops the session and reconnects only while it is
 * unchanged: the state cannot tell its own stop from the user's while the old session tears down.
 */
class SshStopRequests {
    private val count = AtomicLong()

    val current: Long
        get() = count.get()

    fun record() {
        count.incrementAndGet()
    }
}
