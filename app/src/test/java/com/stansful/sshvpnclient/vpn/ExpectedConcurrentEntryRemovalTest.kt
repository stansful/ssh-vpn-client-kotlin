package com.stansful.sshvpnclient.vpn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedConcurrentEntryRemovalTest {
    @Test
    fun `late close of replaced entry cannot remove its replacement`() {
        val entries = ConcurrentHashMap<String, Any>()
        val oldEntry = Any()
        val replacement = Any()
        val closeStarted = CountDownLatch(1)
        val allowLateClose = CountDownLatch(1)
        var oldEntryRemoved = true
        entries[KEY] = oldEntry

        val lateClose = thread(name = "late-entry-close") {
            closeStarted.countDown()
            check(allowLateClose.await(5, TimeUnit.SECONDS))
            oldEntryRemoved = removeExpectedConcurrentEntry(entries, KEY, oldEntry)
        }

        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        entries[KEY] = replacement
        allowLateClose.countDown()
        lateClose.join(5_000L)

        assertFalse(lateClose.isAlive)
        assertFalse(oldEntryRemoved)
        assertSame(replacement, entries[KEY])
        assertTrue(removeExpectedConcurrentEntry(entries, KEY, replacement))
    }

    private companion object {
        const val KEY = "client:1234-remote:443"
    }
}
