package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import kotlinx.coroutines.flow.Flow

interface ProxyProfileRepository {
    fun observeSummaries(): Flow<List<ProxyProfileSummary>>
    suspend fun getById(id: String): ProxyProfile?
    suspend fun getSelected(): ProxyProfile?
    suspend fun import(
        text: String,
        source: ProxyProfileSource,
        sourceUrl: String? = null,
    ): ProxyImportResult
    suspend fun update(id: String, rawUri: String): ProxyImportResult
    suspend fun select(id: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun delete(ids: Set<String>)
    suspend fun saveTestResult(result: ProxyTunnelTestResult)
}
