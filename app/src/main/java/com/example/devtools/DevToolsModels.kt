package com.example.devtools

import kotlinx.coroutines.flow.asStateFlow

enum class DevToolsTab {
    ELEMENTS,
    CONSOLE,
    NETWORK,
    STORAGE,
    SECURITY
}

enum class ConsoleLevel {
    LOG,
    INFO,
    WARNING,
    ERROR,
    DEBUG,
    RESULT
}

data class ConsoleEntry(
    val id: String,
    val timestamp: Long,
    val level: ConsoleLevel,
    val message: String,
    val sourceId: String? = null,
    val lineNumber: Int? = null
)

enum class NetworkStatus {
    PENDING,
    ALLOWED,
    BLOCKED,
    FAILED
}

enum class ResourceType {
    DOCUMENT,
    SCRIPT,
    STYLESHEET,
    IMAGE,
    FONT,
    MEDIA,
    XHR,
    FETCH,
    WEBSOCKET,
    OTHER
}

data class NetworkEntry(
    val id: String,
    val timestamp: Long,
    val url: String,
    val method: String,
    val host: String,
    val scheme: String,
    val isMainFrame: Boolean,
    val hasGesture: Boolean,
    val resourceType: ResourceType,
    val requestHeaders: Map<String, String> = emptyMap(),
    val status: NetworkStatus = NetworkStatus.PENDING,
    val statusCode: Int? = null,
    val mimeType: String? = null,
    val blockedReason: String? = null,
    val durationMs: Long? = null
)

data class ElementRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class SelectedElement(
    val tagName: String,
    val id: String?,
    val classes: List<String>,
    val textPreview: String,
    val attributes: Map<String, String>,
    val computedStyles: Map<String, String>,
    val selector: String,
    val rect: ElementRect
)

data class StorageSnapshot(
    val cookies: String,
    val localStorage: Map<String, String>,
    val sessionStorage: Map<String, String>,
    val indexedDbAvailable: Boolean
)

data class SecuritySnapshot(
    val url: String,
    val scheme: String,
    val isHttps: Boolean,
    val mixedContentMode: String,
    val safeBrowsingEnabled: Boolean,
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val fileAccessAllowed: Boolean,
    val contentAccessAllowed: Boolean,
    val thirdPartyCookiesAllowed: Boolean,
    val adBlockMode: String,
    val trackerProtectionEnabled: Boolean,
    val isPrivateTab: Boolean,
    val lastSslError: String? = null,
    val lastSafeBrowsingThreat: String? = null
)

data class DevToolsState(
    val isOpen: Boolean = false,
    val activeTab: DevToolsTab = DevToolsTab.CONSOLE,
    val isElementPickerActive: Boolean = false,
    val selectedElement: SelectedElement? = null,
    val consoleEntries: List<ConsoleEntry> = emptyList(),
    val networkEntries: List<NetworkEntry> = emptyList(),
    val domSource: String = "",
    val storageSnapshot: StorageSnapshot? = null,
    val securitySnapshot: SecuritySnapshot? = null,
    val searchQuery: String = "",
    val isRecordingNetwork: Boolean = true
)

