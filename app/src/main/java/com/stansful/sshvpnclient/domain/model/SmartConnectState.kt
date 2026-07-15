package com.stansful.sshvpnclient.domain.model

enum class SmartConnectPhase {
    IDLE,
    STARTING,
    WAITING_FOR_NETWORK,
    REFRESHING,
    CHECKING,
    CLEANING,
    SELECTING,
    CONNECTING,
    VERIFYING,
    CONNECTED,
    FAILING_OVER,
    RETRY_WAIT,
    STOPPING,
    ERROR,
}

data class SmartConnectState(
    val phase: SmartConnectPhase = SmartConnectPhase.IDLE,
    val desiredActive: Boolean = false,
    val checkCompleted: Int = 0,
    val checkTotal: Int = 0,
    val catalogSize: Int = 0,
    val availableCount: Int = 0,
    val removedCount: Int = 0,
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val activeProfileLatencyMs: Long? = null,
    val lastHealthLatencyMs: Long? = null,
    val retryDelayMs: Long? = null,
    val message: String? = null,
) {
    val isBusy: Boolean
        get() = phase in setOf(
            SmartConnectPhase.STARTING,
            SmartConnectPhase.WAITING_FOR_NETWORK,
            SmartConnectPhase.REFRESHING,
            SmartConnectPhase.CHECKING,
            SmartConnectPhase.CLEANING,
            SmartConnectPhase.SELECTING,
            SmartConnectPhase.CONNECTING,
            SmartConnectPhase.VERIFYING,
            SmartConnectPhase.FAILING_OVER,
            SmartConnectPhase.RETRY_WAIT,
            SmartConnectPhase.STOPPING,
        )

    val isConnected: Boolean
        get() = phase == SmartConnectPhase.CONNECTED || phase == SmartConnectPhase.VERIFYING
}
