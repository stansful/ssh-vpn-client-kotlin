package com.stansful.sshvpnclient.data.update

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayCoreDownloadGateTest {
    @Test
    fun `serializes core file operations process wide`() = runBlocking {
        val activeOperations = AtomicInteger(0)
        val maximumConcurrentOperations = AtomicInteger(0)

        List(12) {
            async(Dispatchers.Default) {
                XrayCoreDownloadGate.runExclusive {
                    val active = activeOperations.incrementAndGet()
                    maximumConcurrentOperations.updateAndGet { previous -> maxOf(previous, active) }
                    try {
                        delay(5)
                    } finally {
                        activeOperations.decrementAndGet()
                    }
                }
            }
        }.awaitAll()

        assertEquals(1, maximumConcurrentOperations.get())
        assertEquals(0, activeOperations.get())
    }
}
