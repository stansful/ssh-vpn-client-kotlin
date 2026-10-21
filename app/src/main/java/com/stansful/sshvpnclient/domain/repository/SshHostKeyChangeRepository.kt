package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import kotlinx.coroutines.flow.Flow

/**
 * Changed host keys the SSH service ran into, one per server, for the server editor to offer
 * "Accept new key". Process memory only: after a restart the next connection attempt finds it again.
 */
interface SshHostKeyChangeRepository {
    fun observe(configId: String): Flow<SshHostKeyChange?>
    fun record(change: SshHostKeyChange)
    fun clear(configId: String)
}