class DevToolsStateController(
    private val maxConsoleMessageLength: Int = 2000,
    private val maxConsoleSourceLength: Int = 256,
    private val maxConsoleEntriesPerTab: Int = 200,
    private val maxConsoleEntriesPerSecond: Int = 60
) {
    private val _states = kotlinx.coroutines.flow.MutableStateFlow<Map<String, DevToolsState>>(emptyMap())
    val states: kotlinx.coroutines.flow.StateFlow<Map<String, DevToolsState>> = _states.asStateFlow()

    private val consoleRateLock = Any()
    private var consoleWindowStartMs = 0L
    private var consoleCountInWindow = 0

    fun toggleDevTools(tabId: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(isOpen = !current.isOpen))
        }
    }

    fun openDevTools(tabId: String, tab: DevToolsTab) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(isOpen = true, activeTab = tab))
        }
    }

    fun setDevToolsTab(tabId: String, tab: DevToolsTab) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(activeTab = tab))
        }
    }

    fun setElementPickerActive(tabId: String, active: Boolean) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(isElementPickerActive = active))
        }
    }

    fun updateSelectedElement(tabId: String, element: SelectedElement) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(selectedElement = element, isElementPickerActive = false))
        }
    }

    fun addConsoleEntry(tabId: String, entry: ConsoleEntry, inspectEnabled: Boolean) {
        if (!inspectEnabled) return
        val now = System.currentTimeMillis()
        synchronized(consoleRateLock) {
            if (now - consoleWindowStartMs > 1000L) {
                consoleWindowStartMs = now
                consoleCountInWindow = 0
            }
            if (++consoleCountInWindow > maxConsoleEntriesPerSecond) return
        }
        val boundedEntry = entry.copy(
            message = entry.message.take(maxConsoleMessageLength),
            sourceId = entry.sourceId?.take(maxConsoleSourceLength)
        )
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val newEntries = (current.consoleEntries + boundedEntry).takeLast(maxConsoleEntriesPerTab)
            currentMap + (tabId to current.copy(consoleEntries = newEntries))
        }
    }

    fun addConsoleResult(tabId: String, command: String, result: String) {
        val entryCmd = ConsoleEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            level = ConsoleLevel.DEBUG,
            message = ("> $command").take(maxConsoleMessageLength)
        )
        val entryRes = ConsoleEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            level = ConsoleLevel.RESULT,
            message = result.take(maxConsoleMessageLength)
        )
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val newEntries = (current.consoleEntries + entryCmd + entryRes).takeLast(maxConsoleEntriesPerTab)
            currentMap + (tabId to current.copy(consoleEntries = newEntries))
        }
    }

    fun clearConsole(tabId: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(consoleEntries = emptyList()))
        }
    }

    fun recordNetworkRequest(
        tabId: String,
        url: String,
        method: String,
        isMainFrame: Boolean,
        hasGesture: Boolean,
        headers: Map<String, String>,
        resourceType: ResourceType
    ): String {
        val entryId = java.util.UUID.randomUUID().toString()
        val host = try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
        val scheme = try { android.net.Uri.parse(url).scheme ?: "" } catch (_: Exception) { "" }
        val entry = NetworkEntry(
            id = entryId,
            timestamp = System.currentTimeMillis(),
            url = url,
            method = method,
            host = host,
            scheme = scheme,
            isMainFrame = isMainFrame,
            hasGesture = hasGesture,
            resourceType = resourceType,
            requestHeaders = headers,
            status = NetworkStatus.PENDING
        )
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val newEntries = (current.networkEntries + entry).takeLast(500)
            currentMap + (tabId to current.copy(networkEntries = newEntries))
        }
        return entryId
    }

    fun updateNetworkBlocked(tabId: String, entryId: String, reason: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val updated = current.networkEntries.map { entry ->
                if (entry.id == entryId) {
                    entry.copy(
                        status = NetworkStatus.BLOCKED,
                        blockedReason = reason,
                        durationMs = System.currentTimeMillis() - entry.timestamp
                    )
                } else entry
            }
            currentMap + (tabId to current.copy(networkEntries = updated))
        }
    }

    fun updateNetworkAllowed(tabId: String, entryId: String, statusCode: Int? = null, mimeType: String? = null) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val updated = current.networkEntries.map { entry ->
                if (entry.id == entryId) {
                    entry.copy(
                        status = NetworkStatus.ALLOWED,
                        statusCode = statusCode ?: entry.statusCode ?: 200,
                        mimeType = mimeType ?: entry.mimeType,
                        durationMs = System.currentTimeMillis() - entry.timestamp
                    )
                } else entry
            }
            currentMap + (tabId to current.copy(networkEntries = updated))
        }
    }

    fun clearNetwork(tabId: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(networkEntries = emptyList()))
        }
    }

    fun updateDomSource(tabId: String, source: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(domSource = source))
        }
    }

    fun updateStorageSnapshot(tabId: String, snapshot: StorageSnapshot) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            currentMap + (tabId to current.copy(storageSnapshot = snapshot))
        }
    }

    fun refreshSecuritySnapshot(
        tabId: String,
        currentUrl: String,
        isPrivate: Boolean,
        adBlockModeName: String,
        isTrackerBlockEnabled: Boolean,
        thirdPartyCookiesAllowed: Boolean,
        safeBrowsingReady: Boolean
    ) {
        val uri = try { android.net.Uri.parse(currentUrl) } catch (_: Exception) { null }
        val scheme = uri?.scheme ?: ""
        val isHttps = scheme.equals("https", ignoreCase = true)
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val currentSec = current.securitySnapshot
            val newSec = SecuritySnapshot(
                url = currentUrl,
                scheme = scheme,
                isHttps = isHttps,
                mixedContentMode = "MIXED_CONTENT_NEVER_ALLOW",
                safeBrowsingEnabled = safeBrowsingReady,
                javaScriptEnabled = true,
                domStorageEnabled = !isPrivate,
                fileAccessAllowed = false,
                contentAccessAllowed = false,
                thirdPartyCookiesAllowed = thirdPartyCookiesAllowed,
                adBlockMode = adBlockModeName,
                trackerProtectionEnabled = isTrackerBlockEnabled,
                isPrivateTab = isPrivate,
                lastSslError = currentSec?.lastSslError,
                lastSafeBrowsingThreat = currentSec?.lastSafeBrowsingThreat
            )
            currentMap + (tabId to current.copy(securitySnapshot = newSec))
        }
    }

    fun updateLastSslError(tabId: String, error: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val currentSec = current.securitySnapshot
            val updatedSec = currentSec?.copy(lastSslError = error) ?: SecuritySnapshot(
                url = "", scheme = "", isHttps = false, mixedContentMode = "",
                safeBrowsingEnabled = false, javaScriptEnabled = false, domStorageEnabled = false,
                fileAccessAllowed = false, contentAccessAllowed = false, thirdPartyCookiesAllowed = false,
                adBlockMode = "", trackerProtectionEnabled = false, isPrivateTab = false,
                lastSslError = error
            )
            currentMap + (tabId to current.copy(securitySnapshot = updatedSec))
        }
    }

    fun updateLastSafeBrowsingThreat(tabId: String, threat: String) {
        _states.value = _states.value.let { currentMap ->
            val current = currentMap[tabId] ?: DevToolsState()
            val currentSec = current.securitySnapshot
            val updatedSec = currentSec?.copy(lastSafeBrowsingThreat = threat) ?: SecuritySnapshot(
                url = "", scheme = "", isHttps = false, mixedContentMode = "",
                safeBrowsingEnabled = false, javaScriptEnabled = false, domStorageEnabled = false,
                fileAccessAllowed = false, contentAccessAllowed = false, thirdPartyCookiesAllowed = false,
                adBlockMode = "", trackerProtectionEnabled = false, isPrivateTab = false,
                lastSafeBrowsingThreat = threat
            )
            currentMap + (tabId to current.copy(securitySnapshot = updatedSec))
        }
    }

    fun clearDevToolsState(tabId: String) {
        _states.value = _states.value - tabId
    }

    fun clearAll() {
        _states.value = emptyMap()
    }
}
