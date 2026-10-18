package com.yourbrowser.adblock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AlexShieldBlockedLog(
    val tabId: String,
    val pageHost: String,
    val blockedUrl: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

object AlexShieldV5LogStore {
    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<AlexShieldBlockedLog>>(emptyList())
    val logs: StateFlow<List<AlexShieldBlockedLog>> = _logs

    fun add(tabId: String, pageUrl: String?, blockedUrl: String, reason: String) {
        val host = try {
            android.net.Uri.parse(pageUrl ?: "").host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }

        _logs.update { old ->
            (old + AlexShieldBlockedLog(tabId, host, blockedUrl, reason)).takeLast(MAX_LOGS)
        }
    }

    fun forTab(tabId: String): List<AlexShieldBlockedLog> {
        return _logs.value.filter { it.tabId == tabId }
    }

    fun clearTab(tabId: String) {
        _logs.update { old -> old.filterNot { it.tabId == tabId } }
    }

    fun clearAll() {
        _logs.value = emptyList()
    }
}
