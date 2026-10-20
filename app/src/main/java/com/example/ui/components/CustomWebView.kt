package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.manager.DisplayMode
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.BrowserViewModel

private const val TAG = "CustomWebView"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CustomWebView(
    tab: BrowserTab,
    viewModel: BrowserViewModel,
    adBlockEnabled: Boolean,
    trackerBlockEnabled: Boolean,
    webViewPool: MutableMap<String, WebView>,
    onFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Unit,
    onFullscreenView: ((android.view.View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideFullscreenView: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!com.example.manager.FilterListManager.isLoaded()) {
            try {
                com.example.manager.FilterListManager.loadRules(context)
            } catch (e: Exception) {
                Log.w("AdBlock", "Failed to load rules on WebView creation: ${e.message}")
            }
        }
    }

    val displayMode by viewModel.displayMode.collectAsState()
    val desktopZoomScale by viewModel.desktopZoomScale.collectAsState()
    val streamingBoostEnabled by viewModel.streamingBoostEnabled.collectAsState()
    val dataSaverEnabled by viewModel.dataSaverEnabled.collectAsState()
    val navigationActions by viewModel.navigationActions.collectAsState()
    val isAppPaused by viewModel.isAppPaused.collectAsState()

    val privacyShieldMode by viewModel.privacyShieldMode.collectAsState()
    val preventWebRtcLeak by viewModel.preventWebRtcLeak.collectAsState()
    val strictWebRtcMode by viewModel.strictWebRtcMode.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val forceWebPageDarkMode by viewModel.forceWebPageDarkMode.collectAsState()
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDarkTheme = when (appTheme) {
        com.example.manager.AppTheme.SYSTEM -> isSystemDark
        com.example.manager.AppTheme.LIGHT -> false
        com.example.manager.AppTheme.DARK -> true
    }

    val devToolsStates by viewModel.devToolsStates.collectAsState()
    val devState = devToolsStates[tab.id]
    val inspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()

    val backgroundPlaybackEnabled by viewModel.backgroundPlaybackEnabled.collectAsState()

    LaunchedEffect(tab.id, inspectToolsEnabled) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        try {
            if (inspectToolsEnabled) {
                webView.addJavascriptInterface(
                    createDevToolsJsBridge(tab.id, viewModel),
                    "AdBlockDevTools"
                )
            } else {
                webView.removeJavascriptInterface("AdBlockDevTools")
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(devState?.isElementPickerActive) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        if (devState?.isElementPickerActive == true) {
            webView.evaluateJavascript(com.example.devtools.DevToolsScriptFactory.installElementPickerScript(), null)
            webView.evaluateJavascript("window.__ADB_DEVTOOLS_PICKER_ACTIVE__ = true;", null)
        } else {
            webView.evaluateJavascript("window.__ADB_DEVTOOLS_PICKER_ACTIVE__ = false;", null)
        }
    }

    val isElementZapperActive by viewModel.isElementZapperActive.collectAsState()
    val zapperRulesVersion by viewModel.zapperRulesVersion.collectAsState()

    LaunchedEffect(tab.id, isElementZapperActive) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        webView.evaluateJavascript(
            com.yourbrowser.adblock.AdBlockWebViewClient.buildElementZapperToggleScript(isElementZapperActive),
            null
        )
    }

    LaunchedEffect(tab.id, tab.url, zapperRulesVersion) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        val host = safeHost(tab.url)
        if (host.isNotBlank()) {
            val rules = viewModel.getCustomZapperRulesForHost(host)
            val css = if (rules.isEmpty()) "" else {
                rules.joinToString(",\n") + " { display:none !important; visibility:hidden !important; pointer-events:none !important; }"
            }
            val escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
            webView.evaluateJavascript("""
(function(){
  var s=window.__asZapperRef;
  if(!s||!s.isConnected){
    s=document.createElement('style');
    (document.head||document.documentElement).appendChild(s);
    try{Object.defineProperty(window,'__asZapperRef',{value:s,enumerable:false,configurable:true});}catch(e){}
  }
  s.textContent=`$escaped`;
})();
            """.trimIndent(), null)
        }
    }

    LaunchedEffect(tab.id, isAppPaused, backgroundPlaybackEnabled, tab.isPlayingAudio) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        if (isAppPaused && !backgroundPlaybackEnabled && !tab.isPlayingAudio) {
            webView.onPause()
        } else {
            webView.onResume()
        }
    }

    LaunchedEffect(tab.id, tab.isPrivate, privacyShieldMode) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        val shouldAllowThirdPartyCookies = !tab.isPrivate &&
            privacyShieldMode != com.example.manager.PrivacyShieldMode.STRICT
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, shouldAllowThirdPartyCookies)
    }

    LaunchedEffect(tab.id, tab.isMuted) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        val muteScript = """
            document.querySelectorAll('video, audio').forEach(function(media) {
                media.muted = ${tab.isMuted};
            });
        """.trimIndent()
        webView.evaluateJavascript(muteScript, null)
    }

    AndroidView(
        factory = { context ->
            com.example.manager.FrameMetricsTelemetrySync.validateRenderToken(context)
            webViewPool.getOrPut(tab.id) {
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    isFocusable = true
                    isFocusableInTouchMode = true

                    // Set standard DNT: Both Standard and Strict send DNT

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            offscreenPreRaster = false
                        }
                        
                        // Safety: Strict file sandbox access
                        allowFileAccess = false
                        allowContentAccess = false
                        
                        // Block Mixed Content (HTTP on HTTPS)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        
                        // Phishing and Malware Shield Safe Browsing (Android O+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            try {
                                safeBrowsingEnabled = true
                            } catch (e: Exception) {
                                Log.w(TAG, "Safe Browsing is not supported on this device")
                            }
                        }

                        if (viewModel.displayMode.value != DisplayMode.MOBILE) {
                            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                            textZoom = viewModel.desktopZoomScale.value.coerceIn(50, 200)
                        } else {
                            textZoom = 100
                        }
                    }

                    // For private/incognito tabs, disable third-party cookies for security isolation
                    // Also disable if in STRICT privacy mode
                    if (tab.isPrivate || privacyShieldMode == com.example.manager.PrivacyShieldMode.STRICT) {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    } else {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }

                    // Update properties that can change dynamically using update block
                    // For instance, Data Saver image loading and Cache Mode
                    settings.loadsImagesAutomatically = !dataSaverEnabled
                    if (dataSaverEnabled) {
                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    } else {
                        settings.cacheMode = if (tab.isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                    }
                    
                    val currentPageUrlRef = java.util.concurrent.atomic.AtomicReference<String>(tab.url)
                    val alexShieldV5Client = com.yourbrowser.adblock.AdBlockWebViewClient(
                        mode = com.yourbrowser.adblock.PrivacyMode.BALANCED,
                        onBlocked = { blockedUrl, reason ->
                            Log.d(TAG, "AlexShield v5 blocked: $reason | $blockedUrl")
                            if (reason.contains("tracker", ignoreCase = true) ||
                                reason.contains("beacon", ignoreCase = true) ||
                                blockedUrl.contains("analytics", ignoreCase = true) ||
                                blockedUrl.contains("pixel", ignoreCase = true)
                            ) {
                                viewModel.incrementTrackerCookiesBlocked()
                            }
                            if (tab.isPrivate || viewModel.privacyShieldMode.value == com.example.manager.PrivacyShieldMode.STRICT) {
                                viewModel.incrementThirdPartyCookiesBlocked()
                            }
                            val safePageUrl = currentPageUrlRef.get()
                            com.yourbrowser.adblock.AlexShieldV5LogStore.add(
                                tabId = tab.id,
                                pageUrl = safePageUrl,
                                blockedUrl = blockedUrl,
                                reason = reason
                            )
                        }
                    )

                    addJavascriptInterface(object {
                        @Volatile private var lastNewTabTimestamp = 0L
                        @Volatile private var lastTrapReportTimestamp = 0L

                        @android.webkit.JavascriptInterface
                        fun openUrlInNewTab(url: String) {
                            val trimmed = url.trim()
                            if (trimmed.isBlank() || trimmed.length > 2048) return
                            // Strict scheme allowlist: prevent javascript:, file:, content:, or intent: injection
                            val isHttpOrHttps = trimmed.startsWith("https://", ignoreCase = true) ||
                                trimmed.startsWith("http://", ignoreCase = true)
                            if (!isHttpOrHttps) return

                            val now = System.currentTimeMillis()
                            if (now - lastNewTabTimestamp < 1200L) return
                            if (viewModel.tabs.value.size >= 40) return
                            lastNewTabTimestamp = now

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.addNewTab(trimmed, tab.isPrivate)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun onAudioStateChanged(isPlaying: Boolean) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.updateTabPlayingAudio(tab.id, isPlaying)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun onStealthTrapDefused(kind: String) {
                            val now = System.currentTimeMillis()
                            if (now - lastTrapReportTimestamp < 300L) return
                            lastTrapReportTimestamp = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                alexShieldV5Client.recordStealthTrapDefused(1)
                                viewModel.recordStealthTrapDefused()
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun onElementZapperSelected(selector: String, tag: String) {
                            if (!viewModel.isElementZapperActive.value) return
                            val cleanSelector = sanitizeZapperSelector(selector)
                            val cleanTag = tag.trim().lowercase().take(32)
                            if (cleanSelector.isBlank()) return
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (viewModel.isElementZapperActive.value) {
                                    viewModel.onZapperElementSelected(cleanSelector, cleanTag)
                                }
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun setImmersiveFullscreen(enabled: Boolean) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.setImmersivePageFullscreen(enabled)
                            }
                        }
                        
                        @android.webkit.JavascriptInterface
                        fun onWebRtcBlocked(url: String) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context,
                                    "Koneksi WebRTC diblokir oleh Mode Privasi Ketat. Ubah WebRTC ke 'Standar' di Pengaturan jika panggilan video gagal.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }, "AlexBrowserNative")

                    // Only attach DevTools JS Bridge when Inspect Tools is explicitly enabled by user
                    if (viewModel.inspectToolsEnabled.value) {
                        addJavascriptInterface(
                            createDevToolsJsBridge(tab.id, viewModel),
                            "AdBlockDevTools"
                        )
                    }
                    
                    webViewClient = object : WebViewClient() {
                        @Volatile private var lastAlexShieldMode: com.yourbrowser.adblock.PrivacyMode? = null
                        @Volatile private var lastAlexShieldWhitelist: Set<String> = emptySet()

                        @Synchronized
                        private fun syncAlexShieldV5State(syncZapperRules: Boolean = false) {
                            val nextMode = when (viewModel.adBlockMode.value) {
                                com.example.manager.AdBlockMode.OFF -> com.yourbrowser.adblock.PrivacyMode.OFF
                                com.example.manager.AdBlockMode.BALANCED -> com.yourbrowser.adblock.PrivacyMode.BALANCED
                                com.example.manager.AdBlockMode.AGGRESSIVE -> com.yourbrowser.adblock.PrivacyMode.AGGRESSIVE
                            }

                            if (nextMode != lastAlexShieldMode) {
                                alexShieldV5Client.setMode(nextMode)
                                lastAlexShieldMode = nextMode
                            }

                            val nextWhitelist = viewModel.adBlockDisabledDomains.value
                                .mapNotNull { domain ->
                                    domain.trim().lowercase().removePrefix("www.").takeIf { it.isNotBlank() }
                                }
                                .toSet()

                            if (nextWhitelist != lastAlexShieldWhitelist) {
                                alexShieldV5Client.setWhitelist(nextWhitelist)
                                lastAlexShieldWhitelist = nextWhitelist
                            }

                            if (syncZapperRules) {
                                val currentHost = safeHost(currentPageUrlRef.get())
                                if (currentHost.isNotBlank()) {
                                    val zapperRules = viewModel.getCustomZapperRulesForHost(currentHost).toSet()
                                    alexShieldV5Client.setCustomZapperSelectors(currentHost, zapperRules)
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            currentPageUrlRef.set(url.orEmpty())
                            syncAlexShieldV5State(syncZapperRules = true)
                            if (view != null && !url.isNullOrBlank()) {
                                alexShieldV5Client.onPageStarted(view, url, favicon)
                                com.example.manager.SecurityGuardEngine.injectClientSecurityScript(
                                    webView = view,
                                    blockClipboardHijack = viewModel.blockClipboardHijack.value,
                                    blockHiddenPopups = viewModel.blockHiddenPopups.value
                                )
                                com.example.manager.BrowserExtensionManager.injectMatchingExtensions(
                                    webView = view,
                                    url = url,
                                    extensions = viewModel.extensions.value,
                                    stage = com.example.manager.ExtensionRunAt.DOCUMENT_START
                                )
                            }
                            viewModel.clearTabActivePermissions(tab.id)
                            viewModel.updateTabPlayingAudio(tab.id, false)
                            viewModel.updateTabLoading(tab.id, true)
                            viewModel.updateTabError(tab.id, null)
                            viewModel.updateTabNavigation(
                                tabId = tab.id,
                                canBack = view?.canGoBack() ?: false,
                                canForward = view?.canGoForward() ?: false
                            )
                            if (!url.isNullOrBlank() && url != "about:blank") {
                                viewModel.updateTabUrl(tab.id, url)
                                if (viewModel.inspectToolsEnabled.value) {
                                    val navLog = com.example.devtools.ConsoleEntry(
                                        id = java.util.UUID.randomUUID().toString(),
                                        timestamp = System.currentTimeMillis(),
                                        level = com.example.devtools.ConsoleLevel.INFO,
                                        message = "--- Navigated to $url ---"
                                    )
                                    viewModel.addConsoleEntry(tab.id, navLog)
                                    viewModel.refreshSecuritySnapshot(tab.id, url, tab.isPrivate)
                                }
                            }
                            
                            // WebRTC Leak Protection
                            val host = try { android.net.Uri.parse(url ?: "").host?.lowercase() ?: "" } catch (e: Exception) { "" }
                            val isGoogleOrDiscord = host.endsWith("google.com") || host.endsWith("google.co.id") || host.endsWith("discord.com") || host.endsWith("discordapp.com") || host.contains("google") || host.contains("discord") || host.contains("meet") || host.contains("zoom")
                            val isWhitelisted = viewModel.adBlockDisabledDomains.value.contains(host) || viewModel.adBlockDisabledDomains.value.any { host.endsWith(".$it") }

                            // WebRTC Leak Protection: Compatibility Mode (Filters private networks instead of breaking all RTCPeerConnection functions)
                            if (preventWebRtcLeak && !isWhitelisted) {
                                val script = if (strictWebRtcMode) {
                                    """
                                        (function() {
                                            if (window.RTCPeerConnection) {
                                                window.RTCPeerConnection = function() {
                                                    try {
                                                        if (window.AlexBrowserNative) {
                                                            window.AlexBrowserNative.onWebRtcBlocked(window.location.href);
                                                        }
                                                    } catch(e) {}
                                                    throw new Error("WebRTC connection blocked by AlexBrowser Strict Privacy Mode (Standard compatibility mode required for this page).");
                                                };
                                                window.webkitRTCPeerConnection = window.RTCPeerConnection;
                                            }
                                        })();
                                    """.trimIndent()
                                } else {
                                    """
                                        (function() {
                                            if (window.RTCPeerConnection) {
                                                const orig = window.RTCPeerConnection;
                                                window.RTCPeerConnection = function(config, constraints) {
                                                    const pc = new orig(config, constraints);
                                                    
                                                    const origSetLocalDescription = pc.setLocalDescription;
                                                    pc.setLocalDescription = function(desc) {
                                                        if (desc && desc.sdp) {
                                                            let sdp = desc.sdp;
                                                            sdp = sdp.replace(/a=candidate:\d+ \d+ \w+ \d+ (192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)/gi, 'a=candidate:0 1 UDP 0 0.0.0.0');
                                                            const lines = sdp.split('\n');
                                                            const filteredLines = lines.filter(line => {
                                                                if (line.trim().startsWith('a=candidate:')) {
                                                                    return !/(192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)/i.test(line);
                                                                }
                                                                return true;
                                                            });
                                                            desc.sdp = filteredLines.join('\n');
                                                        }
                                                        return origSetLocalDescription.call(this, desc);
                                                    };
    
                                                    const origAddIceCandidate = pc.addIceCandidate;
                                                    pc.addIceCandidate = function(candidate, successCallback, failureCallback) {
                                                        if (candidate && candidate.candidate) {
                                                            const candStr = candidate.candidate;
                                                            if (/(192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)/i.test(candStr)) {
                                                                if (successCallback) successCallback();
                                                                return Promise.resolve();
                                                            }
                                                        }
                                                        return origAddIceCandidate.call(this, candidate, successCallback, failureCallback);
                                                    };
    
                                                    return pc;
                                                };
                                                Object.setPrototypeOf(window.RTCPeerConnection, orig);
                                                window.RTCPeerConnection.prototype = orig.prototype;
                                            }
                                        })();
                                    """.trimIndent()
                                }
                                view?.evaluateJavascript(script, null)
                            }
                            
                            // Anti-Fingerprinting (Compatible / Selective Spoofing)
                            if (!isWhitelisted && !isGoogleOrDiscord) {
                                val standardAfp = """
                                    (function() {
                                        Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
                                        Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 4 });
                                        Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(standardAfp, null)
                                
                                // Strict: Canvas + Audio noise
                                if (privacyShieldMode == com.example.manager.PrivacyShieldMode.STRICT) {
                                    val strictAfp = """
                                        (function() {
                                            // Canvas noise
                                            const origGetContext = HTMLCanvasElement.prototype.getContext;
                                            HTMLCanvasElement.prototype.getContext = function(type, contextAttributes) {
                                                const ctx = origGetContext.call(this, type, contextAttributes);
                                                if (ctx && type === '2d') {
                                                    const origGetImageData = ctx.getImageData;
                                                    ctx.getImageData = function(x, y, w, h) {
                                                        const data = origGetImageData.call(this, x, y, w, h);
                                                        for (let i = 0; i < data.data.length; i += 4) {
                                                            data.data[i] = data.data[i] + (Math.random() * 2 - 1);
                                                        }
                                                        return data;
                                                    };
                                                }
                                                return ctx;
                                            };
                                            if (window.AudioContext || window.webkitAudioContext) {
                                                const ac = window.AudioContext || window.webkitAudioContext;
                                                ac.prototype.createOscillator = function() {
                                                    console.log('AudioContext fingerprint prevented');
                                                    return { connect: () => {}, start: () => {}, stop: () => {} };
                                                };
                                            }
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(strictAfp, null)
                                }
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            viewModel.updateTabNavigation(
                                tabId = tab.id,
                                canBack = view?.canGoBack() ?: false,
                                canForward = view?.canGoForward() ?: false
                            )
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            currentPageUrlRef.set(url.orEmpty())
                            viewModel.updateTabLoading(tab.id, false)
                            viewModel.updateTabProgress(tab.id, 100)
                            viewModel.updateTabNavigation(
                                tabId = tab.id,
                                canBack = view?.canGoBack() ?: false,
                                canForward = view?.canGoForward() ?: false
                            )
                            if (url.isNullOrBlank() || url == "about:blank") return

                            viewModel.updateTabUrl(tab.id, url)
                            val title = view?.title ?: ""
                            viewModel.updateTabTitle(tab.id, title)
                            viewModel.handlePageLoadComplete(title, url, tab.isPrivate)
                            
                            // Inject middle-click handler, media audio state tracker, and sync mute state if muted
                            view?.evaluateJavascript("""
                                (function() {
                                    if (!window.__middleClickInjected) {
                                        window.__middleClickInjected = true;
                                        document.addEventListener('auxclick', function(e) {
                                            if (e.button === 1) {
                                                let target = e.target.closest('a');
                                                if (target && target.href) {
                                                    e.preventDefault();
                                                    window.AlexBrowserNative.openUrlInNewTab(target.href);
                                                }
                                            }
                                        });
                                        function syncMediaState() {
                                            try {
                                                var playing = Array.from(document.querySelectorAll('video, audio')).some(function(m) {
                                                    return !m.paused && !m.ended && m.readyState > 2;
                                                });
                                                if (window.AlexBrowserNative && window.AlexBrowserNative.onAudioStateChanged) {
                                                    window.AlexBrowserNative.onAudioStateChanged(playing);
                                                }
                                            } catch(e) {}
                                        }
                                        document.addEventListener('play', syncMediaState, true);
                                        document.addEventListener('pause', syncMediaState, true);
                                        document.addEventListener('ended', syncMediaState, true);
                                    }
                                    if (${tab.isMuted}) {
                                        document.querySelectorAll('video, audio').forEach(function(media) {
                                            media.muted = true;
                                        });
                                    }
                                    var wantsDesktop = ${viewModel.displayMode.value != DisplayMode.MOBILE};
                                    var metaVp = document.querySelector('meta[name="viewport"]');
                                    if (wantsDesktop) {
                                        if (!metaVp) {
                                            metaVp = document.createElement('meta');
                                            metaVp.name = 'viewport';
                                            metaVp.setAttribute('data-created-desktop-vp', '1');
                                            (document.head || document.documentElement).appendChild(metaVp);
                                        } else if (!metaVp.hasAttribute('data-orig-vp')) {
                                            metaVp.setAttribute('data-orig-vp', metaVp.getAttribute('content') || '');
                                        }
                                        metaVp.setAttribute('content', 'width=1280, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes');
                                    } else if (metaVp) {
                                        if (metaVp.hasAttribute('data-created-desktop-vp')) {
                                            metaVp.remove();
                                        } else if (metaVp.hasAttribute('data-orig-vp')) {
                                            metaVp.setAttribute('content', metaVp.getAttribute('data-orig-vp') || 'width=device-width, initial-scale=1.0');
                                            metaVp.removeAttribute('data-orig-vp');
                                        }
                                    }
                                })();
                            """.trimIndent(), null)
                            
                            val disabledDomains = viewModel.adBlockDisabledDomains.value
                            val requestHost = safeHost(url)
                            val isAdBlockDisabledForSite = isHostInSet(requestHost, disabledDomains)
                            
                            val adBlockMode = viewModel.adBlockMode.value
                            if (adBlockMode != com.example.manager.AdBlockMode.OFF && !isAdBlockDisabledForSite && view != null) {
                                // v5 page cleanup: aggressive CSS, anti-popup, anti-beacon, safe YouTube cleanup.
                                alexShieldV5Client.onPageFinished(view, url)
                                com.example.manager.CosmeticFilterInjector.inject(view)
                                Log.d("AlexShield", alexShieldV5Client.getAggressiveMetrics().toString())
                            }

                            // Re-ensure client security shield, Auto-Run Custom JS Toolbar scripts & Browser Extensions
                            if (view != null) {
                                com.example.manager.SecurityGuardEngine.injectClientSecurityScript(
                                    webView = view,
                                    blockClipboardHijack = viewModel.blockClipboardHijack.value,
                                    blockHiddenPopups = viewModel.blockHiddenPopups.value
                                )
                                com.example.manager.CustomJsToolbarManager.runAutoScriptsForUrl(
                                    webView = view,
                                    url = url,
                                    scripts = viewModel.customJsScripts.value
                                )
                                com.example.manager.BrowserExtensionManager.injectMatchingExtensions(
                                    webView = view,
                                    url = url,
                                    extensions = viewModel.extensions.value,
                                    stage = com.example.manager.ExtensionRunAt.DOCUMENT_END
                                )
                            }

                            if (adBlockMode != com.example.manager.AdBlockMode.OFF && !isAdBlockDisabledForSite && view != null) {
                                // Anti-Blank Page Check for Aggressive modes
                                if (adBlockMode == com.example.manager.AdBlockMode.AGGRESSIVE) {
                                    val antiBlankJs = """
                                        (function() {
                                            try {
                                                let h = document.body ? document.body.offsetHeight : 0;
                                                let nodes = document.body ? document.body.querySelectorAll('*').length : 0;
                                                let txt = document.body ? document.body.innerText.trim().length : 0;
                                                // Identify common blank page characteristics
                                                if (h < 10 && nodes < 5 && txt < 5) {
                                                    // Give it a tiny bit of time in case it's a dynamic SPA
                                                    return "BLANK";
                                                }
                                                return "OK";
                                            } catch(e) { return "ERROR"; }
                                        })();
                                    """.trimIndent()
                                    
                                    view.postDelayed({
                                        view.evaluateJavascript(antiBlankJs) { result ->
                                            if (result == "\"BLANK\"") {
                                                viewModel.handleBlankPageDetected(url, view.context)
                                            }
                                        }
                                    }, 1500)
                                }
                            }
                        }

                        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            if (detail != null && detail.didCrash()) {
                                Log.e(TAG, "WebView crash detected! Recovering...")
                            } else {
                                Log.w(TAG, "WebView killed by OS (OOM)")
                            }
                            viewModel.updateTabError(tab.id, "Tab crashed. Refresh to restore.")
                            return true // Indicates we handled it, prevent app crash
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (view != null && request != null && error != null) {
                                alexShieldV5Client.onReceivedError(view, request, error)
                            }

                            super.onReceivedError(view, request, error)

                            if (request?.isForMainFrame == true) {
                                val description = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    error?.description?.toString()
                                } else {
                                    "Connection validation failed"
                                }
                                Log.e(TAG, "WebView error: $description for url [redacted]")
                                viewModel.updateTabError(tab.id, description ?: "Connection validation failed")
                                viewModel.updateTabLoading(tab.id, false)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            // High security: Do not bypass SSL errors.
                            val errorDesc = error?.toString() ?: "Security Error: Blocked Invalid SSL Certificate"
                            Log.e(TAG, "SSL Error blocked: $errorDesc")
                            handler?.cancel() // Cancel the request.
                            viewModel.updateTabError(tab.id, "Security Error: Blocked Invalid SSL Certificate")
                            viewModel.updateLastSslError(tab.id, errorDesc)
                        }

                        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O_MR1)
                        override fun onSafeBrowsingHit(
                            view: WebView?,
                            request: WebResourceRequest?,
                            threatType: Int,
                            callback: android.webkit.SafeBrowsingResponse?
                        ) {
                            // Malware/Phishing detected by Safe Browsing API
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                callback?.showInterstitial(true) // Show Safe Browsing warning
                            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                callback?.backToSafety(true)
                            }
                            val threatLabel = when(threatType) {
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "Malware"
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "Phishing / Social Engineering"
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "Unwanted Software"
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_BILLING -> "Billing Abuse"
                                else -> "Unknown General Threat"
                            }
                            Log.w(TAG, "Safe Browsing Blocked: $threatLabel")
                            viewModel.updateTabError(tab.id, "Security Warning: $threatLabel Detected by Safe Browsing!")
                            viewModel.updateLastSafeBrowsingThreat(tab.id, "$threatLabel (type ID: $threatType)")
                        }

                        // Adblock and tracker interceptors
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url?.toString()
                            
                            var entryId: String? = null
                            if (viewModel.inspectToolsEnabled.value && requestUrl != null) {
                                val sanitizedHeaders = com.example.devtools.DevToolsSanitizer.sanitizeHeaders(request.requestHeaders ?: emptyMap())
                                val resType = com.example.devtools.DevToolsResourceClassifier.classify(requestUrl)
                                entryId = viewModel.recordNetworkRequest(
                                    tabId = tab.id,
                                    url = requestUrl,
                                    method = request.method ?: "GET",
                                    isMainFrame = request.isForMainFrame,
                                    hasGesture = request.hasGesture(),
                                    headers = sanitizedHeaders,
                                    resourceType = resType
                                )
                            }

                            val latestTab = viewModel.tabs.value.find { it.id == tab.id }
                            val mainPageUrl = latestTab?.url ?: currentPageUrlRef.get()
                            alexShieldV5Client.updateCurrentPageUrl(mainPageUrl)

                            val currentMode = viewModel.adBlockMode.value
                            // Primary AlexShield Ultra v5/v6 request blocking & Stealth Surrogate engine.
                            syncAlexShieldV5State(syncZapperRules = false)
                            if (currentMode != com.example.manager.AdBlockMode.OFF && view != null && request != null) {
                                val v5Blocked = alexShieldV5Client.shouldInterceptRequest(view, request)
                                if (v5Blocked != null) {
                                    if (entryId != null) {
                                        viewModel.updateNetworkBlocked(tab.id, entryId, "Blocked by AdBlock (AlexShield)")
                                    }
                                    return v5Blocked
                                }

                                // Strict Fingerprinting Script Protection (returns 200 OK JS surrogate to prevent onerror detection)
                                val pageHost = safeHost(mainPageUrl)
                                val isPageWhitelisted = isHostInSet(pageHost, lastAlexShieldWhitelist)
                                if (!isPageWhitelisted &&
                                    viewModel.privacyShieldMode.value == com.example.manager.PrivacyShieldMode.STRICT &&
                                    requestUrl != null &&
                                    (requestUrl.contains("fingerprint2") || requestUrl.contains("/fp.js") || requestUrl.contains("clientjs"))
                                ) {
                                    viewModel.incrementFingerprintingBlocked()
                                    if (entryId != null) {
                                        viewModel.updateNetworkBlocked(tab.id, entryId, "Blocked by Strict Privacy Shield (Fingerprinting protection)")
                                    }
                                    return alexShieldV5Client.emptyResponse(request.url, "application/javascript")
                                }
                            }

                            if (entryId != null) {
                                viewModel.updateNetworkAllowed(tab.id, entryId, statusCode = 200, mimeType = null)
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        // Prevent arbitrary app-deep-linking exploits to hijack native flows, and block ad redirects
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val hasGesture = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) request.hasGesture() else true

                            // 1. Block dangerous internal/executable schemes (file://, content://, javascript:, data:, etc.)
                            if (com.example.manager.SecurityGuardEngine.isDangerousInternalScheme(url)) {
                                Log.w(TAG, "Blocked dangerous internal scheme navigation: $url")
                                return true
                            }

                            // 2. Validate external app schemes & Intent redirects
                            if (com.example.manager.SecurityGuardEngine.isAllowedExternalScheme(url)) {
                                if (!hasGesture && (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value)) {
                                    Log.w(TAG, "Blocked unprompted external intent without user gesture: $url")
                                    return true
                                }
                                viewModel.requestOpenIntent(url)
                                return true
                            }

                            // Block any other unknown non-HTTP(S) scheme
                            if (!url.startsWith("https://", ignoreCase = true) &&
                                !url.startsWith("http://", ignoreCase = true)
                            ) {
                                Log.w(TAG, "Blocked unsupported navigation scheme: $url")
                                return true
                            }
                            
                            // 3. Main-frame Security Guard (HTTPS-Only upgrade, Phishing detector, Ad Redirect blocker)
                            if (request.isForMainFrame) {
                                val targetHost = com.example.manager.SecurityGuardEngine.extractHost(url)
                                val isTrustedHost = com.example.manager.SecurityGuardEngine.isHostUserTrusted(view?.context ?: context, targetHost)

                                // Auto-upgrade HTTP to HTTPS when HTTPS-Only is enabled
                                if (viewModel.httpsOnlyMode.value && !isTrustedHost && url.startsWith("http://", ignoreCase = true)) {
                                    val upgraded = com.example.manager.SecurityGuardEngine.upgradeUrlIfHttpsOnly(url, true)
                                    if (upgraded != url) {
                                        view?.loadUrl(upgraded, mapOf("DNT" to "1"))
                                        return true
                                    }
                                }

                                // Real-time Phishing, Homograph (xn--), Malware & Insecure HTTP check
                                val threat = com.example.manager.SecurityGuardEngine.inspectUrlThreat(
                                    url = url,
                                    httpsOnlyEnabled = viewModel.httpsOnlyMode.value,
                                    phishingProtectionEnabled = viewModel.phishingProtectionEnabled.value,
                                    isUserTrustedHost = isTrustedHost
                                )
                                if (threat.severity != com.example.manager.ThreatSeverity.SAFE) {
                                    view?.stopLoading()
                                    viewModel.reportTabSecurityThreat(tab.id, threat)
                                    return true
                                }

                                val latestTab = viewModel.tabs.value.find { it.id == tab.id }
                                val mainPageUrl = latestTab?.url ?: ""
                                val currentMode = viewModel.adBlockMode.value
                                
                                if (currentMode != com.example.manager.AdBlockMode.OFF && (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value)) {
                                    syncAlexShieldV5State()
                                    if (alexShieldV5Client.shouldBlockNavigationRedirect(url, mainPageUrl, hasGesture)) {
                                        Log.w(TAG, "Blocked Ad Redirect: $url")
                                        return true // Drop navigation entirely instead of showing empty page
                                    }
                                }
                            }
                            
                            return false
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            if (view == null) return false

                            // Anti-scam: block popups without user gesture
                            if (!isUserGesture && (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value)) {
                                Log.w(TAG, "Blocked popup without user gesture")
                                return false
                            }

                            val handled = java.util.concurrent.atomic.AtomicBoolean(false)
                            val newWebView = WebView(view.context)
                            newWebView.webViewClient = object : WebViewClient() {
                                private fun openAndDestroy(targetView: WebView?, url: String?): Boolean {
                                    if (!url.isNullOrBlank() && url != "about:blank" && handled.compareAndSet(false, true)) {
                                        val trimmedUrl = url.trim()
                                        if (com.example.manager.SecurityGuardEngine.isAllowedExternalScheme(trimmedUrl)) {
                                            if (isUserGesture) {
                                                viewModel.requestOpenIntent(trimmedUrl)
                                            }
                                        } else if (trimmedUrl.startsWith("https://", ignoreCase = true) ||
                                            trimmedUrl.startsWith("http://", ignoreCase = true)
                                        ) {
                                            if (viewModel.openPopupsInNewTab.value) {
                                                viewModel.addNewTab(trimmedUrl, isPrivate = tab.isPrivate)
                                            } else {
                                                // Keep navigation in the same tab so Back button history works step-by-step!
                                                view.loadUrl(trimmedUrl, mapOf("DNT" to "1"))
                                            }
                                        } else {
                                            Log.w(TAG, "Blocked unsafe popup scheme: $trimmedUrl")
                                        }
                                        targetView?.stopLoading()
                                        targetView?.destroy()
                                        return true
                                    }
                                    return false
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return openAndDestroy(view, request?.url?.toString())
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    openAndDestroy(view, url)
                                }
                            }

                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = newWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                            newWebView.destroy()
                            return false
                        }

                        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                            if (request == null) return
                            val origin = request.origin.toString()

                            // Strict Camera & Microphone Blocker
                            if (viewModel.blockCameraMicStrict.value &&
                                (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) ||
                                    request.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            ) {
                                request.deny()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Akses Kamera/Mikrofon dari $origin diblokir oleh Perisai Keamanan",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return
                            }
                            
                            val types = mutableListOf<com.example.manager.SitePermissionType>()
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) types.add(com.example.manager.SitePermissionType.CAMERA)
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) types.add(com.example.manager.SitePermissionType.MICROPHONE)
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) types.add(com.example.manager.SitePermissionType.PROTECTED_MEDIA)
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX)) types.add(com.example.manager.SitePermissionType.MIDI)

                            if (types.isEmpty()) {
                                request.deny()
                                return
                            }

                            viewModel.requestSitePermission(origin, types, tab.isPrivate) { granted ->
                                val resToGrant = mutableListOf<String>()
                                if (granted.contains(com.example.manager.SitePermissionType.CAMERA)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                                if (granted.contains(com.example.manager.SitePermissionType.MICROPHONE)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                if (granted.contains(com.example.manager.SitePermissionType.PROTECTED_MEDIA)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                                if (granted.contains(com.example.manager.SitePermissionType.MIDI)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX)

                                if (resToGrant.isNotEmpty()) {
                                    request.grant(resToGrant.toTypedArray())
                                    resToGrant.forEach { res ->
                                        if (res == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) viewModel.addActivePermission(tab.id, com.example.manager.SitePermissionType.CAMERA)
                                        if (res == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) viewModel.addActivePermission(tab.id, com.example.manager.SitePermissionType.MICROPHONE)
                                    }
                                } else {
                                    request.deny()
                                }
                            }
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: android.webkit.GeolocationPermissions.Callback?
                        ) {
                            if (origin == null || callback == null) return
                            
                            viewModel.requestSitePermission(origin, listOf(com.example.manager.SitePermissionType.LOCATION), tab.isPrivate) { granted ->
                                if (granted.contains(com.example.manager.SitePermissionType.LOCATION)) {
                                    callback.invoke(origin, true, false)
                                    viewModel.addActivePermission(tab.id, com.example.manager.SitePermissionType.LOCATION)
                                } else {
                                    callback.invoke(origin, false, false)
                                }
                            }
                        }

                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            if (viewModel.inspectToolsEnabled.value) {
                                consoleMessage?.let {
                                    val level = when (it.messageLevel()) {
                                        android.webkit.ConsoleMessage.MessageLevel.LOG -> com.example.devtools.ConsoleLevel.LOG
                                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> com.example.devtools.ConsoleLevel.ERROR
                                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> com.example.devtools.ConsoleLevel.WARNING
                                        android.webkit.ConsoleMessage.MessageLevel.TIP -> com.example.devtools.ConsoleLevel.INFO
                                        else -> com.example.devtools.ConsoleLevel.INFO
                                    }
                                    viewModel.addConsoleEntry(tab.id, com.example.devtools.ConsoleEntry(
                                        id = java.util.UUID.randomUUID().toString(),
                                        timestamp = System.currentTimeMillis(),
                                        level = level,
                                        message = it.message(),
                                        lineNumber = it.lineNumber(),
                                        sourceId = it.sourceId()
                                    ))
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }

                        private var lastReportedProgress = 0

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (newProgress == 100 || newProgress == 0 || kotlin.math.abs(newProgress - lastReportedProgress) >= 5) {
                                lastReportedProgress = newProgress
                                viewModel.updateTabProgress(tab.id, newProgress)
                            }
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            if (!title.isNullOrBlank() && title != "about:blank") {
                                viewModel.updateTabTitle(tab.id, title)
                            }
                        }

                        override fun onShowFileChooser(
                            customWebView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            onFileChooser(filePathCallback, fileChooserParams)
                            return true
                        }

                        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                            if (view != null && callback != null) {
                                viewModel.setFullscreen(true)
                                onFullscreenView?.invoke(view, callback)
                            } else {
                                super.onShowCustomView(view, callback)
                            }
                        }

                        override fun onHideCustomView() {
                            viewModel.setFullscreen(false)
                            onHideFullscreenView?.invoke()
                            super.onHideCustomView()
                        }
                    }

                    // Setup fast internet download listener
                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                        viewModel.downloadManager.startDownload(
                            url = url,
                            userAgent = userAgent,
                            fileName = fileName,
                            mimeType = mimetype,
                            domain = safeHost(url),
                            isIncognito = tab.isPrivate
                        )
                        android.widget.Toast.makeText(context, "Downloading...", android.widget.Toast.LENGTH_SHORT).show()
                    }

                    setOnCreateContextMenuListener { menu, _, _ ->
                        val hitResult = this.hitTestResult
                        val hitUrl = hitResult.extra
                        
                        menu.setHeaderTitle(if (!hitUrl.isNullOrEmpty()) hitUrl else "Actions")
                        
                        // Standard Navigation
                        menu.add("Back").setOnMenuItemClickListener {
                            if (this.canGoBack()) this.goBack()
                            true
                        }
                        menu.add("Forward").setOnMenuItemClickListener {
                            if (this.canGoForward()) this.goForward()
                            true
                        }
                        menu.add("Reload").setOnMenuItemClickListener {
                            this.reload()
                            true
                        }
                        
                        // Link specific actions
                        if (hitResult.type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE || hitResult.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                            if (!hitUrl.isNullOrEmpty()) {
                                menu.add("Copy link").setOnMenuItemClickListener {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", hitUrl))
                                    android.widget.Toast.makeText(context, "Link Copied", android.widget.Toast.LENGTH_SHORT).show()
                                    true
                                }
                                menu.add("Open link in new tab").setOnMenuItemClickListener {
                                    viewModel.addNewTab(hitUrl, isPrivate = tab.isPrivate)
                                    true
                                }
                            }
                        }
                        
                        // Image specific actions
                        if (hitResult.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE || hitResult.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                            if (!hitUrl.isNullOrEmpty()) {
                                menu.add("Save image").setOnMenuItemClickListener {
                                    val fileName = android.webkit.URLUtil.guessFileName(hitUrl, null, "image/*")
                                    viewModel.downloadManager.startDownload(
                                        url = hitUrl,
                                        userAgent = this.settings.userAgentString,
                                        fileName = fileName,
                                        mimeType = "image/*",
                                        domain = safeHost(hitUrl),
                                        isIncognito = tab.isPrivate
                                    )
                                    android.widget.Toast.makeText(context, "Downloading image...", android.widget.Toast.LENGTH_SHORT).show()
                                    true
                                }
                            }
                        }
                        
                        if (viewModel.inspectToolsEnabled.value) {
                            menu.add("Inspect element").setOnMenuItemClickListener {
                                viewModel.openDevTools(tab.id, com.example.devtools.DevToolsTab.ELEMENTS)
                                viewModel.setElementPickerActive(tab.id, true)
                                true
                            }
                        }
                    }

                    if (tab.url.isNotBlank() && tab.url != "about:blank" && navigationActions[tab.id] == null) {
                        val headers = mapOf("DNT" to "1")
                        loadUrl(tab.url, headers)
                    }
                }
            }.apply {
                // Detach from prior parent if reused
                (parent as? ViewGroup)?.removeView(this)
            }
        },
        update = { webView ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val expectedDark = if (forceWebPageDarkMode) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
                if (webView.settings.forceDark != expectedDark) {
                    webView.settings.forceDark = expectedDark
                }
            }

            val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            val wantsDesktop = displayMode != DisplayMode.MOBILE
            val isCurrentlyDesktop = webView.settings.userAgentString?.let {
                it.contains("Windows NT") || it.contains("X11; Linux x86_64")
            } == true

            val targetTextZoom = if (wantsDesktop) desktopZoomScale.coerceIn(50, 200) else 100
            if (webView.settings.textZoom != targetTextZoom) {
                webView.settings.textZoom = targetTextZoom
            }

            if (wantsDesktop && !isCurrentlyDesktop) {
                webView.settings.userAgentString = desktopUserAgent
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.builtInZoomControls = true
                webView.settings.displayZoomControls = false

                val currentUrl = webView.url
                if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
                    webView.reload()
                }
            } else if (!wantsDesktop && isCurrentlyDesktop) {
                webView.settings.userAgentString = null // Reset down to original mobile agent
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.textZoom = 100

                val currentUrl = webView.url
                if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
                    webView.reload()
                }
            }

            if (webView.settings.loadsImagesAutomatically != !dataSaverEnabled) {
                webView.settings.loadsImagesAutomatically = !dataSaverEnabled
                webView.settings.cacheMode = if (dataSaverEnabled) WebSettings.LOAD_CACHE_ELSE_NETWORK else if (tab.isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            }

            if (webView.url.isNullOrBlank() && tab.url.isNotBlank() && tab.url != "about:blank" && navigationActions[tab.id] == null) {
                val headers = mapOf("DNT" to "1")
                webView.loadUrl(tab.url, headers)
            }
        },
        modifier = modifier.fillMaxSize()
    )

    // Execute navigation commands (LoadUrl, GoBack, GoForward, Reload, StopLoading) strictly ONCE per actionId
    val currentNavAction = navigationActions[tab.id]
    LaunchedEffect(tab.id, currentNavAction?.actionId) {
        val action = currentNavAction ?: return@LaunchedEffect
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        when (action) {
            is BrowserViewModel.NavigationAction.LoadUrl -> {
                if (action.url.isNotBlank() && action.url != "about:blank") {
                    val headers = mapOf("DNT" to "1")
                    webView.loadUrl(action.url, headers)
                }
            }
            is BrowserViewModel.NavigationAction.GoBack -> {
                performSmartGoBack(webView, tab.id, viewModel)
            }
            is BrowserViewModel.NavigationAction.GoForward -> {
                if (webView.canGoForward()) {
                    webView.goForward()
                } else {
                    viewModel.updateTabLoading(tab.id, false)
                }
            }
            is BrowserViewModel.NavigationAction.Reload -> {
                val currentWebUrl = webView.url
                if (currentWebUrl.isNullOrBlank() || currentWebUrl == "about:blank") {
                    if (tab.url.isNotBlank() && tab.url != "about:blank") {
                        webView.loadUrl(tab.url, mapOf("DNT" to "1"))
                    } else {
                        viewModel.updateTabLoading(tab.id, false)
                    }
                } else {
                    webView.reload()
                }
            }
            is BrowserViewModel.NavigationAction.StopLoading -> {
                webView.stopLoading()
            }
        }
        viewModel.consumeNavigationAction(tab.id, action.actionId)
    }
}

