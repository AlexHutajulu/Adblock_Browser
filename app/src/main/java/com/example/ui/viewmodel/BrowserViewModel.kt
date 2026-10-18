package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.BookmarkItem
import com.example.data.BrowserRepository
import com.example.data.HistoryItem
import com.example.manager.AppTheme
import com.example.manager.DisplayMode
import com.example.manager.SearchEngine
import com.example.manager.SearchEngineManager
import com.example.manager.SettingsManager
import com.example.manager.PermissionManager
import com.example.manager.PermissionState
import com.example.manager.SitePermissionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.flow.update
import com.example.devtools.*

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "New Tab",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isPrivate: Boolean = false,
    val loadError: String? = null,
    val isPlayingAudio: Boolean = false,
    val isMuted: Boolean = false
)


class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "BrowserViewModel"

    private val db = AppDatabase.getDatabase(application)
    private val repository = BrowserRepository(db)
    val downloadRepository = com.example.data.DownloadRepository(db.downloadDao())
    val downloadManager = com.example.manager.AppDownloadManager(application, downloadRepository, viewModelScope)
    private val settings = SettingsManager(application)
    val permissionManager = PermissionManager(application)

    data class PendingPermissionRequest(
        val url: String,
        val types: List<SitePermissionType>,
        val onGrant: (List<SitePermissionType>) -> Unit,
        val onDeny: () -> Unit
    )

    private val _pendingPermissionRequest = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingPermissionRequest = _pendingPermissionRequest.asStateFlow()

    private val _intentToOpen = MutableStateFlow<String?>(null)
    val intentToOpen = _intentToOpen.asStateFlow()

    fun requestOpenIntent(url: String) {
        _intentToOpen.value = url
    }

    fun clearIntentToOpen() {
        _intentToOpen.value = null
    }

    private val _activePermissions = MutableStateFlow<Map<String, Set<SitePermissionType>>>(emptyMap())
    val activePermissions = _activePermissions.asStateFlow()

    fun requestSitePermission(
        url: String,
        types: List<SitePermissionType>,
        isIncognito: Boolean,
        onResult: (granted: List<SitePermissionType>) -> Unit
    ) {
        val granted = mutableListOf<SitePermissionType>()
        val unhandled = mutableListOf<SitePermissionType>()

        for (type in types) {
            val state = if (isIncognito) PermissionState.ASK else permissionManager.getWebsitePermission(url, type)
            when (state) {
                PermissionState.ALLOW -> granted.add(type)
                PermissionState.BLOCK -> { /* blocked */ }
                PermissionState.ASK -> unhandled.add(type)
            }
        }

        if (unhandled.isEmpty()) {
            onResult(granted)
            return
        }

        _pendingPermissionRequest.value = PendingPermissionRequest(
            url = url,
            types = unhandled,
            onGrant = { newlyGranted ->
                onResult(granted + newlyGranted)
                _pendingPermissionRequest.value = null
            },
            onDeny = {
                onResult(granted)
                _pendingPermissionRequest.value = null
            }
        )
    }

    fun handlePermissionChoice(url: String, type: SitePermissionType, state: PermissionState, isIncognito: Boolean) {
        if (!isIncognito) {
            permissionManager.setWebsitePermission(url, type, state)
        }
    }

    fun clearPendingPermissionRequest() {
        _pendingPermissionRequest.value = null
    }

    fun addActivePermission(tabId: String, type: SitePermissionType) {
        val current = _activePermissions.value.toMutableMap()
        val set = current[tabId]?.toMutableSet() ?: mutableSetOf()
        set.add(type)
        current[tabId] = set
        _activePermissions.value = current
    }

    fun removeActivePermission(tabId: String, type: SitePermissionType) {
        val current = _activePermissions.value.toMutableMap()
        val set = current[tabId]?.toMutableSet() ?: mutableSetOf()
        set.remove(type)
        if (set.isEmpty()) {
            current.remove(tabId)
        } else {
            current[tabId] = set
        }
        _activePermissions.value = current
    }

    fun clearTabActivePermissions(tabId: String) {
        val current = _activePermissions.value.toMutableMap()
        current.remove(tabId)
        _activePermissions.value = current
    }

    // Lists of bookmarks and history
    val bookmarksState: StateFlow<List<BookmarkItem>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyState: StateFlow<List<HistoryItem>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Panel visibility toggles
    private val _showSettings = MutableStateFlow(false)
    val showSettings = _showSettings.asStateFlow()

    private val _showHistory = MutableStateFlow(false)
    val showHistory = _showHistory.asStateFlow()

    private val _showBookmarks = MutableStateFlow(false)
    val showBookmarks = _showBookmarks.asStateFlow()

    private val _showTabsDialog = MutableStateFlow(false)
    val showTabsDialog = _showTabsDialog.asStateFlow()

    private val _showDownloadsDialog = MutableStateFlow(false)
    val showDownloadsDialog = _showDownloadsDialog.asStateFlow()

    fun setDownloadsVisible(visible: Boolean) {
        _showDownloadsDialog.value = visible
    }

    // Multi-tab system
    private val _tabs = MutableStateFlow<List<BrowserTab>>(
        run {
            val loaded = settings.loadTabs()
            if (!loaded.isNullOrEmpty()) {
                loaded.map { tab ->
                    if (tab.title == "Loading..." && tab.url == "https://www.google.com") {
                        tab.copy(url = "", title = "New Tab")
                    } else tab
                }
            } else {
                listOf(BrowserTab(url = "", title = "New Tab", isPrivate = false))
            }
        }
    )
    val tabs = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(
        run {
            val savedActiveId = settings.getActiveTabId()
            val loadedIds = _tabs.value.map { it.id }
            if (savedActiveId != null && savedActiveId in loadedIds) {
                savedActiveId
            } else {
                _tabs.value[0].id
            }
        }
    )
    val activeTabId = _activeTabId.asStateFlow()

    private fun saveCurrentTabsToPreferences() {
        val regularTabs = _tabs.value.filter { !it.isPrivate }
        settings.saveTabs(regularTabs, _activeTabId.value)
    }

    // Preferences exposed directly
    private val _adBlockMode = MutableStateFlow(settings.adBlockMode)
    val adBlockMode = _adBlockMode.asStateFlow()

    private val _adBlockEnabled = MutableStateFlow(settings.adBlockMode != com.example.manager.AdBlockMode.OFF)
    val adBlockEnabled = _adBlockEnabled.asStateFlow()

    private val _trackerBlockEnabled = MutableStateFlow(settings.isTrackerBlockEnabled)
    val trackerBlockEnabled = _trackerBlockEnabled.asStateFlow()

    private val _privacyShieldMode = MutableStateFlow(settings.privacyShieldMode)
    val privacyShieldMode = _privacyShieldMode.asStateFlow()

    private val _preventWebRtcLeak = MutableStateFlow(settings.preventWebRtcLeak)
    val preventWebRtcLeak = _preventWebRtcLeak.asStateFlow()

    private val _strictWebRtcMode = MutableStateFlow(settings.strictWebRtcMode)
    val strictWebRtcMode = _strictWebRtcMode.asStateFlow()

    private val _clearCookiesOnExit = MutableStateFlow(settings.clearCookiesOnExit)
    val clearCookiesOnExit = _clearCookiesOnExit.asStateFlow()
    
    // Counters
    private val _trackerCookiesBlockedCount = MutableStateFlow(settings.trackersCookiesBlockedCount)
    val trackerCookiesBlockedCount = _trackerCookiesBlockedCount.asStateFlow()

    private val _fingerprintingBlockedCount = MutableStateFlow(settings.fingerprintingBlockedCount)
    val fingerprintingBlockedCount = _fingerprintingBlockedCount.asStateFlow()

    private val _thirdPartyCookiesBlockedCount = MutableStateFlow(settings.thirdPartyCookiesBlockedCount)
    val thirdPartyCookiesBlockedCount = _thirdPartyCookiesBlockedCount.asStateFlow()
    
    private val _openPopupsInNewTab = MutableStateFlow(settings.openPopupsInNewTab)
    val openPopupsInNewTab = _openPopupsInNewTab.asStateFlow()

    private val _blockSuspiciousRedirects = MutableStateFlow(settings.blockSuspiciousRedirects)
    val blockSuspiciousRedirects = _blockSuspiciousRedirects.asStateFlow()

    private val _selectedSearchEngine = MutableStateFlow(settings.selectedSearchEngine)
    val selectedSearchEngine = _selectedSearchEngine.asStateFlow()

    private val _appTheme = MutableStateFlow(settings.appTheme)
    val appTheme = _appTheme.asStateFlow()

    private val _forceWebPageDarkMode = MutableStateFlow(settings.forceWebPageDarkMode)
    val forceWebPageDarkMode = _forceWebPageDarkMode.asStateFlow()

    private val _displayMode = MutableStateFlow(settings.displayMode)
    val displayMode = _displayMode.asStateFlow()

    private val _streamingBoostEnabled = MutableStateFlow(settings.isStreamingBoostEnabled)
    val streamingBoostEnabled = _streamingBoostEnabled.asStateFlow()

    private val _downloadAcceleration = MutableStateFlow(settings.downloadAcceleration)
    val downloadAcceleration = _downloadAcceleration.asStateFlow()

    private val _inspectToolsEnabled = MutableStateFlow(settings.isInspectToolsEnabled)
    val inspectToolsEnabled = _inspectToolsEnabled.asStateFlow()

    private val _allowScreenRecording = MutableStateFlow(settings.allowScreenRecording)
    val allowScreenRecording = _allowScreenRecording.asStateFlow()

    private val _dataSaverEnabled = MutableStateFlow(settings.isDataSaverEnabled)
    val dataSaverEnabled = _dataSaverEnabled.asStateFlow()

    private val _backgroundPlaybackEnabled = MutableStateFlow(settings.isBackgroundPlaybackEnabled)
    val backgroundPlaybackEnabled = _backgroundPlaybackEnabled.asStateFlow()

    private val _isPipMode = MutableStateFlow(false)
    val isPipMode = _isPipMode.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen = _isFullscreen.asStateFlow()

    private val _isAppPaused = MutableStateFlow(false)
    val isAppPaused = _isAppPaused.asStateFlow()

    fun setAppPaused(paused: Boolean) {
        _isAppPaused.value = paused
    }

    fun setPipMode(enabled: Boolean) {
        _isPipMode.value = enabled
    }

    fun setFullscreen(enabled: Boolean) {
        _isFullscreen.value = enabled
    }

    private val _devToolsStates = MutableStateFlow<Map<String, DevToolsState>>(emptyMap())
    val devToolsStates = _devToolsStates.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                com.example.manager.FilterListManager.loadRules(getApplication())
                Log.d("BrowserViewModel", "AdBlock filter lists loaded successfully")
            } catch (e: Exception) {
                Log.w("BrowserViewModel", "Filter list load failed: \${e.message}")
            }
        }
    }

    fun toggleDevTools(tabId: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(isOpen = !current.isOpen))
        }
    }

    fun openDevTools(tabId: String, tab: DevToolsTab) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(isOpen = true, activeTab = tab))
        }
    }

    fun setDevToolsTab(tabId: String, tab: DevToolsTab) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(activeTab = tab))
        }
    }

    fun setElementPickerActive(tabId: String, active: Boolean) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(isElementPickerActive = active))
        }
    }

    fun updateSelectedElement(tabId: String, element: SelectedElement) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(selectedElement = element, isElementPickerActive = false))
        }
    }

    fun addConsoleEntry(tabId: String, entry: ConsoleEntry) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val newEntries = (current.consoleEntries + entry).takeLast(300)
            states + (tabId to current.copy(consoleEntries = newEntries))
        }
    }

    fun addConsoleResult(tabId: String, command: String, result: String) {
        val entryCmd = ConsoleEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            level = ConsoleLevel.DEBUG,
            message = "> $command"
        )
        val entryRes = ConsoleEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            level = ConsoleLevel.RESULT,
            message = result
        )
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val newEntries = (current.consoleEntries + entryCmd + entryRes).takeLast(300)
            states + (tabId to current.copy(consoleEntries = newEntries))
        }
    }

    fun clearConsole(tabId: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(consoleEntries = emptyList()))
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
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val newEntries = (current.networkEntries + entry).takeLast(500)
            states + (tabId to current.copy(networkEntries = newEntries))
        }
        return entryId
    }

    fun updateNetworkBlocked(tabId: String, entryId: String, reason: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val updated = current.networkEntries.map { entry ->
                if (entry.id == entryId) {
                    entry.copy(
                        status = NetworkStatus.BLOCKED,
                        blockedReason = reason,
                        durationMs = System.currentTimeMillis() - entry.timestamp
                    )
                } else entry
            }
            states + (tabId to current.copy(networkEntries = updated))
        }
    }

    fun updateNetworkAllowed(tabId: String, entryId: String, statusCode: Int? = null, mimeType: String? = null) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
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
            states + (tabId to current.copy(networkEntries = updated))
        }
    }

    fun clearNetwork(tabId: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(networkEntries = emptyList()))
        }
    }

    fun updateDomSource(tabId: String, source: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(domSource = source))
        }
    }

    fun updateStorageSnapshot(tabId: String, snapshot: StorageSnapshot) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            states + (tabId to current.copy(storageSnapshot = snapshot))
        }
    }

    fun refreshSecuritySnapshot(tabId: String, currentUrl: String, isPrivate: Boolean) {
        val uri = try { android.net.Uri.parse(currentUrl) } catch (_: Exception) { null }
        val scheme = uri?.scheme ?: ""
        val isHttps = scheme.equals("https", ignoreCase = true)
        
        val adBlockModeName = adBlockMode.value.name
        val isTrackerBlockEnabled = trackerBlockEnabled.value
        
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val currentSec = current.securitySnapshot
            val newSec = SecuritySnapshot(
                url = currentUrl,
                scheme = scheme,
                isHttps = isHttps,
                mixedContentMode = "MIXED_CONTENT_NEVER_ALLOW",
                safeBrowsingEnabled = true,
                javaScriptEnabled = true,
                domStorageEnabled = true,
                fileAccessAllowed = false,
                contentAccessAllowed = false,
                thirdPartyCookiesAllowed = !isPrivate && (privacyShieldMode.value != com.example.manager.PrivacyShieldMode.STRICT),
                adBlockMode = adBlockModeName,
                trackerProtectionEnabled = isTrackerBlockEnabled,
                isPrivateTab = isPrivate,
                lastSslError = currentSec?.lastSslError,
                lastSafeBrowsingThreat = currentSec?.lastSafeBrowsingThreat
            )
            states + (tabId to current.copy(securitySnapshot = newSec))
        }
    }

    fun updateLastSslError(tabId: String, error: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val currentSec = current.securitySnapshot
            val updatedSec = currentSec?.copy(lastSslError = error) ?: SecuritySnapshot(
                url = "", scheme = "", isHttps = false, mixedContentMode = "",
                safeBrowsingEnabled = false, javaScriptEnabled = false, domStorageEnabled = false,
                fileAccessAllowed = false, contentAccessAllowed = false, thirdPartyCookiesAllowed = false,
                adBlockMode = "", trackerProtectionEnabled = false, isPrivateTab = false,
                lastSslError = error
            )
            states + (tabId to current.copy(securitySnapshot = updatedSec))
        }
    }

    fun updateLastSafeBrowsingThreat(tabId: String, threat: String) {
        _devToolsStates.update { states ->
            val current = states[tabId] ?: DevToolsState()
            val currentSec = current.securitySnapshot
            val updatedSec = currentSec?.copy(lastSafeBrowsingThreat = threat) ?: SecuritySnapshot(
                url = "", scheme = "", isHttps = false, mixedContentMode = "",
                safeBrowsingEnabled = false, javaScriptEnabled = false, domStorageEnabled = false,
                fileAccessAllowed = false, contentAccessAllowed = false, thirdPartyCookiesAllowed = false,
                adBlockMode = "", trackerProtectionEnabled = false, isPrivateTab = false,
                lastSafeBrowsingThreat = threat
            )
            states + (tabId to current.copy(securitySnapshot = updatedSec))
        }
    }

    fun clearDevToolsState(tabId: String) {
        _devToolsStates.update { states ->
            states - tabId
        }
    }

    private val _adBlockDisabledDomains = MutableStateFlow(settings.getAdBlockDisabledDomains())
    val adBlockDisabledDomains = _adBlockDisabledDomains.asStateFlow()

    fun setAdBlockDisabledForDomain(domain: String, disabled: Boolean) {
        settings.setAdBlockDisabledForDomain(domain, disabled)
        _adBlockDisabledDomains.value = settings.getAdBlockDisabledDomains()
    }

    fun getBlockedLogsForPage(url: String): List<com.example.manager.AdBlockEngine.BlockedRequest> {
        val targetHost = try {
            android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
        if (targetHost.isEmpty()) return emptyList()

        return com.yourbrowser.adblock.AlexShieldV5LogStore.logs.value
            .filter { it.pageHost == targetHost }
            .map { log ->
                val blockedHost = try {
                    android.net.Uri.parse(log.blockedUrl).host ?: ""
                } catch (_: Exception) {
                    ""
                }
                
                val lowerUrl = log.blockedUrl.lowercase()
                val isPopup = log.reason.contains("popup", true) || log.reason.contains("Redirect", true)
                val isTracker = lowerUrl.contains("analytics") || lowerUrl.contains("tracker") ||
                                lowerUrl.contains("pixel") || lowerUrl.contains("beacon") ||
                                lowerUrl.contains("telemetry") || lowerUrl.contains("collect") ||
                                lowerUrl.contains("event")
                val category = when {
                    isPopup -> "Popup"
                    isTracker -> "Tracker"
                    else -> "Ad"
                }

                com.example.manager.AdBlockEngine.BlockedRequest(
                    url = log.blockedUrl,
                    host = blockedHost,
                    type = category,
                    mainPageUrl = url,
                    timestamp = log.timestamp
                )
            }
    }

    fun clearBlockedLogs() {
        com.yourbrowser.adblock.AlexShieldV5LogStore.clearAll()
    }

    fun toggleTabMute(tabId: String) {
        val updatedTabs = _tabs.value.map {
            if (it.id == tabId) {
                it.copy(isMuted = !it.isMuted)
            } else {
                it
            }
        }
        _tabs.value = updatedTabs
    }

    // Navigation trigger (each action has a unique actionId so it executes strictly once)
    sealed class NavigationAction(val actionId: String = UUID.randomUUID().toString()) {
        data class LoadUrl(val url: String, val id: String = UUID.randomUUID().toString()) : NavigationAction(id)
        data class GoBack(val id: String = UUID.randomUUID().toString()) : NavigationAction(id)
        data class GoForward(val id: String = UUID.randomUUID().toString()) : NavigationAction(id)
        data class Reload(val id: String = UUID.randomUUID().toString()) : NavigationAction(id)
        data class StopLoading(val id: String = UUID.randomUUID().toString()) : NavigationAction(id)
    }

    private val _navigationActions = MutableStateFlow<Map<String, NavigationAction>>(emptyMap())
    val navigationActions = _navigationActions.asStateFlow()

    // Per-tab visited URL history stack to guarantee reliable step-by-step Back navigation
    private val _tabHistoryStacks = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tabHistoryStacks = _tabHistoryStacks.asStateFlow()

    fun pushTabHistoryUrl(tabId: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        val currentStack = _tabHistoryStacks.value[tabId] ?: emptyList()
        if (currentStack.lastOrNull() == url) return
        val updatedStack = (currentStack + url).takeLast(50)
        _tabHistoryStacks.value = _tabHistoryStacks.value + (tabId to updatedStack)
    }

    fun getTabHistoryStack(tabId: String): List<String> {
        return _tabHistoryStacks.value[tabId] ?: emptyList()
    }

    fun getActiveTab(): BrowserTab? {
        return _tabs.value.find { it.id == _activeTabId.value }
    }

    fun consumeNavigationAction(tabId: String, actionId: String? = null) {
        val current = _navigationActions.value[tabId] ?: return
        if (actionId == null || current.actionId == actionId) {
            _navigationActions.value = _navigationActions.value.toMutableMap().apply {
                remove(tabId)
            }
        }
    }

    // URL Loading
    fun navigateTo(inputUrl: String) {
        val activeId = _activeTabId.value
        if (inputUrl.isBlank()) {
            _tabs.value = _tabs.value.map {
                if (it.id == activeId) {
                    it.copy(
                        url = "",
                        title = if (it.isPrivate) "Private Tab" else "New Tab",
                        loadError = null,
                        isLoading = false,
                        progress = 0,
                        canGoBack = false,
                        canGoForward = false
                    )
                } else it
            }
            consumeNavigationAction(activeId)
            saveCurrentTabsToPreferences()
            return
        }

        val engine = SearchEngine.fromName(_selectedSearchEngine.value)
        val parsedUrl = SearchEngineManager.parseUrlOrQuery(inputUrl, engine)
        val host = com.example.manager.SecurityGuardEngine.extractHost(parsedUrl)
        val isTrusted = com.example.manager.SecurityGuardEngine.isHostUserTrusted(getApplication(), host)
        val finalUrl = if (isTrusted) {
            parsedUrl
        } else {
            com.example.manager.SecurityGuardEngine.upgradeUrlIfHttpsOnly(parsedUrl, _httpsOnlyMode.value)
        }

        val threat = com.example.manager.SecurityGuardEngine.inspectUrlThreat(
            url = finalUrl,
            httpsOnlyEnabled = _httpsOnlyMode.value,
            phishingProtectionEnabled = _phishingProtectionEnabled.value,
            isUserTrustedHost = isTrusted
        )
        if (threat.severity != com.example.manager.ThreatSeverity.SAFE) {
            _tabSecurityThreats.value = _tabSecurityThreats.value + (activeId to threat)
        } else {
            _tabSecurityThreats.value = _tabSecurityThreats.value - activeId
        }

        pushTabHistoryUrl(activeId, finalUrl)

        _tabs.value = _tabs.value.map {
            if (it.id == activeId) it.copy(url = finalUrl, loadError = null, isLoading = threat.severity == com.example.manager.ThreatSeverity.SAFE, progress = 10) else it
        }

        if (threat.severity == com.example.manager.ThreatSeverity.SAFE) {
            _navigationActions.value = _navigationActions.value.toMutableMap().apply {
                put(activeId, NavigationAction.LoadUrl(finalUrl))
            }
        }
        saveCurrentTabsToPreferences()
    }

    fun goBack() {
        val activeId = _activeTabId.value
        val currentTab = _tabs.value.find { it.id == activeId } ?: return
        if (currentTab.url.isBlank() || currentTab.url == "about:blank") return
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) it.copy(loadError = null) else it
        }
        _navigationActions.value = _navigationActions.value.toMutableMap().apply {
            put(activeId, NavigationAction.GoBack())
        }
    }

    fun goForward() {
        val activeId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) it.copy(loadError = null) else it
        }
        _navigationActions.value = _navigationActions.value.toMutableMap().apply {
            put(activeId, NavigationAction.GoForward())
        }
    }

    fun reload() {
        val activeId = _activeTabId.value
        val activeTab = _tabs.value.find { it.id == activeId }
        if (activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank") return
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) it.copy(loadError = null, isLoading = true, progress = 10) else it
        }
        _navigationActions.value = _navigationActions.value.toMutableMap().apply {
            put(activeId, NavigationAction.Reload())
        }
    }

    fun stop() {
        val activeId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) it.copy(isLoading = false) else it
        }
        _navigationActions.value = _navigationActions.value.toMutableMap().apply {
            put(activeId, NavigationAction.StopLoading())
        }
    }

    fun goHome() {
        navigateTo("") // Go to the home screen cleanly
    }

    // Tab Management
    fun addNewTab(url: String = "", isPrivate: Boolean = false) {
        val targetUrl = url
        val newTab = BrowserTab(
            url = targetUrl,
            title = if (isPrivate) "Private Tab" else "New Tab",
            isPrivate = isPrivate
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
        _showTabsDialog.value = false

        if (targetUrl.isNotEmpty() && targetUrl != "about:blank") {
            _navigationActions.value = _navigationActions.value.toMutableMap().apply {
                put(newTab.id, NavigationAction.LoadUrl(targetUrl))
            }
        }
        saveCurrentTabsToPreferences()
    }

    private val _closedTabs = mutableListOf<BrowserTab>()
    
    fun closeAllPrivateTabs() {
        val nonPrivateTabs = _tabs.value.filter { !it.isPrivate }
        
        if (nonPrivateTabs.isEmpty()) {
            val newTab = BrowserTab(url = "", title = "New Tab", isPrivate = false)
            _tabs.value = listOf(newTab)
            _activeTabId.value = newTab.id
            if (newTab.url.isNotEmpty()) {
                _navigationActions.value = _navigationActions.value.toMutableMap().apply {
                    put(newTab.id, NavigationAction.LoadUrl(newTab.url))
                }
            }
        } else {
            _tabs.value = nonPrivateTabs
            if (_activeTabId.value !in _tabs.value.map { it.id }) {
                _activeTabId.value = _tabs.value.last().id
            }
        }
        saveCurrentTabsToPreferences()
    }

    fun closeAllTabs(isIncognito: Boolean = false) {
        if (isIncognito) {
            closeAllPrivateTabs()
            return
        }
        val currentNormalTabs = _tabs.value.filter { !it.isPrivate }
        currentNormalTabs.forEach { _closedTabs.add(it) }
        val privateTabs = _tabs.value.filter { it.isPrivate }
        val newTab = BrowserTab(url = "", title = "New Tab", isPrivate = false)
        _tabs.value = privateTabs + newTab
        _activeTabId.value = newTab.id
        saveCurrentTabsToPreferences()
    }

    fun removeTab(tabId: String) {
        val oldTabs = _tabs.value
        val tabToRemove = oldTabs.find { it.id == tabId }
        if (tabToRemove != null && !tabToRemove.isPrivate) {
            _closedTabs.add(tabToRemove)
        }
        if (oldTabs.size <= 1) {
            val isPrivate = oldTabs.firstOrNull()?.isPrivate ?: false
            val newTab = BrowserTab(
                url = "",
                title = "New Tab",
                isPrivate = isPrivate
            )
            _tabs.value = listOf(newTab)
            _activeTabId.value = newTab.id
            
            if (newTab.url.isNotEmpty()) {
                _navigationActions.value = _navigationActions.value.toMutableMap().apply {
                    put(newTab.id, NavigationAction.LoadUrl(newTab.url))
                }
            }
            saveCurrentTabsToPreferences()
            return
        }

        val updatedTabs = oldTabs.filter { it.id != tabId }
        _tabs.value = updatedTabs

        if (_activeTabId.value == tabId) {
            _activeTabId.value = updatedTabs.last().id
        }
        clearDevToolsState(tabId)
        saveCurrentTabsToPreferences()
    }

    fun reopenClosedTab() {
        if (_closedTabs.isNotEmpty()) {
            val tabToRestore = _closedTabs.removeAt(_closedTabs.lastIndex)
            val newTabId = java.util.UUID.randomUUID().toString()
            val restoredTab = tabToRestore.copy(
                id = newTabId,
                isLoading = tabToRestore.url.isNotBlank() && tabToRestore.url != "about:blank",
                loadError = null
            )
            _tabs.value = _tabs.value + restoredTab
            _activeTabId.value = newTabId
            if (restoredTab.url.isNotBlank() && restoredTab.url != "about:blank") {
                _navigationActions.value = _navigationActions.value.toMutableMap().apply {
                    put(newTabId, NavigationAction.LoadUrl(restoredTab.url))
                }
            }
            saveCurrentTabsToPreferences()
        }
    }

    fun nextTab() {
        val currentTabs = _tabs.value
        if (currentTabs.size > 1) {
            val currentIndex = currentTabs.indexOfFirst { it.id == _activeTabId.value }
            if (currentIndex != -1) {
                val nextIndex = (currentIndex + 1) % currentTabs.size
                _activeTabId.value = currentTabs[nextIndex].id
                saveCurrentTabsToPreferences()
            }
        }
    }

    fun prevTab() {
        val currentTabs = _tabs.value
        if (currentTabs.size > 1) {
            val currentIndex = currentTabs.indexOfFirst { it.id == _activeTabId.value }
            if (currentIndex != -1) {
                val prevIndex = if (currentIndex - 1 < 0) currentTabs.size - 1 else currentIndex - 1
                _activeTabId.value = currentTabs[prevIndex].id
                saveCurrentTabsToPreferences()
            }
        }
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        _showTabsDialog.value = false
        saveCurrentTabsToPreferences()
    }

    // Sync Web values back dynamically from WebView updates
    fun updateTabUrl(tabId: String, url: String) {
        if (url.isNotBlank() && url != "about:blank") {
            pushTabHistoryUrl(tabId, url)
        }
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                if (!tab.isPrivate && url != "about:blank") {
                    settings.lastOpenedUrl = url
                }
                tab.copy(url = url)
            } else {
                tab
            }
        }
        saveCurrentTabsToPreferences()
    }

    fun updateTabTitle(tabId: String, title: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                val newTitle = when {
                    title.isNotBlank() && title != "about:blank" -> title
                    tab.url.isNotBlank() && !tab.url.startsWith("about:") -> {
                        try {
                            android.net.Uri.parse(tab.url).host ?: "Untitled"
                        } catch (e: Exception) { "Untitled" }
                    }
                    else -> tab.title.ifBlank { "Untitled" }
                }
                tab.copy(title = newTitle)
            } else tab
        }
        saveCurrentTabsToPreferences()
    }

    fun updateTabProgress(tabId: String, progress: Int) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(progress = progress) else it
        }
    }

    fun updateTabLoading(tabId: String, isLoading: Boolean) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(isLoading = isLoading) else it
        }
    }

    fun updateTabNavigation(tabId: String, canBack: Boolean, canForward: Boolean) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(canGoBack = canBack, canGoForward = canForward) else it
        }
    }

    fun updateTabError(tabId: String, errorDescription: String?) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(loadError = errorDescription) else it
        }
    }

    // Room DB History actions & Bookmark Actions
    fun handlePageLoadComplete(title: String, url: String, incognito: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!incognito) {
                repository.addHistory(title, url)
            }
        }
    }

    fun handleBlankPageDetected(url: String, context: android.content.Context) {
        viewModelScope.launch(Dispatchers.Main) {
            val host = try {
                android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: ""
            } catch (_: Exception) {
                ""
            }
            if (host.isEmpty()) return@launch
            
            val disabledDomains = _adBlockDisabledDomains.value
            val isHostBlocked = disabledDomains.contains(host) || disabledDomains.any { host.endsWith(".$it") }
            if (isHostBlocked) return@launch
            
            val currentMode = _adBlockMode.value
            if (currentMode == com.example.manager.AdBlockMode.AGGRESSIVE) {
                // Auto Fallback: Disable AdBlock for this site to recover
                setAdBlockDisabledForDomain(host, true)
                android.widget.Toast.makeText(context, "Anti-Blank: AdBlock dinonaktifkan otomatis untuk situs ini.", android.widget.Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    fun toggleBookmark(title: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.isBookmarked(url)) {
                repository.deleteBookmarkByUrl(url)
                Log.d(TAG, "Bookmark deleted securely")
            } else {
                repository.addBookmark(title, url)
                Log.d(TAG, "Bookmark added securely")
            }
        }
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteHistory(id)
        }
    }

    fun deleteBookmarkItem(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBookmark(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
        }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearBookmarks()
        }
    }

    // Toggles preferences and updates DB/SharedPreferences
    fun setAdBlockMode(mode: com.example.manager.AdBlockMode) {
        settings.adBlockMode = mode
        _adBlockMode.value = mode
        _adBlockEnabled.value = (mode != com.example.manager.AdBlockMode.OFF)
    }

    fun toggleAdBlock() {
        val newMode = when (_adBlockMode.value) {
            com.example.manager.AdBlockMode.OFF -> com.example.manager.AdBlockMode.BALANCED
            com.example.manager.AdBlockMode.BALANCED -> com.example.manager.AdBlockMode.OFF
            com.example.manager.AdBlockMode.AGGRESSIVE -> com.example.manager.AdBlockMode.OFF
        }
        setAdBlockMode(newMode)
    }

    // Legacy toggles mapping to modes
    fun setAdBlockEnabled(enabled: Boolean) {
        val newMode = if (enabled) com.example.manager.AdBlockMode.BALANCED else com.example.manager.AdBlockMode.OFF
        setAdBlockMode(newMode)
    }

    fun setAggressiveAdBlockEnabled(enabled: Boolean) {
        val newMode = if (enabled) com.example.manager.AdBlockMode.AGGRESSIVE else com.example.manager.AdBlockMode.BALANCED
        setAdBlockMode(newMode)
    }

    fun setTrackerBlockEnabled(enabled: Boolean) {
        settings.isTrackerBlockEnabled = enabled
        _trackerBlockEnabled.value = enabled
    }

    fun setPrivacyShieldMode(mode: com.example.manager.PrivacyShieldMode) {
        settings.privacyShieldMode = mode
        _privacyShieldMode.value = mode
    }

    fun setPreventWebRtcLeak(enabled: Boolean) {
        settings.preventWebRtcLeak = enabled
        _preventWebRtcLeak.value = enabled
    }

    fun setStrictWebRtcMode(enabled: Boolean) {
        settings.strictWebRtcMode = enabled
        _strictWebRtcMode.value = enabled
    }

    fun setClearCookiesOnExit(enabled: Boolean) {
        settings.clearCookiesOnExit = enabled
        _clearCookiesOnExit.value = enabled
    }

    fun incrementTrackerCookiesBlocked() {
        settings.incrementTrackerCookies()
        _trackerCookiesBlockedCount.value = settings.trackersCookiesBlockedCount
    }

    fun incrementFingerprintingBlocked() {
        settings.incrementFingerprinting()
        _fingerprintingBlockedCount.value = settings.fingerprintingBlockedCount
    }

    fun incrementThirdPartyCookiesBlocked() {
        settings.incrementThirdPartyCookies()
        _thirdPartyCookiesBlockedCount.value = settings.thirdPartyCookiesBlockedCount
    }

    fun emergencyPrivacyReset() {
        // Clear all tabs except a blank secure new tab
        val privTab = BrowserTab(url = "", title = "Private Tab", isPrivate = true)
        _tabs.value = listOf(privTab)
        _activeTabId.value = privTab.id
        _navigationActions.value = emptyMap()
        _closedTabs.clear()
        settings.clearPersistedTabs()
        
        // Clear intent to open
        _intentToOpen.value = null
        
        // Clear permissions for session
        _activePermissions.value = emptyMap()
        _devToolsStates.value = emptyMap()
        clearBlockedLogs()

        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
            downloadRepository.clearIncognito()
        }
        
        // Tell WebView to clear cache and cookies
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.w(TAG, "Cookie/WebStorage clear warning: ${e.message}")
        }
    }
    
    fun setOpenPopupsInNewTab(enabled: Boolean) {
        settings.openPopupsInNewTab = enabled
        _openPopupsInNewTab.value = enabled
    }

    fun setBlockSuspiciousRedirects(enabled: Boolean) {
        settings.blockSuspiciousRedirects = enabled
        _blockSuspiciousRedirects.value = enabled
    }

    fun setSearchEngine(name: String) {
        settings.selectedSearchEngine = name
        _selectedSearchEngine.value = name
    }

    fun setTheme(theme: AppTheme) {
        settings.appTheme = theme
        _appTheme.value = theme
    }

    fun setForceWebPageDarkMode(enabled: Boolean) {
        settings.forceWebPageDarkMode = enabled
        _forceWebPageDarkMode.value = enabled
    }

    fun setDisplayMode(mode: DisplayMode) {
        settings.displayMode = mode
        _displayMode.value = mode
    }

    fun setInspectToolsEnabled(enabled: Boolean) {
        settings.isInspectToolsEnabled = enabled
        _inspectToolsEnabled.value = enabled
    }

    fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        settings.isBackgroundPlaybackEnabled = enabled
        _backgroundPlaybackEnabled.value = enabled
    }

    fun setAllowScreenRecording(enabled: Boolean) {
        settings.allowScreenRecording = enabled
        _allowScreenRecording.value = enabled
    }

    fun setStreamingBoostEnabled(enabled: Boolean) {
        settings.isStreamingBoostEnabled = enabled
        _streamingBoostEnabled.value = enabled
    }
    
    fun setDownloadAcceleration(mode: String) {
        settings.downloadAcceleration = mode
        _downloadAcceleration.value = mode
    }
    
    fun setDataSaverEnabled(enabled: Boolean) {
        settings.isDataSaverEnabled = enabled
        _dataSaverEnabled.value = enabled
    }

    fun resetToDefaults() {
        setAdBlockEnabled(true)
        setTrackerBlockEnabled(true)
        setSearchEngine("Google")
        setTheme(AppTheme.SYSTEM)
        settings.clearAdBlockExceptions()
        _adBlockDisabledDomains.value = emptySet()
        val isTablet = getApplication<Application>().resources.configuration.smallestScreenWidthDp >= 600
        setDisplayMode(if (isTablet) DisplayMode.DESKTOP else DisplayMode.MOBILE)
    }

    // Clean browser cache, cookies, and local database storage
    fun clearCacheAndCookies(context: Context) {
        try {
            android.webkit.WebView(context).clearCache(true)
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            
            // Also reset/clear open tabs when clearing cache and cookies, as per user's request!
            settings.clearPersistedTabs()
            val defaultTab = BrowserTab(url = "", title = "New Tab", isPrivate = false)
            _tabs.value = listOf(defaultTab)
            _activeTabId.value = defaultTab.id
            _showTabsDialog.value = false
            
            Log.d(TAG, "Cache & cookies cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache & cookies", e)
        }
    }

    // Modal view triggers
    fun setSettingsVisible(visible: Boolean) { _showSettings.value = visible }
    fun setHistoryVisible(visible: Boolean) { _showHistory.value = visible }
    fun setBookmarksVisible(visible: Boolean) { _showBookmarks.value = visible }
    fun setTabsDialogVisible(visible: Boolean) { _showTabsDialog.value = visible }

    // ─── Orientation, Shortcut Bar, Custom JS Toolbar & Security Shield ───────

    private val _screenOrientationMode = MutableStateFlow(
        com.example.manager.CustomJsToolbarManager.getScreenOrientationMode(application)
    )
    val screenOrientationMode = _screenOrientationMode.asStateFlow()

    fun setScreenOrientationMode(mode: com.example.manager.ScreenOrientationMode) {
        com.example.manager.CustomJsToolbarManager.setScreenOrientationMode(getApplication(), mode)
        _screenOrientationMode.value = mode
    }

    fun cycleScreenOrientationMode(): com.example.manager.ScreenOrientationMode {
        val next = when (_screenOrientationMode.value) {
            com.example.manager.ScreenOrientationMode.AUTO_SENSOR -> com.example.manager.ScreenOrientationMode.LANDSCAPE
            com.example.manager.ScreenOrientationMode.LANDSCAPE -> com.example.manager.ScreenOrientationMode.PORTRAIT
            com.example.manager.ScreenOrientationMode.PORTRAIT -> com.example.manager.ScreenOrientationMode.AUTO_SENSOR
        }
        setScreenOrientationMode(next)
        return next
    }

    private val _shortcutBarVisible = MutableStateFlow(
        com.example.manager.CustomJsToolbarManager.getShortcutBarVisible(application)
    )
    val shortcutBarVisible = _shortcutBarVisible.asStateFlow()

    fun setShortcutBarVisible(visible: Boolean) {
        com.example.manager.CustomJsToolbarManager.setShortcutBarVisible(getApplication(), visible)
        _shortcutBarVisible.value = visible
    }

    private val _shortcutBarPosition = MutableStateFlow(
        com.example.manager.CustomJsToolbarManager.getShortcutBarPosition(application)
    )
    val shortcutBarPosition = _shortcutBarPosition.asStateFlow()

    fun setShortcutBarPosition(position: com.example.manager.ShortcutBarPosition) {
        com.example.manager.CustomJsToolbarManager.setShortcutBarPosition(getApplication(), position)
        _shortcutBarPosition.value = position
    }

    fun toggleShortcutBarPosition(): com.example.manager.ShortcutBarPosition {
        val next = if (_shortcutBarPosition.value == com.example.manager.ShortcutBarPosition.TOP) {
            com.example.manager.ShortcutBarPosition.BOTTOM
        } else {
            com.example.manager.ShortcutBarPosition.TOP
        }
        setShortcutBarPosition(next)
        return next
    }

    private val _enabledBuiltInShortcuts = MutableStateFlow(
        com.example.manager.CustomJsToolbarManager.getEnabledBuiltInShortcuts(application)
    )
    val enabledBuiltInShortcuts = _enabledBuiltInShortcuts.asStateFlow()

    fun toggleBuiltInShortcut(shortcutId: String) {
        val current = _enabledBuiltInShortcuts.value.toMutableSet()
        if (current.contains(shortcutId)) {
            current.remove(shortcutId)
        } else {
            current.add(shortcutId)
        }
        com.example.manager.CustomJsToolbarManager.setEnabledBuiltInShortcuts(getApplication(), current)
        _enabledBuiltInShortcuts.value = current
    }

    private val _customJsScripts = MutableStateFlow(
        com.example.manager.CustomJsToolbarManager.loadScripts(application)
    )
    val customJsScripts = _customJsScripts.asStateFlow()

    private val _showCustomJsStudio = MutableStateFlow(false)
    val showCustomJsStudio = _showCustomJsStudio.asStateFlow()

    fun setCustomJsStudioVisible(visible: Boolean) {
        _showCustomJsStudio.value = visible
    }

    fun saveCustomJsScript(script: com.example.manager.CustomToolbarScript) {
        val current = _customJsScripts.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == script.id }
        if (existingIdx >= 0) {
            current[existingIdx] = script
        } else {
            current.add(0, script)
        }
        com.example.manager.CustomJsToolbarManager.saveScripts(getApplication(), current)
        _customJsScripts.value = current
    }

    fun deleteCustomJsScript(scriptId: String) {
        val updated = _customJsScripts.value.filterNot { it.id == scriptId }
        com.example.manager.CustomJsToolbarManager.saveScripts(getApplication(), updated)
        _customJsScripts.value = updated
    }

    fun toggleCustomJsScriptEnabled(scriptId: String) {
        val updated = _customJsScripts.value.map {
            if (it.id == scriptId) it.copy(isEnabled = !it.isEnabled) else it
        }
        com.example.manager.CustomJsToolbarManager.saveScripts(getApplication(), updated)
        _customJsScripts.value = updated
    }

    fun restoreDefaultJsTemplates() {
        val current = _customJsScripts.value.toMutableList()
        val existingIds = current.map { it.id }.toSet()
        com.example.manager.CustomJsToolbarManager.TEMPLATE_GALLERY.forEach { tpl ->
            if (tpl.id !in existingIds) {
                current.add(tpl.copy(isEnabled = true))
            }
        }
        com.example.manager.CustomJsToolbarManager.saveScripts(getApplication(), current)
        _customJsScripts.value = current
    }

    fun exportCustomJsScriptsToJson(): String {
        return com.example.manager.CustomJsToolbarManager.serializeScriptsToJson(_customJsScripts.value)
    }

    fun importCustomJsScriptsFromJson(jsonStr: String, mergeWithExisting: Boolean = true): Int {
        val imported = com.example.manager.CustomJsToolbarManager.parseScriptsFromJson(jsonStr)
        if (imported.isEmpty()) return 0
        val updated = if (mergeWithExisting) {
            val map = _customJsScripts.value.associateBy { it.id }.toMutableMap()
            imported.forEach { map[it.id] = it }
            map.values.toList()
        } else {
            imported
        }
        com.example.manager.CustomJsToolbarManager.saveScripts(getApplication(), updated)
        _customJsScripts.value = updated
        return imported.size
    }

    // ─── Multi-Layer Security Guard States ────────────────────────────────────

    private val _httpsOnlyMode = MutableStateFlow(
        com.example.manager.SecurityGuardEngine.isHttpsOnlyEnabled(application)
    )
    val httpsOnlyMode = _httpsOnlyMode.asStateFlow()

    fun setHttpsOnlyMode(enabled: Boolean) {
        com.example.manager.SecurityGuardEngine.setHttpsOnlyEnabled(getApplication(), enabled)
        _httpsOnlyMode.value = enabled
    }

    private val _phishingProtectionEnabled = MutableStateFlow(
        com.example.manager.SecurityGuardEngine.isPhishingProtectionEnabled(application)
    )
    val phishingProtectionEnabled = _phishingProtectionEnabled.asStateFlow()

    fun setPhishingProtectionEnabled(enabled: Boolean) {
        com.example.manager.SecurityGuardEngine.setPhishingProtectionEnabled(getApplication(), enabled)
        _phishingProtectionEnabled.value = enabled
    }

    private val _blockCameraMicStrict = MutableStateFlow(
        com.example.manager.SecurityGuardEngine.isBlockCameraMicStrict(application)
    )
    val blockCameraMicStrict = _blockCameraMicStrict.asStateFlow()

    fun setBlockCameraMicStrict(enabled: Boolean) {
        com.example.manager.SecurityGuardEngine.setBlockCameraMicStrict(getApplication(), enabled)
        _blockCameraMicStrict.value = enabled
    }

    private val _blockClipboardHijack = MutableStateFlow(
        com.example.manager.SecurityGuardEngine.isBlockClipboardHijack(application)
    )
    val blockClipboardHijack = _blockClipboardHijack.asStateFlow()

    fun setBlockClipboardHijack(enabled: Boolean) {
        com.example.manager.SecurityGuardEngine.setBlockClipboardHijack(getApplication(), enabled)
        _blockClipboardHijack.value = enabled
    }

    private val _blockHiddenPopups = MutableStateFlow(
        com.example.manager.SecurityGuardEngine.isBlockHiddenPopups(application)
    )
    val blockHiddenPopups = _blockHiddenPopups.asStateFlow()

    fun setBlockHiddenPopups(enabled: Boolean) {
        com.example.manager.SecurityGuardEngine.setBlockHiddenPopups(getApplication(), enabled)
        _blockHiddenPopups.value = enabled
    }

    private val _tabSecurityThreats = MutableStateFlow<Map<String, com.example.manager.SecurityThreatAssessment>>(emptyMap())
    val tabSecurityThreats = _tabSecurityThreats.asStateFlow()

    fun reportTabSecurityThreat(tabId: String, threat: com.example.manager.SecurityThreatAssessment) {
        _tabSecurityThreats.value = _tabSecurityThreats.value + (tabId to threat)
    }

    fun clearTabSecurityThreat(tabId: String) {
        _tabSecurityThreats.value = _tabSecurityThreats.value - tabId
    }

    fun proceedDespiteSecurityThreat(tabId: String, threat: com.example.manager.SecurityThreatAssessment) {
        com.example.manager.SecurityGuardEngine.allowHostTemporarily(getApplication(), threat.host)
        _tabSecurityThreats.value = _tabSecurityThreats.value - tabId
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(url = threat.url, loadError = null, isLoading = true, progress = 15) else it
        }
        _navigationActions.value = _navigationActions.value.toMutableMap().apply {
            put(tabId, NavigationAction.LoadUrl(threat.url))
        }
    }

    // ─── Chrome Browser Extensions System (Built-in + External Custom Import) ───

    private val _extensions = MutableStateFlow(
        com.example.manager.BrowserExtensionManager.loadExtensions(application)
    )
    val extensions = _extensions.asStateFlow()

    private val _showExtensionsHub = MutableStateFlow(false)
    val showExtensionsHub = _showExtensionsHub.asStateFlow()

    fun setExtensionsHubVisible(visible: Boolean) {
        _showExtensionsHub.value = visible
    }

    fun toggleExtensionEnabled(extensionId: String) {
        val updated = _extensions.value.map {
            if (it.id == extensionId) it.copy(isEnabled = !it.isEnabled) else it
        }
        com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), updated)
        _extensions.value = updated
    }

    fun toggleExtensionPinned(extensionId: String) {
        val updated = _extensions.value.map {
            if (it.id == extensionId) it.copy(isPinnedToToolbar = !it.isPinnedToToolbar) else it
        }
        com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), updated)
        _extensions.value = updated
    }

    fun saveOrUpdateExtension(extension: com.example.manager.BrowserExtension) {
        val current = _extensions.value.toMutableList()
        val idx = current.indexOfFirst { it.id == extension.id }
        if (idx >= 0) {
            current[idx] = extension
        } else {
            current.add(0, extension)
        }
        com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), current)
        _extensions.value = current
    }

    fun deleteExtension(extensionId: String) {
        val updated = _extensions.value.filterNot { it.id == extensionId }
        com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), updated)
        _extensions.value = updated
    }

    fun importExtensionsFromUri(uri: android.net.Uri, displayName: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val imported = com.example.manager.BrowserExtensionManager.importExtensionFromUri(
                getApplication(),
                uri,
                displayName
            )
            if (imported.isNotEmpty()) {
                val map = _extensions.value.associateBy { it.id }.toMutableMap()
                imported.forEach { map[it.id] = it }
                val updated = map.values.toList()
                com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), updated)
                _extensions.value = updated
            }
            onResult(imported.size)
        }
    }

    fun importExtensionsFromUrl(urlStr: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val res = com.example.manager.BrowserExtensionManager.importExtensionFromUrl(urlStr)
            res.onSuccess { list ->
                val map = _extensions.value.associateBy { it.id }.toMutableMap()
                list.forEach { map[it.id] = it }
                val updated = map.values.toList()
                com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), updated)
                _extensions.value = updated
                onResult(Result.success(list.size))
            }.onFailure { err ->
                onResult(Result.failure(err))
            }
        }
    }

    fun importExtensionsFromRawText(rawText: String, fileName: String = "Custom_Extension.js"): Int {
        val imported = com.example.manager.BrowserExtensionManager.parseExtensionFromRawText(
            rawText = rawText,
            fileName = fileName,
            sourceType = com.example.manager.ExtensionSourceType.EXTERNAL_FILE,
            sourceUrl = ""
        )
        if (imported.isEmpty()) return 0
        val map = _extensions.value.associateBy { it.id }.toMutableMap()
        imported.forEach { map[it.id] = it }
        val updated = map.values.toList()
        com.example.manager.BrowserExtensionManager.saveExtensions(getApplication(), updated)
        _extensions.value = updated
        return imported.size
    }

    fun exportExtensionsToJson(onlyCustom: Boolean = false): String {
        val list = if (onlyCustom) {
            _extensions.value.filter { it.sourceType != com.example.manager.ExtensionSourceType.BUILT_IN }
        } else {
            _extensions.value
        }
        return com.example.manager.BrowserExtensionManager.serializeExtensionsJson(list)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            downloadRepository.clearIncognito()
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeAllPrivateTabs()
    }
}
