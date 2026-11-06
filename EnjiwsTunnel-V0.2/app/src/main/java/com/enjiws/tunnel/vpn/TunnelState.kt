package com.enjiws.tunnel.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Status { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, WAITING_NETWORK }

enum class NetworkKind { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class TunnelState(
    val status: Status = Status.DISCONNECTED,
    val connectedSince: Long = 0L,
    val network: NetworkKind = NetworkKind.NONE,
    val attempt: Int = 0,
    val message: String? = null
)

/** Single source of truth untuk UI. Service menulis, UI hanya membaca. */
object TunnelStateHolder {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    fun update(block: (TunnelState) -> TunnelState) = _state.update(block)
    fun reset() { _state.value = TunnelState() }
}