private fun performSmartGoBack(webView: WebView, tabId: String, viewModel: BrowserViewModel) {
    val historyList = webView.copyBackForwardList()
    val currentIndex = historyList.currentIndex
    if (webView.canGoBack() && currentIndex > 0) {
        val currentUrl = historyList.currentItem?.url.orEmpty()
        val prevUrl = historyList.getItemAtIndex(currentIndex - 1)?.url.orEmpty()
        val isTrivialRedirect = isSamePageRedirect(prevUrl, currentUrl)
        if (isTrivialRedirect && currentIndex >= 2 && webView.canGoBackOrForward(-2)) {
            webView.goBackOrForward(-2)
        } else if (isTrivialRedirect && currentIndex == 1) {
            webView.clearHistory()
            viewModel.goHome()
        } else {
            webView.goBack()
        }
        viewModel.updateTabNavigation(
            tabId = tabId,
            canBack = webView.canGoBack(),
            canForward = webView.canGoForward()
        )
    } else {
        // When at the first web page in this tab, pressing Back returns cleanly to the New Tab HomeScreen
        webView.loadUrl("about:blank")
        webView.clearHistory()
        viewModel.goHome()
    }
}

private fun isSamePageRedirect(urlA: String, urlB: String): Boolean {
    if (urlA.isBlank() || urlB.isBlank()) return false
    if (urlA == urlB) return true
    return try {
        val uriA = Uri.parse(urlA)
        val uriB = Uri.parse(urlB)
        val hostA = uriA.host?.lowercase()?.removePrefix("www.") ?: return false
        val hostB = uriB.host?.lowercase()?.removePrefix("www.") ?: return false
        val pathA = (uriA.path ?: "").trimEnd('/')
        val pathB = (uriB.path ?: "").trimEnd('/')
        val queryA = uriA.query ?: ""
        val queryB = uriB.query ?: ""
        hostA == hostB && pathA == pathB && queryA == queryB
    } catch (_: Exception) {
        false
    }
}

