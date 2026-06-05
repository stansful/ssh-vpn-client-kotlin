package com.stansful.sshvpnclient.domain.usecase.vpn

import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository

class ObserveVpnConnectionStateUseCase(
    private val repository: VpnConnectionRepository,
) {
    operator fun invoke() = repository.state
}
