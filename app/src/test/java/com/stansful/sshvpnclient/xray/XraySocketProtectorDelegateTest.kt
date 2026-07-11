package com.stansful.sshvpnclient.xray

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XraySocketProtectorDelegateTest {
    @Test
    fun `native lifecycle gate serializes start and stop operations`() {
        val gate = XrayNativeLifecycleGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                gate.withLock {
                    firstEntered.countDown()
                    check(releaseFirst.await(1, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStarted.countDown()
                gate.withLock { secondEntered.set(true) }
            }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
            assertFalse(secondEntered.get())

            releaseFirst.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
            assertTrue(secondEntered.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stable delegate uses latest protector without retaining old callback`() {
        val delegate = XraySocketProtectorDelegate()
        var firstCalls = 0
        var secondCalls = 0

        delegate.update { fd ->
            firstCalls += 1
            fd == 10
        }
        assertTrue(delegate.protect(10))

        delegate.update { fd ->
            secondCalls += 1
            fd == 20
        }
        assertTrue(delegate.protect(20))
        assertFalse(delegate.protect(10))
        assertEquals(1, firstCalls)
        assertEquals(2, secondCalls)
    }

    @Test
    fun `cleared delegate rejects protection until next runtime owns it`() {
        val delegate = XraySocketProtectorDelegate()
        var calls = 0
        delegate.update {
            calls += 1
            true
        }

        delegate.clear()

        assertFalse(delegate.protect(42))
        assertEquals(0, calls)
    }

    @Test
    fun `binding registers each native controller once and updates stable delegate`() {
        FakeXrayProtectorApi.reset()
        val binding = XrayBinding(FakeXrayProtectorApi::class.java)

        binding.updateSocketProtector { fd -> fd == 1 }
        binding.updateSocketProtector { fd -> fd == 2 }

        assertEquals(1, FakeXrayProtectorApi.dialerControllers.size)
        assertEquals(1, FakeXrayProtectorApi.listenerControllers.size)
        assertFalse(FakeXrayProtectorApi.dialerControllers.single().protectFd(1))
        assertTrue(FakeXrayProtectorApi.dialerControllers.single().protectFd(2))
        assertTrue(FakeXrayProtectorApi.listenerControllers.single().protectFd(2))

        binding.clearSocketProtector()

        assertFalse(FakeXrayProtectorApi.dialerControllers.single().protectFd(2))
        assertFalse(FakeXrayProtectorApi.listenerControllers.single().protectFd(2))
    }

    @Test
    fun `Android DNS uses the protected dialer controller and can be reset`() {
        FakeXrayProtectorApi.reset()
        val binding = XrayBinding(FakeXrayProtectorApi::class.java)
        binding.updateSocketProtectors(
            dialerProtector = { fd -> fd == 53 },
            listenerProtector = { false },
        )

        binding.initDns("[2001:4860:4860::8888]:53")

        assertEquals("[2001:4860:4860::8888]:53", FakeXrayProtectorApi.dnsServer)
        assertTrue(checkNotNull(FakeXrayProtectorApi.dnsController).protectFd(53))
        assertFalse(checkNotNull(FakeXrayProtectorApi.dnsController).protectFd(54))

        binding.resetDns()
        assertEquals(1, FakeXrayProtectorApi.resetDnsCalls)
    }
}

internal fun interface FakeXraySocketController {
    fun protectFd(fd: Int): Boolean
}

internal class FakeXrayProtectorApi private constructor() {
    companion object {
        val dialerControllers = mutableListOf<FakeXraySocketController>()
        val listenerControllers = mutableListOf<FakeXraySocketController>()
        var dnsController: FakeXraySocketController? = null
        var dnsServer: String? = null
        var resetDnsCalls = 0

        @JvmStatic
        fun registerDialerController(controller: FakeXraySocketController) {
            dialerControllers += controller
        }

        @JvmStatic
        fun registerListenerController(controller: FakeXraySocketController) {
            listenerControllers += controller
        }

        @JvmStatic
        fun initDns(controller: FakeXraySocketController, server: String) {
            dnsController = controller
            dnsServer = server
        }

        @JvmStatic
        fun resetDns() {
            resetDnsCalls += 1
        }

        fun reset() {
            dialerControllers.clear()
            listenerControllers.clear()
            dnsController = null
            dnsServer = null
            resetDnsCalls = 0
        }
    }
}
