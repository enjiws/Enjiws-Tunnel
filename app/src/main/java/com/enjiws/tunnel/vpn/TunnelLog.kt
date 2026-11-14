package com.enjiws.tunnel.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val time: Long, val level: LogLevel, val message: String)

/** Ring buffer log koneksi (di memori, maks 300 baris). Jangan pernah menulis password/secret ke sini. */
object TunnelLog {
    private const val MAX = 300
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogLevel, message: String) {
        _entries.update { (it + LogEntry(System.currentTimeMillis(), level, message)).takeLast(MAX) }
    }

    fun clear() { _entries.value = emptyList() }
}
