package com.stansful.sshvpnclient.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A notice outlives the log clear that starts the next connection attempt, and only that one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class InMemoryVpnConnectionRepositoryNoticeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = InMemoryVpnConnectionRepository(
        context = ApplicationProvider.getApplicationContext(),
        applicationScope = scope,
        ioDispatcher = Dispatchers.Unconfined,
        cpuDispatcher = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a notice written just before a connect stays in that attempt's log`() {
        repository.appendDiagnostic("Earlier session ended")
        // The connect's own config read is what finds the secret store unreadable.
        repository.appendNotice(NOTICE)

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(listOf(NOTICE), diagnostics())
    }

    @Test
    fun `the notice is carried into one attempt only`() {
        repository.appendNotice(NOTICE)
        repository.setConnecting(configId = CONFIG_ID)
        repository.appendDiagnostic("Authentication failed")
        repository.setError(CONFIG_ID, "Authentication failed")
        assertEquals(listOf(NOTICE, "Authentication failed"), diagnostics())

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    @Test
    fun `a connect that keeps the log leaves the notice for the next one that clears it`() {
        repository.appendNotice(NOTICE)
        repository.setConnecting(configId = CONFIG_ID, clearDiagnostics = false)

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(listOf(NOTICE), diagnostics())
    }

    @Test
    fun `clearing the log by hand drops the notice`() {
        repository.appendNotice(NOTICE)
        repository.clearDiagnostics()

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    @Test
    fun `ordinary lines are cleared by a connect as before`() {
        repository.appendDiagnostic("Earlier session ended")

        repository.setConnecting(configId = CONFIG_ID)

        assertEquals(emptyList<String>(), diagnostics())
    }

    /** Lines without their `HH:mm:ss` prefix. */
    private fun diagnostics(): List<String> {
        return repository.currentState.diagnostics.map { line -> line.substringAfter(' ') }
    }

    private companion object {
        const val CONFIG_ID = "config"
        const val NOTICE = "Saved passwords and keys can't be decrypted"
    }
}
