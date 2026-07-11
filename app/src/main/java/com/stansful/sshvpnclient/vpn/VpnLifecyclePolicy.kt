package com.stansful.sshvpnclient.vpn

/**
 * Pure precondition for a service lifecycle mutation. The final Android-side guard is
 * Service.stopSelfResult(startId), which also observes starts queued by the system but not yet
 * delivered to onStartCommand().
 */
internal fun isVpnLifecycleCommandCurrent(
    expectedRunId: Long,
    currentRunId: Long,
    expectedCommandId: Long,
    currentCommandId: Long,
    expectedStartId: Int,
    serviceDestroyed: Boolean,
): Boolean {
    return expectedStartId > 0 &&
        !serviceDestroyed &&
        expectedRunId == currentRunId &&
        expectedCommandId == currentCommandId
}