private fun safeHost(url: String?): String {
    return try {
        android.net.Uri.parse(url ?: "").host?.lowercase()?.removePrefix("www.") ?: ""
    } catch (e: Exception) {
        ""
    }
}

private fun isHostInSet(host: String, domains: Set<String>): Boolean {
    if (host.isBlank()) return false
    return domains.any { domain ->
        val clean = domain.lowercase().removePrefix("www.")
        host == clean || host.endsWith(".$clean")
    }
}

private fun sanitizeZapperSelector(raw: String): String {
    val trimmed = raw.trim().take(160)
    if (trimmed.isEmpty()) return ""
    // Block CSS rule breakouts, imports, or HTML tags
    if (trimmed.contains('{') || trimmed.contains('}') || trimmed.contains(';') ||
        trimmed.contains('<') || trimmed.contains('>') || trimmed.contains('@') ||
        trimmed.contains('\n') || trimmed.contains('\r')
    ) {
        return ""
    }
    return trimmed
}

private fun createDevToolsJsBridge(
    tabId: String,
    viewModel: BrowserViewModel
): com.example.devtools.DevToolsJsBridge {
    return com.example.devtools.DevToolsJsBridge(
        onElementSelected = { selectedElement ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (viewModel.inspectToolsEnabled.value) {
                    viewModel.updateSelectedElement(tabId, selectedElement)
                    viewModel.openDevTools(tabId, com.example.devtools.DevToolsTab.ELEMENTS)
                    val infoLog = com.example.devtools.ConsoleEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        level = com.example.devtools.ConsoleLevel.INFO,
                        message = "Inspected: ${selectedElement.selector}"
                    )
                    viewModel.addConsoleEntry(tabId, infoLog)
                }
            }
        },
        onConsoleBridgeMessage = { entry ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (viewModel.inspectToolsEnabled.value) {
                    viewModel.addConsoleEntry(tabId, entry)
                }
            }
        }
    )
}

