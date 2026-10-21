package com.stansful.sshvpnclient.data.config

import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import com.stansful.sshvpnclient.domain.repository.SshHostKeyChangeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemorySshHostKeyChangeRepository : SshHostKeyChangeRepository {
    private val changes = MutableStateFlow<Map<String, SshHostKeyChange>>(emptyMap())

    override fun observe(configId: String): Flow<SshHostKeyChange?> {
        return changes.map { byConfig -> byConfig[configId] }.distinctUntilChanged()
    }

    override fun record(change: SshHostKeyChange) {
        changes.update { byConfig -> byConfig + (change.configId to change) }
    }

    override fun clear(configId: String) {
        changes.update { byConfig -> if (configId in byConfig) byConfig - configId else byConfig }
    }
}
