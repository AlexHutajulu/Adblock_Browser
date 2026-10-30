package com.yourbrowser.adblock

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ─── Enums & Models ───────────────────────────────────────────────────────────

enum class PrivacyMode { OFF, BALANCED, AGGRESSIVE }

data class AggressiveMetrics(
    val blockedRequests: Int = 0,
    val hiddenElements: Int = 0,
    val ytAdsSkipped: Int = 0,
    val surrogatesSpoofed: Int = 0,
    val antiAdblockDefused: Int = 0
)

// ─── AdBlockWebViewClient ─────────────────────────────────────────────────────

class AdBlockWebViewClient(
    mode: PrivacyMode = PrivacyMode.BALANCED,
    private val onBlocked: ((blockedUrl: String, reason: String) -> Unit)? = null
) {
    private val currentMode            = AtomicReference(mode)
    private val trackerBlockingEnabled = AtomicBoolean(true)
    private val trackerActiveWhenOff   = AtomicBoolean(false)
    private val streamingBoostEnabled  = AtomicBoolean(false)
    private val pageUrl                = AtomicReference("")
    private val whitelisted            = CopyOnWriteArraySet<String>()
    private val customZapperRules      = ConcurrentHashMap<String, CopyOnWriteArraySet<String>>()
    private val blockedCount           = AtomicInteger(0)
    private val hiddenCount            = AtomicInteger(0)
    private val ytCount                = AtomicInteger(0)
    private val surrogateCount         = AtomicInteger(0)
    private val defusedTrapsCount      = AtomicInteger(0)

    // Simpan host halaman utama secara thread-safe (diupdate di onPageStarted / updateCurrentPageUrl)
    @Volatile
    var currentPageHost: String = ""

    private val cache = android.util.LruCache<String, String>(6_000)

    init {
        syncCleanAdEngineMode(mode)
    }

    private fun syncCleanAdEngineMode(m: PrivacyMode) {
        CleanAdEngine.currentMode = if (m == PrivacyMode.AGGRESSIVE) {
            AdblockMode.AGGRESSIVE
        } else {
            AdblockMode.BALANCED
        }
    }

    private fun evictCaches() {
        cache.evictAll()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setMode(m: PrivacyMode) {
        syncCleanAdEngineMode(m)
        if (currentMode.getAndSet(m) != m) {
            evictCaches()
        }
    }

    fun setTrackerBlockingEnabled(enabled: Boolean, activeWhenAdBlockOff: Boolean = true) {
        val prevEnabled = trackerBlockingEnabled.getAndSet(enabled)
        val prevActiveOff = trackerActiveWhenOff.getAndSet(activeWhenAdBlockOff)
        if (prevEnabled != enabled || prevActiveOff != activeWhenAdBlockOff) {
            evictCaches()
        }
    }

    fun isTrackerBlockingEnabled(): Boolean = trackerBlockingEnabled.get()

    fun setStreamingBoostEnabled(enabled: Boolean) {
        if (streamingBoostEnabled.getAndSet(enabled) != enabled) {
            evictCaches()
        }
    }

    fun isStreamingBoostEnabled(): Boolean = streamingBoostEnabled.get()

    fun updateCurrentPageUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            pageUrl.set(url)
            currentPageHost = safeHost(url)
        }
    }

    fun whitelistHost(host: String) { whitelisted.add(normalizeHost(host)); evictCaches() }
    fun clearWhitelist() { whitelisted.clear(); evictCaches() }
    fun setWhitelist(hosts: Set<String>) {
        val normalized = hosts.mapNotNull { h -> normalizeHost(h).takeIf { it.isNotEmpty() } }.toSet()
        whitelisted.clear()
        whitelisted.addAll(normalized)
        evictCaches()
    }

    fun setCustomZapperSelectors(host: String, selectors: Set<String>) {
        val norm = normalizeHost(host)
        if (norm.isEmpty()) return
        if (selectors.isEmpty()) {
            customZapperRules.remove(norm)
        } else {
            customZapperRules[norm] = CopyOnWriteArraySet(selectors)
        }
    }

    fun getCustomZapperSelectors(host: String): Set<String> {
        val norm = normalizeHost(host)
        return customZapperRules[norm]?.toSet() ?: emptySet()
    }

    fun recordStealthTrapDefused(count: Int = 1) {
        defusedTrapsCount.addAndGet(count)
    }

    fun getAggressiveMetrics() = AggressiveMetrics(
        blockedRequests = blockedCount.get(),
        hiddenElements = hiddenCount.get(),
        ytAdsSkipped = ytCount.get(),
        surrogatesSpoofed = surrogateCount.get(),
        antiAdblockDefused = defusedTrapsCount.get()
    )

    // ── Lifecycle passthroughs ─────────────────────────────────────────────────

    fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        updateCurrentPageUrl(url)
        if (url.isNullOrBlank() || currentMode.get() == PrivacyMode.OFF) return
        val host = safeHost(url)
        if (isWhitelistedHost(host) || isCriticalBypassDomain(host)) return
        view.evaluateJavascript(buildEarlyScript(host), null)
        // Lapisan : pustaka scriptlet + pemanggilan per-aturan
        // dari daftar filter (AbpRuleEngine), dan processor data YouTube.
        // SAFE dicek ulang di sini karena onPageStarted tidak melewati
        // shouldInterceptRequest.
        if (!isSafeDomain(host)) {
            if (scriptletLibraryJs == null) {
                scriptletLibraryJs = loadAssetJs(view, "adblock/scriptlet_library.js")
            }
            val library = scriptletLibraryJs.orEmpty()
            if (library.isNotBlank()) {
                view.evaluateJavascript(library, null)
                val ruleSet = com.example.manager.FilterListManager.getCachedRules()?.ruleSet?.scriptlets
                if (ruleSet != null) {
                    val invocation = com.example.manager.ScriptletEngine.buildInvocationJs(host, ruleSet)
                    if (invocation.isNotBlank()) view.evaluateJavascript(invocation, null)
                }
            }
        }
        // Injeksi youtube_guard.js harus berada di luar blok !isSafeDomain(host)
        // karena youtube.com berada di SAFE_DOMAINS, dan aktif di mode BALANCED maupun AGGRESSIVE.
        if (currentMode.get() != PrivacyMode.OFF && isYouTubeHost(host)) {
            if (youtubeGuardJs == null) {
                youtubeGuardJs = loadAssetJs(view, "adblock/youtube_guard.js")
            }
            val guard = youtubeGuardJs.orEmpty()
            if (guard.isNotBlank()) view.evaluateJavascript(guard, null)
        }
    }

    // Cache aset JS lapisan (dimuat sekali dari assets, lalu dipakai ulang).
    @Volatile private var scriptletLibraryJs: String? = null
    @Volatile private var youtubeGuardJs: String? = null

    private fun loadAssetJs(view: WebView, assetPath: String): String {
        return try {
            view.context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    fun onPageFinished(view: WebView, url: String?) {
        updateCurrentPageUrl(url)
        val mode = currentMode.get()
        if (url.isNullOrBlank() || mode == PrivacyMode.OFF) return
        val host = safeHost(url)
        if (isWhitelistedHost(host) || isCriticalBypassDomain(host)) return
        // Solusi T3, T5 & Optimasi Injeksi: Gabungkan seluruh CSS kosmetik & script proteksi DOM dalam 1 evaluasi JS
        injectUnifiedPageProtection(view, isAggressive = (mode == PrivacyMode.AGGRESSIVE))
    }

    // ── Solusi T3 & T5 & Optimasi Injeksi Terpadu ──────────────────────────────

    fun applyCosmeticFiltering(view: WebView, isAggressive: Boolean) {
        if (currentMode.get() == PrivacyMode.OFF && !isAggressive) return
        val host = currentPageHost.ifEmpty { safeHost(pageUrl.get()) }
        val domainCss = buildCosmeticCSS(host)
        if (domainCss.isNotBlank()) {
            injectCSS(view, domainCss)
        }
    }

    fun injectUnifiedPageProtection(view: WebView, isAggressive: Boolean) {
        val host = currentPageHost.ifEmpty { safeHost(pageUrl.get()) }
        val zapperCss = buildCustomZapperCSS(host)
        val pageScript = buildPageScript(host)
        val domainCss = buildCosmeticCSS(host)

        val cosmeticJsBlock = if (domainCss.isNotBlank()) {
            hiddenCount.incrementAndGet()
            val escapedCss = domainCss.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
            """
            var s = window.__asStyleRef;
            if (!s || !s.isConnected) {
                s = document.createElement('style');
                (document.head || document.documentElement).appendChild(s);
                try { Object.defineProperty(window, '__asStyleRef', { value: s, enumerable: false, configurable: true }); } catch(e) {}
            }
            s.textContent = `$escapedCss`;
            """.trimIndent()
        } else {
            ""
        }

        val zapperJsBlock = if (zapperCss.isNotBlank()) {
            val escapedZapper = zapperCss.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
            """
            var z = window.__asZapperRef;
            if (!z || !z.isConnected) {
                z = document.createElement('style');
                (document.head || document.documentElement).appendChild(z);
                try { Object.defineProperty(window, '__asZapperRef', { value: z, enumerable: false, configurable: true }); } catch(e) {}
            }
            z.textContent = `$escapedZapper`;
            """.trimIndent()
        } else {
            ""
        }

        val unifiedJs = """
            (function() {
                if (window.__alexShieldInjected) return;
                window.__alexShieldInjected = true;
                $cosmeticJsBlock
                $zapperJsBlock
                $pageScript
            })();
        """.trimIndent()

        view.evaluateJavascript(unifiedJs, null)
    }

    fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
        // passthrough — tidak ada action khusus
    }

    // ── Main intercept (Zero Allocation, Fast Fail, Native C++ Chromium Networking) ──

    fun createEmptyResponse(mimeType: String = "text/plain"): WebResourceResponse {
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(EMPTY_BYTES)
        )
    }

    fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        val mode = currentMode.get()
        syncCleanAdEngineMode(mode)
        val trackerEnabled = trackerBlockingEnabled.get()
        val trackerOnlyActive = (mode == PrivacyMode.OFF && trackerEnabled && trackerActiveWhenOff.get())
        if (mode == PrivacyMode.OFF && !trackerOnlyActive) return null
        if (request.isForMainFrame) return null

        val uri    = request.url ?: return null
        val rawUrl = uri.toString()
        val lower  = rawUrl.lowercase(Locale.US)
        val reqHost = uri.host?.lowercase(Locale.US) ?: return null
        val pgHost  = safeHost(pageUrl.get())
        val accept = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value?.lowercase(Locale.US)

        // Never block safe domains (CDN, auth, Google core, streaming infra, payment, captcha),
        // EXCEPT explicit YouTube/Google ad & ad-telemetry endpoints (/api/stats/ads, /pagead/, /ptracking, etc.)
        if (isSafeDomain(reqHost) && !isExplicitYouTubeAdRequest(reqHost, lower)) return null
        if (pgHost.isNotEmpty() && isWhitelistedHost(pgHost)) return null
        if (isWhitelistedHost(reqHost)) return null
        if (isCriticalBypassDomain(reqHost) || (pgHost.isNotEmpty() && isCriticalBypassDomain(pgHost))) return null

        val resType = com.example.manager.AbpRuleEngine.inferType(
            url = rawUrl,
            acceptHeader = accept,
            requestHeaders = request.requestHeaders,
            isForMainFrame = request.isForMainFrame
        )
        val isProxiedSameSiteImage = resType == com.example.manager.AbpRuleEngine.ResourceType.IMAGE &&
            com.example.manager.AbpRuleEngine.isSameSiteImageProxy(reqHost, pgHost, lower)
        val isFirstParty = (pgHost.isNotEmpty() &&
            com.example.manager.SiteDomainPolicy.isSameSite(reqHost, pgHost)) || isProxiedSameSiteImage

        val streamBoost = streamingBoostEnabled.get()
        val cacheKey = "${mode.ordinal}|$trackerEnabled|$streamBoost|$pgHost|$rawUrl"
        cache.get(cacheKey)?.let { cachedReason ->
            return if (cachedReason == "ALLOW") {
                null
            } else if (isFirstParty && cachedReason in FIRST_PARTY_EXEMPT_REASONS) {
                null
            } else {
                blockedCount.incrementAndGet()
                if (cachedReason == "yt-ad-endpoint") ytCount.incrementAndGet()
                onBlocked?.invoke(rawUrl, cachedReason)
                emptyResponse(uri, accept)
            }
        }

        var reason = checkRules(reqHost, pgHost, lower, mode, trackerEnabled, streamBoost, resType, isFirstParty)

        // Lapisan DNS AdGuard (hanya untuk third-party dan bila belum terblokir aturan lokal).
        // Non-blocking: bila belum ada di cache, prefetch berjalan di background.
        // Jangan timpa explicit allow rule (@@) atau memutus media stream / thumbnail konten di mode Balance.
        if (reason == null && !isFirstParty && mode != PrivacyMode.OFF) {
            val explicitlyAllowed = com.example.manager.FilterListManager.getCachedRules()
                ?.ruleSet?.matchesAllowRule(lower, pgHost, resType) == true
            val protectBalancedMedia = (mode == PrivacyMode.BALANCED || streamBoost) && resType.isMediaFamily
            val protectBalancedContentImage = mode == PrivacyMode.BALANCED &&
                resType == com.example.manager.AbpRuleEngine.ResourceType.IMAGE &&
                com.example.manager.AbpRuleEngine.isLikelyContentImagePath(lower)
            if (!explicitlyAllowed && !protectBalancedMedia && !protectBalancedContentImage &&
                AdGuardDnsFilter.isBlockedCached(reqHost)
            ) {
                reason = "adguard-dns"
            }
        }

        // First-party yang terkena aturan umum / heuristik: JANGAN kembalikan 200 kosong,
        // biarkan lewat (return null) agar bundle JS/CSS/gambar situs utama tidak rusak.
        if (reason != null && isFirstParty && reason in FIRST_PARTY_EXEMPT_REASONS) {
            reason = null
        }

        val isBlocked = reason != null
        val isFiltersLoaded = com.example.manager.FilterListManager.isLoaded()
        if (isBlocked || isFiltersLoaded) {
            cache.put(cacheKey, reason ?: "ALLOW")
        }

        return if (isBlocked) {
            blockedCount.incrementAndGet()
            if (reason == "yt-ad-endpoint") ytCount.incrementAndGet()
            onBlocked?.invoke(rawUrl, reason ?: "adblock")
            emptyResponse(uri, accept)
        } else null
    }

    // ── Navigation redirect check ─────────────────────────────────────────────

    fun shouldBlockNavigationRedirect(url: String, mainPageUrl: String, hasGesture: Boolean): Boolean {
        if (currentMode.get() == PrivacyMode.OFF) return false
        val host = safeHost(url)
        val mainHost = safeHost(mainPageUrl)
        if (isSafeDomain(host)) return false
        if (isWhitelistedHost(host)) return false
        if (isCriticalBypassDomain(host)) return false

        // Blok otomatis situs judol (judi online) meskipun ada gestur
        if (HeuristicsEngine.isGamblingUrl(url)) {
            blockedCount.incrementAndGet()
            onBlocked?.invoke(url, "judol-popup-blocked")
            return true
        }

        // Navigasi dalam situs yang sama dengan gestur user diizinkan
        val isSameSite = (host.isNotEmpty() && mainHost.isNotEmpty()) &&
            (host == mainHost || host.endsWith(".$mainHost") || mainHost.endsWith(".$host"))
        if (hasGesture && isSameSite) return false

        // Blok domain iklan yang dikenal walau ada gestur (scam/slot redirect)
        val isKnownAd = REDIRECT_BLOCK_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            AD_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            AGGRESSIVE_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            ULTRA_AGGRESSIVE_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            (com.example.manager.FilterListManager.getCachedRules()?.ruleSet?.isDomainBlocked(host) == true)

        if (isKnownAd) {
            blockedCount.incrementAndGet()
            onBlocked?.invoke(url, "popup-redirect")
            return true
        }

        if (hasGesture) return false // legitimate cross-site navigation with user gesture
        return false
    }

    // ─── Core rule check ──────────────────────────────────────────────────────

    internal fun isCleanStreamingPath(lowerUrl: String): Boolean {
        val cleanPath = com.example.manager.AbpRuleEngine.extractCleanPath(lowerUrl)
        if (cleanPath.isEmpty()) return false
        return cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".m3u") ||
            cleanPath.endsWith(".mpd") || cleanPath.endsWith(".ism") || cleanPath.endsWith(".isml") ||
            cleanPath.endsWith(".m4s") || cleanPath.endsWith(".ts") || cleanPath.endsWith(".m2ts") ||
            cleanPath.endsWith(".m4f") || cleanPath.endsWith(".cmfv") || cleanPath.endsWith(".cmfa") ||
            cleanPath.endsWith(".fmp4") || cleanPath.endsWith(".mp4") || cleanPath.endsWith(".webm") ||
            cleanPath.endsWith(".mkv") || cleanPath.endsWith(".m4v") || cleanPath.endsWith(".mov") ||
            cleanPath.endsWith(".mp3") || cleanPath.endsWith(".m4a") || cleanPath.endsWith(".aac") ||
            cleanPath.endsWith(".ogg") || cleanPath.endsWith(".oga") || cleanPath.endsWith(".opus") ||
            cleanPath.endsWith(".vtt") || cleanPath.endsWith(".srt") || cleanPath.endsWith(".ass") ||
            cleanPath.endsWith(".ssa") || cleanPath.endsWith(".ttml")
    }

    internal fun evaluateRequestDecision(
        rawUrl: String,
        pageHost: String,
        mode: PrivacyMode = currentMode.get(),
        trackerEnabled: Boolean = trackerBlockingEnabled.get(),
        streamBoost: Boolean = streamingBoostEnabled.get(),
        acceptHeader: String? = null,
        requestHeaders: Map<String, String>? = null,
        isForMainFrame: Boolean = false
    ): String? {
        if (isForMainFrame) return null
        val lower = rawUrl.trim().lowercase(Locale.US)
        val reqHost = com.example.manager.AbpRuleEngine.extractHost(lower)
        if (reqHost.isEmpty()) return null
        val pgHost = com.example.manager.AbpRuleEngine.cleanHost(pageHost)
        if (isSafeDomain(reqHost) && !isExplicitYouTubeAdRequest(reqHost, lower)) return null
        if (pgHost.isNotEmpty() && isWhitelistedHost(pgHost)) return null
        if (isWhitelistedHost(reqHost)) return null
        if (isCriticalBypassDomain(reqHost) || (pgHost.isNotEmpty() && isCriticalBypassDomain(pgHost))) return null

        val resType = com.example.manager.AbpRuleEngine.inferType(
            url = rawUrl,
            acceptHeader = acceptHeader,
            requestHeaders = requestHeaders,
            isForMainFrame = isForMainFrame
        )
        val isProxiedSameSiteImage = resType == com.example.manager.AbpRuleEngine.ResourceType.IMAGE &&
            com.example.manager.AbpRuleEngine.isSameSiteImageProxy(reqHost, pgHost, lower)
        val isFirstParty = (pgHost.isNotEmpty() &&
            com.example.manager.SiteDomainPolicy.isSameSite(reqHost, pgHost)) || isProxiedSameSiteImage
        var reason = checkRules(reqHost, pgHost, lower, mode, trackerEnabled, streamBoost, resType, isFirstParty)
        if (reason != null && isFirstParty && reason in FIRST_PARTY_EXEMPT_REASONS) {
            reason = null
        }
        return reason
    }

    internal fun sanitizeUrlForDiagnosticLog(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val host = com.example.manager.AbpRuleEngine.extractHost(trimmed)
        val path = com.example.manager.AbpRuleEngine.extractCleanPath(trimmed)
        val hasQuery = trimmed.contains('?')
        return if (hasQuery) "https://$host$path?[REDACTED]" else "https://$host$path"
    }

    internal fun isYouTubeHost(host: String): Boolean {
        val clean = normalizeHost(host)
        return clean == "youtube.com" || clean.endsWith(".youtube.com") || clean == "youtu.be"
    }

    internal fun isYouTubeOrGoogleAdHost(host: String): Boolean {
        val clean = normalizeHost(host)
        return isYouTubeHost(clean) ||
            clean == "google.com" || clean.endsWith(".google.com") ||
            clean == "google.co.id" || clean.endsWith(".google.co.id")
    }

    internal fun isExplicitYouTubeAdRequest(reqHost: String, lowerUrl: String): Boolean {
        if (!isYouTubeOrGoogleAdHost(reqHost)) return false
        val cleanPath = com.example.manager.AbpRuleEngine.extractCleanPath(lowerUrl)
        if (cleanPath.isEmpty()) return false
        if (cleanPath == "/youtubei/v1/player" ||
            cleanPath.startsWith("/videoplayback") ||
            cleanPath.startsWith("/youtubei/v1/next") ||
            cleanPath.startsWith("/youtubei/v1/browse") ||
            cleanPath.startsWith("/youtubei/v1/search") ||
            cleanPath.startsWith("/youtubei/v1/guide") ||
            cleanPath.startsWith("/s/player/") ||
            cleanPath.startsWith("/iframe_api")
        ) {
            return false
        }
        return YOUTUBE_EXPLICIT_AD_PATHS.any { lowerUrl.contains(it) }
    }

    private fun checkRules(
        reqHost: String,
        pgHost: String,
        lowerUrl: String,
        mode: PrivacyMode,
        trackerEnabled: Boolean,
        streamBoost: Boolean,
        resType: com.example.manager.AbpRuleEngine.ResourceType = com.example.manager.AbpRuleEngine.ResourceType.OTHER,
        isFirstParty: Boolean = (pgHost.isNotEmpty() && com.example.manager.SiteDomainPolicy.isSameSite(reqHost, pgHost))
    ): String? {
        val isMediaResource = resType.isMediaFamily || isCleanStreamingPath(lowerUrl)
        if (isMediaResource && streamBoost) {
            return null
        }
        val isProtectedContentImage = resType == com.example.manager.AbpRuleEngine.ResourceType.IMAGE &&
            com.example.manager.AbpRuleEngine.isLikelyContentImagePath(lowerUrl)

        val isKnownAdDomain = AD_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }
        val adBlockActive = (mode != PrivacyMode.OFF)
        val isAggressive = (mode == PrivacyMode.AGGRESSIVE)

        // 0. Endpoint iklan & telemetri iklan YouTube eksplisit (berlaku baik first-party maupun third-party)
        if (!isMediaResource && isExplicitYouTubeAdRequest(reqHost, lowerUrl)) {
            val isYtTelemetryOnly = lowerUrl.contains("/ptracking") ||
                lowerUrl.contains("/youtubei/v1/log_event") ||
                lowerUrl.contains("/pcs/activeview")
            if (adBlockActive) {
                return "yt-ad-endpoint"
            }
            if (trackerEnabled && isYtTelemetryOnly) {
                return "yt-ad-endpoint"
            }
        }

        // 1. Domain iklan & pelacak eksplisit (hanya third-party)
        if (!isFirstParty) {
            if (adBlockActive && isKnownAdDomain) {
                return "ad-domain"
            }
            if (trackerEnabled && com.example.manager.TrackerClassifier.isTrackerDomain(reqHost)) {
                return "tracker-domain"
            }
        }

        val isFirstPartyCriticalFlow = isFirstParty && isCriticalAuthOrPaymentContext(lowerUrl)

        // 2. Kata kunci path iklan/pelacak eksplisit (di Balance hanya berlaku third-party)
        if (!isFirstParty && !isFirstPartyCriticalFlow) {
            if (trackerEnabled && com.example.manager.TrackerClassifier.isTrackerPath(lowerUrl)) {
                return "tracker-keyword"
            }
            if (adBlockActive && (AD_ONLY_PATH_KEYWORDS.any { lowerUrl.contains(it) } ||
                    (isMediaResource && com.example.manager.AbpRuleEngine.hasExplicitVideoAdIndicator(lowerUrl)))
            ) {
                return "ad-keyword"
            }
        }

        if (!adBlockActive) {
            return null
        }

        // 3. Kemampuan eks-Aggressive kini menjadi standar di mode BALANCED (dan tetap aktif di AGGRESSIVE),
        //    tanpa memblokir media stream atau thumbnail konten sah.
        if (!isFirstParty && AGGRESSIVE_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
            return "aggressive-ad"
        }
        if (!isFirstParty && !isFirstPartyCriticalFlow && !isMediaResource && !isProtectedContentImage &&
            AGGRESSIVE_PATH_KEYWORDS.any { lowerUrl.contains(it) }
        ) {
            return "aggressive-keyword"
        }
        if (!isFirstParty && HeuristicsEngine.isGamblingUrl(lowerUrl)) {
            return "heuristic-gambling"
        }

        // 3b. Mode AGGRESSIVE Baru (Ultra-Aggressive): blokir jaringan iklan/popunder/push/judol tingkat lanjut
        //     serta path iklan first-party & third-party yang lebih dalam, tetap melindungi stream video & gambar konten.
        if (isAggressive) {
            if (!isFirstParty && ULTRA_AGGRESSIVE_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return "ultra-aggressive-ad"
            }
            if (!isFirstParty && !isFirstPartyCriticalFlow && !isMediaResource && !isProtectedContentImage &&
                ULTRA_AGGRESSIVE_PATH_KEYWORDS.any { lowerUrl.contains(it) }
            ) {
                return "ultra-aggressive-keyword"
            }
            if (isFirstParty && !isFirstPartyCriticalFlow && !isMediaResource && !isProtectedContentImage &&
                FIRST_PARTY_AGGRESSIVE_AD_PATHS.any { lowerUrl.contains(it) }
            ) {
                return "first-party-aggressive-ad"
            }
        }

        // 4. Evaluasi AbpRuleEngine
        val effectiveResType = if (isMediaResource && !resType.isMediaFamily) {
            com.example.manager.AbpRuleEngine.ResourceType.MEDIA
        } else {
            resType
        }
        val cachedRules = com.example.manager.FilterListManager.getCachedRules()
        if (cachedRules != null) {
            val match = cachedRules.ruleSet.shouldBlock(lowerUrl, pgHost, effectiveResType)
            if (match != null) {
                val isTrackerLike = com.example.manager.TrackerClassifier.isTrackerRequest(reqHost, lowerUrl) ||
                    lowerUrl.contains("analytics") || lowerUrl.contains("tracker") ||
                    lowerUrl.contains("pixel") || lowerUrl.contains("telemetry") ||
                    lowerUrl.contains("beacon")
                if (isTrackerLike) {
                    if (trackerEnabled) return "filter-tracker"
                } else {
                    if (adBlockActive) return "filter-rule"
                }
            }
        }

        // 5. Lapisan heuristik (setelah exception ABP)
        if (adBlockActive) {
            val explicitlyAllowed = cachedRules?.ruleSet?.matchesAllowRule(lowerUrl, pgHost, effectiveResType) == true
            if (!explicitlyAllowed) {
                val heuristic = HeuristicsEngine.heuristicReason(
                    host = reqHost,
                    lowerUrl = lowerUrl,
                    isFirstParty = isFirstParty,
                    resType = effectiveResType,
                    aggressive = isAggressive
                )
                if (heuristic != null) return heuristic
            }
        }

        return null
    }

    private fun isCriticalAuthOrPaymentContext(lowerUrl: String): Boolean {
        return CRITICAL_FLOW_KEYWORDS.any { lowerUrl.contains(it) }
    }

    private fun isDomainBlocked(host: String, blockSet: HashSet<String>): Boolean {
        if (host.isEmpty()) return false
        if (blockSet.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            val parent = host.substring(idx + 1)
            if (blockSet.contains(parent)) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    // ─── Safe domains (NEVER blocked) ────────────────────────────────────────

    private fun isSafeDomain(host: String): Boolean {
        if (SAFE_EXCLUSIONS.any { host == it || host.endsWith(".$it") }) return false
        return SAFE_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    internal fun isCriticalBypassDomain(host: String): Boolean {
        if (host.isEmpty()) return false
        return CRITICAL_BYPASS_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    private fun isWhitelistedHost(host: String): Boolean {
        val n = normalizeHost(host)
        return whitelisted.any { n == it || n.endsWith(".$it") }
    }

    // ─── Stealth CSS injection (Zero detectable ID/attribute footprint) ──────

    private fun injectCSS(view: WebView, css: String) {
        hiddenCount.incrementAndGet()
        val escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
        view.evaluateJavascript("""
(function(){
  var s=window.__asStyleRef;
  if(!s||!s.isConnected){
    s=document.createElement('style');
    (document.head||document.documentElement).appendChild(s);
    try{Object.defineProperty(window,'__asStyleRef',{value:s,enumerable:false,configurable:true});}catch(e){}
  }
  s.textContent=`$escaped`;
})();""", null)
    }

    fun injectZapperCSS(view: WebView, css: String) {
        val escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
        view.evaluateJavascript("""
(function(){
  var s=window.__asZapperRef;
  if(!s||!s.isConnected){
    s=document.createElement('style');
    (document.head||document.documentElement).appendChild(s);
    try{Object.defineProperty(window,'__asZapperRef',{value:s,enumerable:false,configurable:true});}catch(e){}
  }
  s.textContent=`$escaped`;
})();""", null)
    }

    // ─── Early script (sebelum halaman JS jalan) ──────────────────────────────

    private fun buildEarlyScript(hostname: String): String = buildString {
        append(EARLY_CORE_SCRIPT)
        if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
            append("\n").append(YT_EARLY_SCRIPT)
        }
    }

    // ─── Page script (setelah DOM siap) ───────────────────────────────────────

    private fun buildPageScript(hostname: String): String {
        val mode = currentMode.get()
        val isAggressive = mode == PrivacyMode.AGGRESSIVE
        return buildString {
            append(buildBaseCleanupScript(aggressive = isAggressive))
            append("\n").append(ANTI_ADBLOCK_NEUTRALIZER_SCRIPT)
            append("\n").append(buildVisualAdKiller(aggressive = isAggressive))
            if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
                append("\n").append(YT_PAGE_SCRIPT)
            }
        }
    }

    // ─── Cosmetic CSS (Stealth offscreen & zero-opacity rather than detectable display:none) ───

    private fun buildCosmeticCSS(hostname: String): String {
        val mode = currentMode.get()
        if (mode == PrivacyMode.OFF) return ""
        val selectors = LinkedHashSet<String>()

        // Mode BALANCED kini mewarisi seluruh kemampuan kosmetik dari mode Aggressive lama
        selectors.addAll(EXPLICIT_AD_SELECTORS)
        selectors.addAll(GLOBAL_COSMETIC)
        if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
            selectors.addAll(YT_COSMETIC)
        }
        DOMAIN_COSMETIC.forEach { (domain, rules) ->
            if (hostname == domain || hostname.endsWith(".$domain")) selectors.addAll(rules)
        }

        // Mode AGGRESSIVE baru menambahkan selektor ultra-agresif (banner judol, sticky ads, popunder overlays)
        if (mode == PrivacyMode.AGGRESSIVE) {
            selectors.addAll(ULTRA_AGGRESSIVE_COSMETIC)
        }

        val validated = selectors.filter { com.example.manager.CosmeticFilterInjector.isSafeCssSelector(it) }
        if (validated.isEmpty()) return ""

        val ruleBody = "{ opacity:0 !important; pointer-events:none !important; max-height:1px !important; max-width:1px !important; overflow:hidden !important; position:absolute !important; left:-9999px !important; top:-9999px !important; z-index:-9999 !important; }"
        // Satu aturan per baris agar 1 selektor tidak didukung tidak membatalkan selektor lain.
        return validated.joinToString("\n") { sel -> "$sel $ruleBody" }
    }

    fun buildCustomZapperCSS(hostname: String): String {
        val norm = normalizeHost(hostname)
        val rules = customZapperRules[norm] ?: return ""
        if (rules.isEmpty()) return ""
        return rules.joinToString(",\n") +
            " { display:none !important; visibility:hidden !important; pointer-events:none !important; }"
    }

    // ─── Stealth Surrogate & Empty Response Builder ───────────────────────────

    fun emptyResponse(uri: Uri?, acceptHeader: String?): WebResourceResponse {
        val fullUrl = uri?.toString().orEmpty().lowercase(Locale.US)
        val path = com.example.manager.AbpRuleEngine.extractCleanPath(fullUrl)
        val accept = acceptHeader.orEmpty().lowercase(Locale.US)
        val inferredType = com.example.manager.AbpRuleEngine.inferType(fullUrl, accept)

        val isJsRequest = !inferredType.isMediaFamily && (
            inferredType == com.example.manager.AbpRuleEngine.ResourceType.SCRIPT ||
                path.endsWith(".js") ||
                fullUrl.contains("adsbygoogle") ||
                fullUrl.contains("gpt.js") ||
                fullUrl.contains("prebid") ||
                fullUrl.contains("fuckadblock") ||
                fullUrl.contains("blockadblock") ||
                fullUrl.contains("admiral") ||
                fullUrl.contains("pagefair") ||
                fullUrl.contains("fundingchoices") ||
                fullUrl.contains("/pagead/js/") ||
                fullUrl.contains("analytics.js") ||
                fullUrl.contains("fbevents.js") ||
                accept.contains("javascript") ||
                accept.contains("ecmascript")
            )

        val isImageRequest = !inferredType.isMediaFamily && !isJsRequest && (
            inferredType == com.example.manager.AbpRuleEngine.ResourceType.IMAGE ||
                path.endsWith(".gif") ||
                path.endsWith(".png") ||
                path.endsWith(".jpg") ||
                path.endsWith(".jpeg") ||
                path.endsWith(".webp") ||
                path.endsWith(".svg") ||
                path.endsWith(".ico") ||
                path.endsWith(".avif") ||
                fullUrl.contains("/pixel") ||
                fullUrl.contains("/beacon") ||
                accept.startsWith("image/")
            )

        val isJsonRequest = !inferredType.isMediaFamily && (
            path.endsWith(".json") ||
                fullUrl.contains("/api/stats/ads") ||
                fullUrl.contains("/youtubei/v1/log_event") ||
                fullUrl.contains("/youtubei/v1/player/ad_break") ||
                fullUrl.contains("/get_midroll_info") ||
                fullUrl.contains("/ptracking") ||
                fullUrl.contains("/pcs/activeview") ||
                fullUrl.contains("/ad_data_204") ||
                fullUrl.contains("/pagead/interaction") ||
                fullUrl.contains("/pagead/adview") ||
                fullUrl.contains("/pagead/lvz") ||
                accept.contains("application/json")
            )

        val isCssRequest = inferredType == com.example.manager.AbpRuleEngine.ResourceType.STYLESHEET ||
            path.endsWith(".css") || accept.contains("text/css")

        val mime: String
        val body: ByteArray

        when {
            isJsRequest -> {
                mime = "application/javascript"
                body = getSurrogateJsPayload(fullUrl)
                surrogateCount.incrementAndGet()
            }
            isImageRequest -> {
                mime = "image/gif"
                body = TRANSPARENT_1X1_GIF
                surrogateCount.incrementAndGet()
            }
            isJsonRequest -> {
                mime = "application/json"
                body = SURROGATE_JSON_OK
                surrogateCount.incrementAndGet()
            }
            isCssRequest -> {
                mime = "text/css"
                body = SURROGATE_CSS_EMPTY
            }
            else -> {
                mime = "text/plain"
                body = EMPTY_BYTES
            }
        }

        // Return 200 OK for all surrogates (JS, GIF, JSON, CSS) so <script onerror> and <img onerror> NEVER fire
        val statusCode = if (body.isEmpty()) 204 else 200
        val reasonPhrase = if (body.isEmpty()) "No Content" else "OK"
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Cache-Control" to "no-store",
            "Timing-Allow-Origin" to "*"
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebResourceResponse(mime, "utf-8", statusCode, reasonPhrase, headers, ByteArrayInputStream(body))
        } else {
            @Suppress("DEPRECATION")
            WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
        }
    }

    private fun getSurrogateJsPayload(lowerUrl: String): ByteArray {
        return when {
            lowerUrl.contains("adsbygoogle") || lowerUrl.contains("/pagead/") || lowerUrl.contains("googlesyndication") ->
                SURROGATE_ADSBYGOOGLE_JS
            lowerUrl.contains("gpt.js") || lowerUrl.contains("doubleclick") || lowerUrl.contains("pubads") ->
                SURROGATE_GPT_JS
            lowerUrl.contains("fuckadblock") || lowerUrl.contains("blockadblock") || lowerUrl.contains("sniffadblock") ->
                SURROGATE_FUCKADBLOCK_JS
            lowerUrl.contains("prebid") ->
                SURROGATE_PREBID_JS
            lowerUrl.contains("analytics") || lowerUrl.contains("gtag") || lowerUrl.contains("fbevents") ->
                SURROGATE_ANALYTICS_JS
            lowerUrl.contains("inplayer-adx") || lowerUrl.contains("player-adx") ->
                SURROGATE_INPLAYER_ADX_JS
            else ->
                SURROGATE_GENERIC_AD_JS
        }
    }

    private fun normalizeHost(h: String) = h.trim().lowercase(Locale.US).removePrefix("www.")
    private fun safeHost(url: String?) = runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")

    // ─── Rule sets & Stealth Surrogates ────────────────────────────────────────

    companion object {
        // Singleton array byte kosong untuk menghindari alokasi objek berulang di shouldInterceptRequest
        private val EMPTY_BYTES = ByteArray(0)

        private val FIRST_PARTY_EXEMPT_REASONS: Set<String> = setOf(
            "filter-rule",
            "filter-tracker",
            "heuristic-domain",
            "heuristic-gambling",
            "heuristic-path",
            "ad-keyword",
            "tracker-keyword",
            "aggressive-keyword",
            "adguard-dns"
        )

        private val EXPLICIT_AD_SELECTORS: Set<String> = setOf(
            "ins.adsbygoogle",
            "[data-ad-unit]",
            "[data-google-query-id]",
            "[data-adsbygoogle-status]",
            "iframe[src*='doubleclick.net']",
            "iframe[src*='googlesyndication.com']",
            "iframe[src*='adnxs.com']",
            "iframe[src*='amazon-adsystem.com']",
            "iframe[src*='taboola.com']",
            "iframe[src*='outbrain.com']",
            "div[id^='google_ads_']",
            "div[id^='taboola-']",
            ".OUTBRAIN",
            "div[id^='ob_holder']"
        )

        // 43-byte valid 1x1 transparent GIF (prevents <img onerror="adblockDetected()">)
        val TRANSPARENT_1X1_GIF: ByteArray = java.util.Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        )

        private val SURROGATE_JSON_OK = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
        private val SURROGATE_CSS_EMPTY = "/* */".toByteArray(Charsets.UTF_8)

        private val SURROGATE_ADSBYGOOGLE_JS = """
(function(){
  window.google_ad_status=1;
  window.adsbygoogle=window.adsbygoogle||[];
  window.adsbygoogle.loaded=true;
  window.adsbygoogle.push=function(o){return 1;};
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_GPT_JS = """
(function(){
  var noop=function(){};
  var rThis=function(){return this;};
  var slot={addService:rThis,clearCategoryExclusions:rThis,clearTargeting:rThis,defineSizeMapping:rThis,get:function(){return null;},getAdUnitPath:function(){return '';},getAttributeKeys:function(){return[];},getCategoryExclusions:function(){return[];},getDomId:function(){return '';},getResponseInformation:function(){return null;},getSlotElementId:function(){return '';},getSlotId:function(){return{};},getTargeting:function(){return[];},getTargetingKeys:function(){return[];},set:rThis,setCategoryExclusion:rThis,setClickUrl:rThis,setCollapseEmptyDiv:rThis,setForceSafeFrame:rThis,setSafeFrameConfig:rThis,setTargeting:rThis,updateTargetingFromMap:rThis};
  var pubads={addEventListener:rThis,clear:noop,clearCategoryExclusions:rThis,clearTagForChildDirectedTreatment:rThis,clearTargeting:rThis,collapseEmptyDivs:noop,defineOutOfPagePassback:function(){return slot;},definePassback:function(){return slot;},disableInitialLoad:noop,display:noop,enableAsyncRendering:noop,enableSingleRequest:noop,enableSyncRendering:noop,enableVideoAds:noop,get:function(){return null;},getAttributeKeys:function(){return[];},getSlots:function(){return[];},getTargeting:function(){return[];},getTargetingKeys:function(){return[];},isInitialLoadDisabled:function(){return false;},refresh:noop,removeEventListener:function(){return false;},set:rThis,setCategoryExclusion:rThis,setCentering:noop,setCookieOptions:rThis,setForceSafeFrame:rThis,setLocation:rThis,setPublisherProvidedId:rThis,setRequestNonPersonalizedAds:rThis,setSafeFrameConfig:rThis,setTagForChildDirectedTreatment:rThis,setTargeting:rThis,setVideoContent:rThis,updateCorrelator:rThis};
  var gt=window.googletag||{};
  gt.apiReady=true;
  gt.pubadsReady=true;
  gt.cmd=gt.cmd||[];
  gt.cmd.push=function(fn){if(typeof fn==='function'){try{fn();}catch(e){}}return 1;};
  gt.companionAds=function(){return{addEventListener:rThis,enableSyncLoading:noop,setRefreshUnfilledSlots:noop};};
  gt.content=function(){return{addEventListener:rThis,setContent:noop};};
  gt.defineOutOfPageSlot=function(){return slot;};
  gt.defineSlot=function(){return slot;};
  gt.defineUnit=function(){return slot;};
  gt.destroySlots=function(){return true;};
  gt.disablePublisherConsole=noop;
  gt.display=noop;
  gt.enableServices=noop;
  gt.getVersion=function(){return '2025010101';};
  gt.pubads=function(){return pubads;};
  gt.setAdIframeTitle=noop;
  gt.sizeMapping=function(){return{addSize:rThis,build:function(){return[];}};};
  window.googletag=gt;
  while(gt.cmd.length>0){var f=gt.cmd.shift();if(typeof f==='function'){try{f();}catch(e){}}}
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_FUCKADBLOCK_JS = """
(function(){
  function Fab(){}
  Fab.prototype.check=function(l){return true;};
  Fab.prototype.clearEvent=function(){};
  Fab.prototype.emitEvent=function(detected){return this;};
  Fab.prototype.on=function(detected,fn){if(!detected&&typeof fn==='function'){try{fn();}catch(e){}}return this;};
  Fab.prototype.onDetected=function(fn){return this;};
  Fab.prototype.onNotDetected=function(fn){if(typeof fn==='function'){try{fn();}catch(e){}}return this;};
  Fab.prototype.setOption=function(){return this;};
  var inst=new Fab();
  window.FuckAdBlock=window.BlockAdBlock=Fab;
  window.fuckAdBlock=window.blockAdBlock=window.sniffAdBlock=inst;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_PREBID_JS = """
(function(){
  var pb=window.pbjs||{};
  pb.que=pb.que||[];
  pb.cmd=pb.cmd||[];
  pb.libLoaded=true;
  pb.addAdUnits=function(){};
  pb.requestBids=function(o){if(o&&typeof o.bidsBackHandler==='function'){try{o.bidsBackHandler();}catch(e){}}};
  pb.setConfig=function(){};
  pb.getAdserverTargeting=function(){return{};};
  pb.setTargetingForGPTAsync=function(){};
  pb.que.push=function(fn){if(typeof fn==='function'){try{fn();}catch(e){}}return 1;};
  pb.cmd.push=pb.que.push;
  window.pbjs=pb;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_INPLAYER_ADX_JS = """
(function(){
  var emptyList = function() { return []; };
  var ignore = function() {};
  var refuse = function() { return false; };
  try {
    window.show_adx = 0;
    window.COUNT_VAST = 0;
    window.vastAdx = [];
    window.bannerAdx = [];
  } catch (_) {}
  void emptyList; void ignore; void refuse;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_ANALYTICS_JS = """
(function(){
  var noop=function(){};
  window.ga=window.ga||function(){(window.ga.q=window.ga.q||[]).push(arguments);};
  window.ga.loaded=true;
  window.ga.create=noop;
  window.ga.getByName=function(){return null;};
  window.ga.getAll=function(){return[];};
  window.datalayer=window.dataLayer=window.dataLayer||[];
  window.fbq=window.fbq||function(){};
  window.fbq.loaded=true;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_GENERIC_AD_JS = """
(function(){
  window.google_ad_status=1;
  window.canRunAds=true;
  window.isAdBlockActive=false;
  window.adblock=false;
  window.adBlockEnabled=false;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SAFE_EXCLUSIONS = setOf(
            "adservice.google.com",
            "fundingchoicesmessages.google.com",
            "analytics.google.com",
            "pagead2.googlesyndication.com",
            "googleads.g.doubleclick.net"
        )

        private val SAFE_DOMAINS = setOf(
            "google.com", "google.co.id", "google.co.uk", "google.de", "google.fr",
            "google.co.jp", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "googletagmanager.com",
            "youtube.com", "youtu.be", "ytimg.com", "yt3.ggpht.com",
            "googlevideo.com",
            "accounts.google.com",
            "music.youtube.com",
            // Auth / Captcha / Security
            "recaptcha.net", "hcaptcha.com", "hcaptcha.net",
            "challenges.cloudflare.com", "turnstile.cloudflare.com",
            "arkoselabs.com", "funcaptcha.com", "geetest.com",
            "auth0.com", "okta.com", "onelogin.com",
            // CDN infra
            "cloudflare.com",
            "cdn.jsdelivr.net", "jsdelivr.net",
            "cdnjs.cloudflare.com",
            "unpkg.com",
            "jquery.com",
            "bootstrapcdn.com",
            "fontawesome.com",
            // Social login
            "appleid.apple.com",
            "login.microsoftonline.com", "microsoftonline.com",
            "login.live.com",
            // Payment gateway Indonesia & global
            "midtrans.com", "xendit.co", "doku.com",
            "snap.midtrans.com",
            "paypal.com", "stripe.com", "braintreegateway.com",
            // Media & streaming
            "spotify.com",
            // Common API / infra
            "web.whatsapp.com"
        )

        private val AD_DOMAINS = setOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com",
            "adnxs.com", "criteo.com", "outbrain.com", "taboola.com",
            "pubmatic.com", "rubiconproject.com", "openx.net", "adroll.com",
            "media.net", "propellerads.com", "exoclick.com", "adsterra.com",
            "popads.net", "popcash.net", "mgid.com", "revcontent.com",
            "zeropark.com", "trafficjunky.com", "go.xlviirdr.com",
            "onclickads.net", "adcash.com",
            "adsymptotic.com", "adcolony.com", "applovin.com",
            "mopub.com", "chartboost.com", "inneractive.com",
            "smartadserver.com", "teads.tv", "adform.net",
            "sharethrough.com", "indexexchange.com", "casalemedia.com",
            "33across.com", "bidswitch.net", "emxdgt.com",
            "contextweb.com", "yldbt.com", "adtelligent.com",
            "adsafeprotected.com", "moatads.com",
            // Anti-adblock recovery / wall providers
            "fundingchoicesmessages.google.com",
            "getadmiral.com", "pagefair.com", "pagefair.net", "blockadblock.com", "fuckadblock.com",
            // Gambling/scam ad networks
            "1xbet.com", "bet365.com",
            "woclin.com", "shorte.st", "adf.ly", "ouo.io"
        )

        private val TRACKER_DOMAINS = setOf(
            "hotjar.com", "clarity.ms", "mixpanel.com",
            "amplitude.com", "segment.com", "segment.io",
            "fullstory.com", "logrocket.com", "inspectlet.com",
            "mouseflow.com", "crazyegg.com",
            "scorecardresearch.com", "quantserve.com",
            "pixel.facebook.com",
            "analytics.tiktok.com",
            "ads.linkedin.com", "snap.licdn.com",
            "static.ads-twitter.com"
        )

        private val AGGRESSIVE_DOMAINS = setOf(
            "amazon-adsystem.com", "adsystem.amazon.com",
            "advertising.com", "everesttech.net",
            "rlcdn.com", "w55c.net",
            "ads-twitter.com"
        )

        private val ULTRA_AGGRESSIVE_DOMAINS = setOf(
            "hilltopads.net", "hilltopads.com", "clickadu.com", "trafficstars.com",
            "juicyads.com", "ero-advertising.com", "a-ads.com", "unityads.unity3d.com",
            "vungle.com", "inmobi.com", "bidvertiser.com", "zeroredirect1.com",
            "tsyndicate.com", "realsrv.com", "acscdn.com", "galaksion.com",
            "rollerads.com", "pushhouse.com", "evadav.com", "richads.com",
            "mondiad.com", "admaven.com", "coinzilla.com", "cointraffic.io",
            "adskeeper.com", "adskeeper.co.uk", "lentainform.com", "idealmedia.io",
            "trafficfactory.biz", "plugrush.com", "adxpansion.com", "adnium.com",
            "pushground.com", "megapush.com", "dao.ad", "propush.me",
            "outbrainimg.com", "trc.taboola.com", "vidazoo.com", "connatix.com",
            "primis.tech", "aniview.com", "springserve.com", "spotxchange.com",
            "spotx.tv", "lkqd.net", "fyber.com", "ironsrc.com", "Tapjoy.com",
            "tapjoy.com", "startappservice.com", "Smaato.net", "smaato.net",
            "sonobi.com", "gumgum.com", "triplelift.com", "sovrn.com",
            "lijit.com", "zedo.com", "undertone.com", "conversantmedia.com",
            "fastclick.net", "valueclick.com", "Burstnet.com", "burstnet.com",
            "tribalfusion.com", "exponential.com", "adblade.com", "content.ad",
            "adrecover.com", "d38psrni17bvxu.cloudfront.net"
        )

        private val ULTRA_AGGRESSIVE_PATH_KEYWORDS = listOf(
            "/clickunder",
            "/popunder.js",
            "/pop.js",
            "/inpagepush",
            "/inpage-push",
            "/webpush-ad",
            "/adframe.",
            "/adscript.",
            "/ad_data/",
            "/ad-unit/",
            "/ad_handler",
            "/nativeads/",
            "/floating-ad",
            "/interstitial-ad",
            "/sticky-ad",
            "/preroll-ad",
            "/midroll-ad",
            "/slot-gacor",
            "/rtp-slot",
            "/daftar-slot",
            "/link-gacor",
            "/judislot",
            "/situs-judi",
            "/mahjong-ways",
            "/kakek-zeus",
            "/scatter-hitam",
            "?adurl=",
            "&adurl=",
            "?click_url=",
            "&click_url=",
            "?zoneid=",
            "&zoneid=",
            "?banner_id=",
            "&banner_id="
        )

        private val FIRST_PARTY_AGGRESSIVE_AD_PATHS = listOf(
            "/wp-content/uploads/ads/",
            "/wp-content/plugins/ad-inserter/",
            "/assets/ads/",
            "/images/ads/",
            "/banner-judi",
            "/banner-slot",
            "/iklan-slot",
            "/iklan-judi",
            "/popunder.js",
            "/clickunder.js"
        )

        private val AD_ONLY_PATH_KEYWORDS = listOf(
            "/api/stats/ads",
            "/pagead/",
            "/doubleclick/",
            "/adsystem/",
            "/prebid.js",
            "/vast.xml",
            "/vpaid.js",
            "/adserver/ads",
            "/fuckadblock",
            "/blockadblock",
            "/adblock-detector"
        )

        private val YOUTUBE_EXPLICIT_AD_PATHS = listOf(
            "/api/stats/ads",
            "/pagead/",
            "/ptracking",
            "/youtubei/v1/log_event",
            "/youtubei/v1/player/ad_break",
            "/get_midroll_info",
            "/pcs/activeview",
            "/ad_data_204"
        )

        private val CRITICAL_FLOW_KEYWORDS = listOf(
            "/login", "/signin", "/signup", "/register", "/oauth", "/auth/",
            "/checkout", "/payment", "/pembayaran", "/transaksi", "/verify", "/captcha"
        )

        internal val CRITICAL_BYPASS_DOMAINS = setOf(
            "accounts.google.com",
            "recaptcha.net", "hcaptcha.com", "hcaptcha.net",
            "challenges.cloudflare.com", "turnstile.cloudflare.com",
            "arkoselabs.com", "funcaptcha.com", "geetest.com",
            "auth0.com", "okta.com", "onelogin.com",
            "appleid.apple.com", "login.microsoftonline.com", "login.live.com",
            "midtrans.com", "snap.midtrans.com", "xendit.co", "doku.com",
            "paypal.com", "stripe.com", "braintreegateway.com"
        )

        private val AGGRESSIVE_PATH_KEYWORDS = listOf(
            "/advertising/banner",
            "/ads/banner",
            "/adserver/",
            "/adbanner/",
            "/popunder/"
        )

        private val REDIRECT_BLOCK_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "taboola.com", "outbrain.com", "exoclick.com",
            "propellerads.com", "adsterra.com", "popads.net"
        )

        // ─── Cosmetic selectors ──────────────────────────────────────────────

        private val GLOBAL_COSMETIC = setOf(
            "ins.adsbygoogle",
            "[data-ad-unit]", "[data-google-query-id]", "[data-adsbygoogle-status]",
            "iframe[src*='doubleclick']", "iframe[src*='googlesyndication']",
            "iframe[src*='adnxs']", "iframe[src*='amazon-adsystem']",
            "iframe[src*='taboola']", "iframe[src*='outbrain']",
            "div[id^='google_ads']", "[class*='adsbygoogle']",
            ".ad-banner", ".ads-banner-container", ".banner-ad",
            ".advertisement-box", ".advertise-here",
            "div[id^='taboola-']", "div[class*='taboola-']",
            ".OUTBRAIN", "div[id^='ob_holder']",
            "div[class*='jackpot-banner']", "div[class*='casino-banner']",
            "div[class*='judi-banner']",
            "a[class*='floating-ad-banner']"
        )

        private val YT_COSMETIC = setOf(
            ".ytp-ad-overlay-container", ".ytp-ad-text-overlay", ".ytp-ad-image-overlay",
            ".ytp-ad-progress", ".ytp-ad-progress-list",
            "ytd-promoted-sparkles-web-renderer", "ytd-display-ad-renderer",
            "ytd-companion-slot-renderer", "ytd-action-companion-ad-renderer",
            "ytd-promoted-video-renderer", "ytd-search-pyv-renderer",
            "ytd-in-feed-ad-layout-renderer", "ytd-ad-slot-renderer",
            "ytd-statement-banner-renderer", "ytd-promoted-sparkles-text-search-renderer",
            "ytd-banner-promo-renderer", "ytm-promoted-sparkles-web-renderer",
            "#masthead-ad", "#player-ads"
        )

        private val DOMAIN_COSMETIC = mapOf(
            "reddit.com"    to listOf(".promotedlink", "[data-testid='promoted-post']", "shreddit-ad-post"),
            "facebook.com"  to listOf("[aria-label='Sponsored']", "[data-pagelet*='sponsored']"),
            "instagram.com" to listOf("[aria-label='Sponsored']")
        )

        private val ULTRA_AGGRESSIVE_COSMETIC = setOf(
            "iframe[src*='popads']", "iframe[src*='popcash']", "iframe[src*='propellerads']",
            "iframe[src*='adsterra']", "iframe[src*='exoclick']", "iframe[src*='mgid']",
            "iframe[src*='revcontent']", "iframe[src*='juicyads']", "iframe[src*='clickadu']",
            "iframe[src*='hilltopads']", "iframe[src*='adskeeper']",
            "div[id*='mgid']", "div[class*='mgid']", "div[id*='revcontent']",
            "div[id*='adsterra']", "div[class*='adsterra']",
            "div[class*='slot-banner']", "div[class*='gacor-banner']", "div[class*='judol-banner']",
            "div[class*='iklan-slot']", "div[class*='iklan-judi']", "div[class*='floating-ads']",
            "div[id*='floating-ads']", "div[class*='sticky-ads']", "div[id*='sticky-ads']",
            "div[class*='bottom-ad']", "div[id*='bottom-ad']", "div[class*='top-ad-banner']",
            "div[class*='popup-ad']", "div[id*='popup-ad']", "div[class*='overlay-ad']",
            "a[href*='slot88']", "a[href*='slotgacor']", "a[href*='slot-gacor']",
            "a[href*='maxwin']", "a[href*='judislot']", "a[href*='rtp-slot']",
            "a[href*='rtpslot']", "a[href*='daftar-slot']", "a[href*='link-gacor']",
            "a[href*='sbobet']", "a[href*='pragmatic']", "a[href*='mahjong-ways']",
            "a[href*='togel88']", "a[href*='poker88']", "a[href*='casino88']",
            "a[href*='zeus88']", "a[href*='olympus88']", "a[href*='sultan88']",
            "a[href*='hoki88']", "a[href*='kakek-zeus']", "a[href*='scatter-hitam']",
            "img[src*='slot-gacor']", "img[src*='slotgacor']", "img[src*='judislot']",
            "img[src*='banner-slot']", "img[src*='iklan-slot']", "img[alt*='slot gacor']",
            "img[alt*='judi slot']", "img[alt*='rtp slot']", "img[alt*='maxwin']"
        )

        // ─── Stealth Early Script (Native Cloaking + Bait Dimension Spoof + SDK Stubs) ───

        val EARLY_CORE_SCRIPT = """
(function(){
  if(window.__asStealthInit) return;
  try{Object.defineProperty(window,'__asStealthInit',{value:true,enumerable:false,configurable:false});}catch(e){window.__asStealthInit=true;}

  // 1. Native Function.prototype.toString Cloaking (Defeats Anti-AdBlock Monkey-Patch Fingerprinting)
  var cloakedFns=typeof WeakMap!=='undefined'?new WeakMap():null;
  var origToString=Function.prototype.toString;
  function cloak(fn,nativeName){
    if(cloakedFns&&typeof fn==='function'){
      cloakedFns.set(fn,'function '+nativeName+'() { [native code] }');
    }
    return fn;
  }
  var wrappedToString=function toString(){
    if(cloakedFns&&cloakedFns.has(this)) return cloakedFns.get(this);
    return origToString.apply(this,arguments);
  };
  cloak(wrappedToString,'toString');
  try{Function.prototype.toString=wrappedToString;}catch(e){}

  // 2. Spoof Global Ad & Anti-AdBlock Objects (Prevents SDK presence checks)
  try{
    window.canRunAds=true;
    window.isAdBlockActive=false;
    window.adblock=false;
    window.adBlockEnabled=false;
    window.google_ad_status=1;
    if(!window.adsbygoogle){
      window.adsbygoogle={loaded:true,push:cloak(function(){return 1;},'push')};
    }else{
      window.adsbygoogle.loaded=true;
    }
    if(!window.fuckAdBlock){
      var Fab=function(){};
      Fab.prototype.check=function(){return true;};
      Fab.prototype.clearEvent=function(){};
      Fab.prototype.on=function(d,f){if(!d&&typeof f==='function'){try{f();}catch(e){}}return this;};
      Fab.prototype.onDetected=function(){return this;};
      Fab.prototype.onNotDetected=function(f){if(typeof f==='function'){try{f();}catch(e){}}return this;};
      Fab.prototype.setOption=function(){return this;};
      var fabInst=new Fab();
      window.FuckAdBlock=window.BlockAdBlock=Fab;
      window.fuckAdBlock=window.blockAdBlock=window.sniffAdBlock=fabInst;
    }
  }catch(e){}

  // 3. DOM Bait / Honeypot Element Dimension & Style Spoofing
  // Anti-AdBlock scripts create hidden bait elements (.adsbox, .ad-banner, .textads, ins.adsbygoogle)
  // and check if offsetHeight === 0 or getComputedStyle(el).display === 'none'.
  var BAIT_RX=/\b(adsbox|ad-banner|banner-ad|textads|banner-ads|ad-unit|ad-zone|ad-space|adsbygoogle|pub_300x250|ad-placeholder|ad-tester|adblock-bait)\b/i;
  function isBaitNode(el){
    if(!el||el.nodeType!==1) return false;
    var cls=typeof el.className==='string'?el.className:'';
    var id=el.id||'';
    return BAIT_RX.test(cls)||BAIT_RX.test(id)||el.tagName==='INS'&&cls.indexOf('adsbygoogle')>-1;
  }
  try{
    var proto=HTMLElement.prototype;
    var dOH=Object.getOwnPropertyDescriptor(proto,'offsetHeight');
    var dOW=Object.getOwnPropertyDescriptor(proto,'offsetWidth');
    var dCH=Object.getOwnPropertyDescriptor(proto,'clientHeight');
    var dCW=Object.getOwnPropertyDescriptor(proto,'clientWidth');
    if(dOH&&dOH.get){
      Object.defineProperty(proto,'offsetHeight',{get:cloak(function(){
        var v=dOH.get.call(this);
        return (v<=1&&isBaitNode(this))?90:v;
      },'get offsetHeight'),configurable:true});
    }
    if(dOW&&dOW.get){
      Object.defineProperty(proto,'offsetWidth',{get:cloak(function(){
        var v=dOW.get.call(this);
        return (v<=1&&isBaitNode(this))?300:v;
      },'get offsetWidth'),configurable:true});
    }
    if(dCH&&dCH.get){
      Object.defineProperty(proto,'clientHeight',{get:cloak(function(){
        var v=dCH.get.call(this);
        return (v<=1&&isBaitNode(this))?90:v;
      },'get clientHeight'),configurable:true});
    }
    if(dCW&&dCW.get){
      Object.defineProperty(proto,'clientWidth',{get:cloak(function(){
        var v=dCW.get.call(this);
        return (v<=1&&isBaitNode(this))?300:v;
      },'get clientWidth'),configurable:true});
    }
    var origGBR=Element.prototype.getBoundingClientRect;
    if(origGBR){
      Element.prototype.getBoundingClientRect=cloak(function(){
        var r=origGBR.apply(this,arguments);
        if(r&&r.height<=1&&isBaitNode(this)){
          return {width:300,height:90,top:10,left:10,right:310,bottom:100,x:10,y:10,toJSON:function(){return this;}};
        }
        return r;
      },'getBoundingClientRect');
    }
    var origGCS=window.getComputedStyle;
    if(origGCS){
      window.getComputedStyle=cloak(function(el,pseudo){
        var st=origGCS.apply(this,arguments);
        if(!pseudo&&isBaitNode(el)&&typeof Proxy!=='undefined'){
          return new Proxy(st,{
            get:function(target,prop){
              if(prop==='display') return 'block';
              if(prop==='visibility') return 'visible';
              if(prop==='opacity') return '1';
              if(prop==='height') return '90px';
              if(prop==='width') return '300px';
              if(prop==='getPropertyValue'){
                return cloak(function(p){
                  if(p==='display') return 'block';
                  if(p==='visibility') return 'visible';
                  if(p==='opacity') return '1';
                  return target.getPropertyValue(p);
                },'getPropertyValue');
              }
              var val=target[prop];
              return typeof val==='function'?val.bind(target):val;
            }
          });
        }
        return st;
      },'getComputedStyle');
    }
  }catch(e){}

  // 4. Cloaked Fetch / XHR / Beacon Interception (Returns 200 OK Surrogates)
  var B=/doubleclick|googlesyndication|adnxs|amazon-adsystem|criteo|taboola|outbrain|hotjar|clarity\.ms|mixpanel|segment\.(io|com)|amplitude|fullstory|scorecardresearch|google-analytics\.com\/(collect|j\/collect|g\/collect)|\/pagead\/|\/ptracking|fundingchoicesmessages|getadmiral\.com/i;
  function bad(u){try{return B.test(String(u||''));}catch(e){return false;}}
  var sb=navigator.sendBeacon&&navigator.sendBeacon.bind(navigator);
  if(sb){
    navigator.sendBeacon=cloak(function(u,d){return bad(u)?true:sb(u,d);},'sendBeacon');
  }
  var of=window.fetch;
  if(of){
    window.fetch=cloak(function(r,i){
      var u=typeof r==='string'?r:(r&&r.url)||'';
      if(bad(u)){
        try{if(window.AlexBrowserNative&&window.AlexBrowserNative.onStealthTrapDefused)window.AlexBrowserNative.onStealthTrapDefused('fetch-surrogate');}catch(e){}
        return Promise.resolve(new Response('{}',{status:200,statusText:'OK',headers:{'Content-Type':'application/json'}}));
      }
      return of.apply(this,arguments);
    },'fetch');
  }
  var xo=XMLHttpRequest.prototype.open;
  if(xo){
    XMLHttpRequest.prototype.open=cloak(function(m,u){
      if(bad(u)){
        u='data:application/json,%7B%22status%22%3A%22ok%22%7D';
      }
      return xo.apply(this,arguments);
    },'open');
  }
  var ow=window.open;
  if(ow){
    window.open=cloak(function(u,t,f){return bad(u)?null:ow.apply(this,arguments);},'open');
  }
})();
        """.trimIndent()

        private val YT_EARLY_SCRIPT = """
(function(){
  if(window.__asYTEarly) return;
  try{Object.defineProperty(window,'__asYTEarly',{value:true,enumerable:false,configurable:false});}catch(e){window.__asYTEarly=true;}
  function wipe(v){
    if(v&&typeof v==='object'){
      try{
        delete v.adPlacements;delete v.playerAds;delete v.adSlots;delete v.adBreakHeartbeatParams;
        if(v.playerResponse&&typeof v.playerResponse==='object'){
          delete v.playerResponse.adPlacements;delete v.playerResponse.playerAds;
          delete v.playerResponse.adSlots;delete v.playerResponse.adBreakHeartbeatParams;
        }
      }catch(e){}
    }
    return v;
  }
  function stripFeed(d){
    if(!d) return d;
    try{
      var tabs=d&&d.contents&&d.contents.twoColumnBrowseResultsRenderer&&d.contents.twoColumnBrowseResultsRenderer.tabs;
      if(tabs) tabs.forEach(function(t){
        var c=t&&t.tabRenderer&&t.tabRenderer.content;
        var items=(c&&c.richGridRenderer&&c.richGridRenderer.contents)||(c&&c.sectionListRenderer&&c.sectionListRenderer.contents);
        if(!items) return;
        for(var i=items.length-1;i>=0;i--){
          var x=items[i];
          if(x.promotedSparklesWebRenderer||x.adSlotRenderer||x.searchPyvRenderer||x.inFeedAdLayoutRenderer
          ||(x.richItemRenderer&&x.richItemRenderer.content&&(x.richItemRenderer.content.adSlotRenderer||x.richItemRenderer.content.promotedSparklesWebRenderer||x.richItemRenderer.content.inFeedAdLayoutRenderer)))
            items.splice(i,1);
        }
      });
    }catch(e){}
    return d;
  }
  try{Object.defineProperty(window,'ytInitialPlayerResponse',{get:function(){return this.__ay;},set:function(v){this.__ay=wipe(v);},configurable:true});}catch(e){}
  try{Object.defineProperty(window,'ytInitialData',{get:function(){return this.__ad;},set:function(v){this.__ad=stripFeed(v);},configurable:true});}catch(e){}
  var pf=window.fetch;
  var YB=['/api/stats/ads','/pagead/','/ptracking','/youtubei/v1/log_event','/youtubei/v1/player/ad_break','/get_midroll_info','/pcs/activeview','/ad_data_204','doubleclick.net/pagead','googlesyndication.com'];
  function isYtAdUrl(u){
    if(!u) return false;
    var s=String(u);
    if(s.indexOf('/videoplayback')>-1||s.indexOf('/s/player/')>-1) return false;
    return YB.some(function(e){return s.indexOf(e)>-1;});
  }
  if(pf){
    window.fetch=function(r,i){
      var u=typeof r==='string'?r:(r&&r.url)||'';
      if(isYtAdUrl(u)) return Promise.resolve(new Response('{"status":"ok"}',{status:200,headers:{'Content-Type':'application/json'}}));
      return pf.apply(this,arguments);
    };
  }
  var yxo=XMLHttpRequest.prototype.open;
  if(yxo){
    XMLHttpRequest.prototype.open=function(m,u){
      if(isYtAdUrl(u)){
        arguments[1]='data:application/json,%7B%22status%22%3A%22ok%22%7D';
      }
      return yxo.apply(this,arguments);
    };
  }
})();
        """.trimIndent()

        // ─── Anti-AdBlock Modal / Wall Neutralizer ────────────────────────────

        internal val PROTECTED_DIALOG_KEYWORDS = listOf(
            "login", "signin", "sign-in", "sign in", "signup", "sign-up", "sign up",
            "register", "auth", "oauth", "password", "otp", "verification", "verify",
            "captcha", "recaptcha", "hcaptcha", "turnstile",
            "cookie", "consent", "gdpr", "privacy",
            "paywall", "subscribe", "subscription", "checkout", "payment",
            "age-gate", "agegate", "age_gate", "adult", "confirm"
        )

        internal val POSITIVE_ANTI_ADBLOCK_MARKERS = listOf(
            "fc-ab-root", "fc-whitelist-root", "ab-message", "babasbmsgx",
            "adblock", "adblocker", "adb-detected", "fuckadblock", "blockadblock"
        )

        internal val POSITIVE_ANTI_ADBLOCK_TEXTS = listOf(
            "disable your adblock", "turn off adblock", "matikan adblock",
            "nonaktifkan adblock", "adblock detected", "adblocker detected",
            "allow ads", "izinkan iklan"
        )

        internal fun isAntiAdblockModalCandidate(
            classAndId: String,
            textContent: String,
            hasProtectedFormOrInput: Boolean = false
        ): Boolean {
            if (hasProtectedFormOrInput) return false
            val attrLower = classAndId.lowercase(Locale.US)
            val textLower = textContent.lowercase(Locale.US)

            if (PROTECTED_DIALOG_KEYWORDS.any { attrLower.contains(it) || textLower.contains(it) }) {
                return false
            }

            val hasMarker = POSITIVE_ANTI_ADBLOCK_MARKERS.any { attrLower.contains(it) }
            val hasPositiveText = POSITIVE_ANTI_ADBLOCK_TEXTS.any { textLower.contains(it) }
            return hasMarker || hasPositiveText
        }

        val ANTI_ADBLOCK_NEUTRALIZER_SCRIPT = """
(function(){
  if(window.__asNeutralizer) return;
  try{Object.defineProperty(window,'__asNeutralizer',{value:true,enumerable:false,configurable:false});}catch(e){window.__asNeutralizer=true;}

  var MODAL_SELS=[
    '.fc-ab-root','#fc-ab-root','.fc-whitelist-root',
    '#babasbmsgx','.ab-message','#ab-message',
    '.adblock-modal','#adblock-modal','.adblocker-modal',
    '[class*="adblock-notice"]','[id*="adblock-notice"]',
    '[class*="adblock-overlay"]','[id*="adblock-overlay"]',
    '[class*="adb-detected"]','[id*="adb-detected"]',
    '[class*="fuckadblock"]','[id*="fuckadblock"]',
    '[class*="blockadblock"]','[id*="blockadblock"]'
  ];

  var PROTECTED_KEYWORDS=[
    'login','signin','sign-in','sign in','signup','sign-up','sign up',
    'register','auth','oauth','password','otp','verification','verify',
    'captcha','recaptcha','hcaptcha','turnstile',
    'cookie','consent','gdpr','privacy',
    'paywall','subscribe','subscription','checkout','payment',
    'age-gate','agegate','age_gate','adult','confirm'
  ];

  var POSITIVE_MARKERS=[
    'fc-ab-root','fc-whitelist-root','ab-message','babasbmsgx',
    'adblock','adblocker','adb-detected','fuckadblock','blockadblock'
  ];

  var POSITIVE_TEXTS=[
    'disable your adblock','turn off adblock','matikan adblock',
    'nonaktifkan adblock','adblock detected','adblocker detected',
    'allow ads','izinkan iklan'
  ];

  function attrString(el){
    if(!el||el.nodeType!==1) return '';
    var cls=typeof el.className==='string'?el.className:'';
    var id=el.id||'';
    var aria=el.getAttribute?el.getAttribute('aria-label')||'':'';
    var role=el.getAttribute?el.getAttribute('role')||'':'';
    return (cls+' '+id+' '+aria+' '+role).toLowerCase();
  }

  function isProtectedModal(el){
    try{
      if(!el||el.nodeType!==1) return true;
      if(el.querySelector&&el.querySelector('input[type="password"],input[type="email"],form[action*="login"],form[action*="auth"],iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="turnstile"],video')){
        return true;
      }
      var cur=el;
      for(var d=0;cur&&cur.nodeType===1&&d<4;d++,cur=cur.parentElement){
        var a=attrString(cur);
        if(PROTECTED_KEYWORDS.some(function(k){return a.indexOf(k)>-1;})) return true;
      }
      var txt=((el.textContent||'')+'').substring(0,600).toLowerCase();
      if(PROTECTED_KEYWORDS.some(function(k){return txt.indexOf(k)>-1;})) return true;
      return false;
    }catch(e){return true;}
  }

  function hasPositiveAntiAdblockSignal(el){
    try{
      var a=attrString(el);
      var txt=((el.textContent||'')+'').substring(0,600).toLowerCase();
      var hasMarker=POSITIVE_MARKERS.some(function(m){return a.indexOf(m)>-1;});
      var hasText=POSITIVE_TEXTS.some(function(t){return txt.indexOf(t)>-1;});
      return hasMarker||hasText;
    }catch(e){return false;}
  }

  function neutralizeElement(el){
    if(!el||el.getAttribute('data-as-neutralized')==='1') return false;
    if(isProtectedModal(el)||!hasPositiveAntiAdblockSignal(el)) return false;
    el.setAttribute('data-as-neutralized','1');
    el.style.setProperty('opacity','0','important');
    el.style.setProperty('pointer-events','none','important');
    el.style.setProperty('max-height','0px','important');
    el.style.setProperty('max-width','0px','important');
    el.style.setProperty('overflow','hidden','important');
    el.style.setProperty('position','absolute','important');
    el.style.setProperty('left','-9999px','important');
    return true;
  }

  function unlockScrollIfConfirmed(){
    try{
      [document.documentElement,document.body].forEach(function(el){
        if(!el) return;
        if(el.style.overflow==='hidden') el.style.setProperty('overflow','auto','important');
      });
    }catch(e){}
  }

  function sweepAntiAdblockModals(){
    var defused=0;
    try{
      MODAL_SELS.forEach(function(sel){
        document.querySelectorAll(sel).forEach(function(el){
          if(neutralizeElement(el)) defused++;
        });
      });
      if(defused>0){
        unlockScrollIfConfirmed();
        try{if(window.AlexBrowserNative&&window.AlexBrowserNative.onStealthTrapDefused)window.AlexBrowserNative.onStealthTrapDefused('anti-adblock-modal');}catch(e){}
      }
    }catch(e){}
  }

  sweepAntiAdblockModals();
  var timer=null;
  new MutationObserver(function(){
    if(timer||document.hidden) return;
    timer=setTimeout(function(){
      timer=null;
      var raf=window.requestAnimationFrame||function(cb){cb();};
      raf(sweepAntiAdblockModals);
    },500);
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── Base cleanup script ──────────────────────────────────────────────

        internal val BASE_CLEANUP_AD_DOMAINS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "amazon-adsystem.com",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "criteo.net"
        )

        internal val SAFE_VIDEO_EMBED_DOMAINS = setOf(
            "youtube.com",
            "youtube-nocookie.com",
            "youtu.be",
            "vimeo.com",
            "player.vimeo.com",
            "dailymotion.com",
            "twitch.tv",
            "player.twitch.tv",
            "spotify.com"
        )

        internal fun shouldCleanIframeInBaseCleanup(
            iframeSrc: String,
            pageHost: String,
            isInsideVideoPlayer: Boolean = false
        ): Boolean {
            if (isInsideVideoPlayer) return false
            val rawSrc = iframeSrc.trim()
            if (rawSrc.isEmpty() || rawSrc.startsWith("about:") || rawSrc.startsWith("javascript:") || rawSrc.startsWith("data:")) {
                return false
            }
            val iframeHost = CleanAdEngine.extractHost(rawSrc)
                .lowercase(Locale.US)
                .removePrefix("www.")
            if (iframeHost.isEmpty()) {
                // Relative URL -> first-party iframe -> never clean in BASE_CLEANUP_SCRIPT
                return false
            }
            val cleanPageHost = pageHost.trim().lowercase(Locale.US).removePrefix("www.")
            if (cleanPageHost.isNotEmpty() && !DomainClassifier.isThirdParty(cleanPageHost, iframeHost)) {
                // First-party iframe -> never clean
                return false
            }
            if (SAFE_VIDEO_EMBED_DOMAINS.any { iframeHost == it || iframeHost.endsWith(".$it") }) {
                return false
            }
            if (CRITICAL_BYPASS_DOMAINS.any { iframeHost == it || iframeHost.endsWith(".$it") }) {
                return false
            }
            return BASE_CLEANUP_AD_DOMAINS.any { iframeHost == it || iframeHost.endsWith(".$it") }
        }

        internal fun buildBaseCleanupScript(aggressive: Boolean = false): String = """
(function(){
  if(window.__asBasePage) return;
  try{Object.defineProperty(window,'__asBasePage',{value:true,enumerable:false,configurable:false});}catch(e){window.__asBasePage=true;}
  var AGG=${aggressive};
  var BAD_DOMAINS=[
    'doubleclick.net','googlesyndication.com','googleadservices.com',
    'adnxs.com','amazon-adsystem.com','rubiconproject.com',
    'pubmatic.com','openx.net','taboola.com','outbrain.com',
    'criteo.com','criteo.net'
  ];
  var SAFE_EMBED_DOMAINS=[
    'youtube.com','youtube-nocookie.com','youtu.be',
    'vimeo.com','player.vimeo.com','dailymotion.com',
    'twitch.tv','player.twitch.tv','spotify.com'
  ];
  function cleanHost(h){
    return String(h||'').toLowerCase().replace(/^www\./,'').replace(/\.$/,'');
  }
  function regDomain(h){
    var c=cleanHost(h);
    if(!c) return '';
    var p=c.split('.');
    if(p.length<=2) return c;
    var last=p[p.length-1],sec=p[p.length-2];
    var cc=(last==='id'||last==='uk'||last==='au'||last==='jp'||last==='nz'||last==='br')&&
           (sec==='co'||sec==='ac'||sec==='go'||sec==='or'||sec==='sch'||sec==='com'||sec==='net'||sec==='org');
    return (cc&&p.length>=3)?(p[p.length-3]+'.'+sec+'.'+last):(sec+'.'+last);
  }
  function isDomainMatch(host,list){
    return list.some(function(d){return host===d||host.endsWith('.'+d);});
  }
  function isProtectedVideoOrPlayer(el){
    try{
      if(!el||el.nodeType!==1) return true;
      var tag=(el.tagName||'').toUpperCase();
      if(tag==='VIDEO'||tag==='AUDIO'||tag==='SOURCE'||tag==='CANVAS') return true;
      if(el.closest&&el.closest('video,audio,.video-player,.player,[data-player],[class*="video-player"],[id*="video-player"]')) return true;
      return false;
    }catch(e){return true;}
  }
  function shouldCleanIframe(n){
    try{
      if(!n||n.nodeType!==1||(n.tagName||'').toUpperCase()!=='IFRAME') return false;
      if(n.getAttribute('data-as-cleaned')==='1') return false;
      if(isProtectedVideoOrPlayer(n)) return false;
      var raw=n.getAttribute('src')||n.src||'';
      if(!raw||raw.indexOf('about:')===0||raw.indexOf('javascript:')===0||raw.indexOf('data:')===0) return false;
      var u=new URL(raw,location.href);
      var iHost=cleanHost(u.hostname);
      var pHost=cleanHost(location.hostname);
      if(!iHost) return false;
      if(pHost&&regDomain(iHost)===regDomain(pHost)) return false;
      if(isDomainMatch(iHost,SAFE_EMBED_DOMAINS)) return false;
      return isDomainMatch(iHost,BAD_DOMAINS);
    }catch(e){return false;}
  }
  function hideIframe(n){
    try{
      n.setAttribute('data-as-cleaned','1');
      if(AGG){
        n.remove();
      }else{
        n.style.setProperty('opacity','0','important');
        n.style.setProperty('pointer-events','none','important');
        n.style.setProperty('max-height','0px','important');
        n.style.setProperty('max-width','0px','important');
        n.style.setProperty('overflow','hidden','important');
        n.style.setProperty('position','absolute','important');
        n.style.setProperty('left','-9999px','important');
      }
    }catch(e){}
  }
  function isPlaceholderImageSrc(s){
    if(!s) return true;
    var v=String(s).trim().toLowerCase();
    return v===''||v==='about:blank'||v.indexOf('data:image/gif')===0||v.indexOf('data:image/svg+xml')===0||v.indexOf('data:image/png;base64,ivborw0kggoaaaansuheugaaaaeaaaab')===0;
  }
  function isSafeLazyImageUrl(raw){
    try{
      if(!raw) return false;
      var s=String(raw).trim();
      if(!s||s.indexOf('data:')===0||s.indexOf('javascript:')===0||s.indexOf('about:')===0) return false;
      var u=new URL(s,location.href);
      var h=cleanHost(u.hostname);
      if(!h) return false;
      return !isDomainMatch(h,BAD_DOMAINS);
    }catch(e){return false;}
  }
  function recoverLazyImages(root){
    try{
      var scope=root&&root.querySelectorAll?root:document;
      scope.querySelectorAll('img[data-src],img[data-lazy-src],img[data-original],img[data-lazy],img[data-srcset],img[data-lazy-srcset]').forEach(function(img){
        if(img.getAttribute('data-as-lazy-fixed')==='1') return;
        var curSrc=img.getAttribute('src')||'';
        if(isPlaceholderImageSrc(curSrc)){
          var cand=img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')||img.getAttribute('data-lazy')||'';
          if(isSafeLazyImageUrl(cand)){
            img.setAttribute('src',cand);
            img.setAttribute('data-as-lazy-fixed','1');
          }
        }
        var curSet=img.getAttribute('srcset')||'';
        if(!curSet){
          var candSet=img.getAttribute('data-srcset')||img.getAttribute('data-lazy-srcset')||'';
          if(candSet&&candSet.indexOf('javascript:')===-1){
            img.setAttribute('srcset',candSet);
          }
        }
        if(img.classList&&(img.classList.contains('lazyload')||img.classList.contains('lazy-hidden'))){
          img.style.removeProperty('opacity');
        }
      });
    }catch(e){}
  }
  function clean(root){
    try{
      var scope=root||document;
      if(scope.nodeType===1&&(scope.tagName||'').toUpperCase()==='IFRAME'){
        if(shouldCleanIframe(scope)) hideIframe(scope);
        return;
      }
      if(scope.querySelectorAll){
        scope.querySelectorAll('iframe[src]').forEach(function(n){
          if(shouldCleanIframe(n)) hideIframe(n);
        });
      }
      recoverLazyImages(scope);
    }catch(e){}
  }
  clean(document);
  var cleanTimer=null;
  new MutationObserver(function(){
    if(cleanTimer||document.hidden) return;
    cleanTimer=setTimeout(function(){
      cleanTimer=null;
      var raf=window.requestAnimationFrame||function(cb){cb();};
      raf(function(){clean(document);});
    },500);
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        internal val BASE_CLEANUP_SCRIPT: String = buildBaseCleanupScript(aggressive = false)

        // ─── YouTube skip script ───────────────────────────────────────────────

        internal val YT_PAGE_SCRIPT = """
(function(){
  if(window.__AlexYTSkip) return;
  window.__AlexYTSkip=true;
  var busy=false;
  var timer=null;
  function runSkip(){
    timer=null;
    if(busy||document.hidden) return;
    busy=true;
    try{
      var c=document.querySelector('.ytp-ad-overlay-close-button,.ytp-ad-feedback-dialog-close-button');
      if(c) c.click();
      var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-slot button');
      if(b){b.click();}
      var player=document.querySelector('#movie_player.ad-showing,#movie_player.ad-interrupting,.html5-video-player.ad-showing,.html5-video-player.ad-interrupting');
      if(player){
        var v=player.querySelector('video')||document.querySelector('video.html5-main-video');
        if(v){
          if(!window.__asYtMutedByAd){
            window.__asYtPrevMuted=!!v.muted;
            window.__asYtMutedByAd=true;
          }
          v.muted=true;
          if(isFinite(v.duration)&&v.duration>0&&v.duration<600&&v.currentTime<v.duration-0.3){
            v.currentTime=Math.max(v.duration-0.1,0);
          }
        }
      }else if(window.__asYtMutedByAd){
        var mainV=document.querySelector('#movie_player video,video.html5-main-video,video');
        if(mainV){
          mainV.muted=!!window.__asYtPrevMuted;
        }
        window.__asYtMutedByAd=false;
      }
      var slots=document.querySelectorAll('ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytd-promoted-video-renderer,ytd-search-pyv-renderer,ytm-promoted-sparkles-web-renderer,#player-ads,#masthead-ad');
      for(var i=0;i<slots.length;i++){slots[i].remove();}
    }catch(e){}
    busy=false;
  }
  function skip(){
    if(timer||document.hidden) return;
    timer=setTimeout(runSkip,650);
  }
  runSkip();
  new MutationObserver(function(){skip();})
    .observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── Visual Ad Killer (Stealth Offscreen + Idle-Debounced) ─────────────

        fun buildVisualAdKiller(aggressive: Boolean): String = """
(function(){
  if(window.__asVAK) return;
  try{Object.defineProperty(window,'__asVAK',{value:true,enumerable:false,configurable:false});}catch(e){window.__asVAK=true;}
  var AGG=${aggressive};
  var IS_STREAMING=/(^|\.)(youtube\.com|youtu\.be|spotify\.com|twitch\.tv|soundcloud\.com|netflix\.com)$/i.test(location.hostname||'');

  var GAMBLING=['arab777','slot gacor','slot online','casino online',
    'judi online','togel online','betting online','daftar slot',
    'agen slot','main slot','menang slot','bonus slot','spin slot',
    'rtp slot','jackpot slot','scatter hitam','mahjong ways',
    'pragmatic play','pg soft','deposit pulsa slot','maxwin slot',
    'link alternatif slot'];

  var SAFE=['login','signin','sign in','sign up','signup','register',
    'auth','oauth','account','checkout','payment','billing',
    'captcha','recaptcha','hcaptcha','verify','password','username',
    'email','phone','otp','invoice','subscribe','newsletter'];

  var IAB=[[728,90],[320,50],[320,100],[300,250],[336,280],[300,600],
           [160,600],[970,90],[468,60],[250,250],[200,200],[970,250]];

  function safeText(el){
    try{return[el.id||'',el.className||'',el.getAttribute('alt')||'',
      el.getAttribute('title')||'',el.getAttribute('aria-label')||'',
      el.getAttribute('src')||'',el.getAttribute('href')||'',
      el.textContent?el.textContent.substring(0,300):''].join(' ').toLowerCase();}
    catch(e){return'';}
  }

  function isProtected(el){
    try{
      if(!el||el.nodeType!==1) return true;
      var tag=(el.tagName||'').toUpperCase();
      if(tag==='BODY'||tag==='HTML'||tag==='MAIN'||tag==='ARTICLE'||tag==='VIDEO'||tag==='AUDIO') return true;
      if(el.querySelector){
        if(el.querySelector('video,audio,form,input,select')) return true;
        if(tag!=='IMG'&&tag!=='IFRAME'&&tag!=='A'&&el.querySelectorAll('img').length>1) return true;
        if(tag!=='IMG'&&tag!=='IFRAME'&&tag!=='A'&&el.querySelector('article,.animepost,.bs,.bsx,.thumb,.post-thumb,.limit')) return true;
      }
      var n=el;
      for(var i=0;n&&i<6;i++,n=n.parentElement){
        var t=safeText(n);
        if(SAFE.some(function(w){return t.includes(w);})) return true;
        if(n.tagName==='FORM'||n.tagName==='INPUT'||n.tagName==='SELECT') return true;
      }
      return false;
    }catch(e){return true;}
  }

  function hasGambling(el){
    var t=safeText(el);
    return GAMBLING.some(function(p){return t.includes(p);});
  }

  function isIAB(el){
    try{var r=el.getBoundingClientRect();var w=Math.round(r.width);var h=Math.round(r.height);
    if(w<50||h<20) return false;
    return IAB.some(function(s){return Math.abs(w-s[0])<=25&&Math.abs(h-s[1])<=25;});}
    catch(e){return false;}
  }

  function isFixedOverlay(el){
    try{var s=window.getComputedStyle(el);
    if(s.position!=='fixed'&&s.position!=='sticky') return false;
    var r=el.getBoundingClientRect();
    return r.width>=(window.innerWidth||320)*0.5&&r.height>=50;}
    catch(e){return false;}
  }

  // Stealth hide without leaving detectable data-alexshield attributes
  function hide(el){
    try{
      if(!el||isProtected(el)) return;
      el.style.setProperty('opacity','0','important');
      el.style.setProperty('pointer-events','none','important');
      el.style.setProperty('max-height','0px','important');
      el.style.setProperty('overflow','hidden','important');
      el.style.setProperty('position','absolute','important');
      el.style.setProperty('left','-9999px','important');
    }catch(e){}
  }

  function step1(){
    var SELS=['[data-ad-unit]','[data-google-query-id]',
      'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]',
      'iframe[src*="adnxs"]','iframe[src*="amazon-adsystem"]',
      'div[id^="google_ads"]'];
    SELS.forEach(function(s){
      try{document.querySelectorAll(s).forEach(function(el){if(!isProtected(el))hide(el);});}
      catch(e){}
    });
  }

  function step2(){
    if(IS_STREAMING) return;
    try{['iframe','img'].forEach(function(t){
      document.querySelectorAll(t+'[src]').forEach(function(el){
        if(isIAB(el)&&hasGambling(el)&&!isProtected(el)){
          var c=el.closest('a')||el;
          if(!isProtected(c)) hide(c);
        }
      });
    });}catch(e){}
  }

  function step3(){
    if(IS_STREAMING) return;
    try{document.querySelectorAll('div,section,aside').forEach(function(el){
      if(el.children.length>6) return;
      if(isIAB(el)&&hasGambling(el)&&!isProtected(el)) hide(el);
    });}catch(e){}
  }

  function step4(){
    if(IS_STREAMING) return;
    try{document.querySelectorAll('div,section,aside').forEach(function(el){
      if(isFixedOverlay(el)&&hasGambling(el)&&!isProtected(el)) hide(el);
    });}catch(e){}
  }

  function step5(){
    if(!AGG||IS_STREAMING) return;
    try{
      var JUDOL_SEL='a[href*="slot"],a[href*="gacor"],a[href*="maxwin"],a[href*="sbobet"],a[href*="judol"],a[href*="judislot"],a[href*="togel"],a[href*="rtp-slot"],a[href*="rtpslot"],a[href*="mahjong-ways"],a[href*="kakek-zeus"],img[src*="slot"],img[src*="gacor"],img[alt*="slot gacor"],img[alt*="judi"]';
      document.querySelectorAll(JUDOL_SEL).forEach(function(el){
        if(hasGambling(el)&&!isProtected(el)){
          var c=el.closest('a')||el;
          if(!isProtected(c)) hide(c);
        }
      });
      document.querySelectorAll('div,aside,section,ins').forEach(function(el){
        if(isProtected(el)) return;
        var s=window.getComputedStyle(el);
        var z=parseInt(s.zIndex,10)||0;
        if((s.position==='fixed'||s.position==='sticky')&&z>=999){
          var r=el.getBoundingClientRect();
          var t=safeText(el);
          if(hasGambling(el)||/\b(ad|ads|iklan|sponsor|promo|popunder|clickhere|download now)\b/i.test(t)||(r.height<=140&&r.width>=(window.innerWidth||320)*0.6)){
            hide(el);
          }
        }
      });
    }catch(e){}
  }

  function runAll(){
    if(document.hidden) return;
    step1();step2();step3();step4();step5();
  }

  if(document.readyState==='loading'){
    document.addEventListener('DOMContentLoaded',runAll,{once:true});
  }else{runAll();}

  var pending=false;
  new MutationObserver(function(){
    if(pending||document.hidden) return;
    pending=true;
    setTimeout(function(){
      var sched=window.requestIdleCallback||function(cb){setTimeout(cb,150);};
      sched(function(){pending=false;runAll();});
    },1800);
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── Interactive Custom Element Zapper Script ─────────────────────────

        fun buildElementZapperToggleScript(enable: Boolean, sessionToken: String = ""): String {
            val quotedToken = org.json.JSONObject.quote(sessionToken)
            return """
(function(){
  if(!${enable}){
    if(window.__asZapperCleanup) window.__asZapperCleanup();
    return;
  }
  if(window.__asZapperCleanup) window.__asZapperCleanup();
  window.__asZapperActive=true;
  var zapToken=$quotedToken;
  var selectedEl=null;
  var origOutline='';

  function cssPath(el){
    if(!el||el.nodeType!==1) return '';
    if(el.id&&!/^[0-9]/.test(el.id)&&el.id.indexOf(' ')===-1){
      return '#'+CSS.escape(el.id);
    }
    var parts=[];
    var cur=el;
    for(var depth=0;cur&&cur.nodeType===1&&cur.tagName!=='HTML'&&cur.tagName!=='BODY'&&depth<4;depth++){
      var tag=cur.tagName.toLowerCase();
      var cls=typeof cur.className==='string'?cur.className.trim().split(/\s+/).filter(function(c){return c&&c.length<36&&!/^[0-9]/.test(c);}).slice(0,2):[];
      if(cls.length>0){
        parts.unshift(tag+'.'+cls.map(function(c){return CSS.escape(c);}).join('.'));
        break;
      }else{
        var sibs=cur.parentElement?Array.from(cur.parentElement.children).filter(function(x){return x.tagName===cur.tagName;}):[];
        if(sibs.length>1){
          var idx=sibs.indexOf(cur)+1;
          parts.unshift(tag+':nth-of-type('+idx+')');
        }else{
          parts.unshift(tag);
        }
      }
      cur=cur.parentElement;
    }
    return parts.join(' ');
  }

  function clearHighlight(){
    if(selectedEl){
      try{selectedEl.style.outline=origOutline;}catch(e){}
      selectedEl=null;
    }
  }

  function onClickCapture(e){
    if(!window.__asZapperActive||!e||!e.isTrusted) return;
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
    var target=e.target;
    if(!target||target===document.body||target===document.documentElement) return;
    clearHighlight();
    selectedEl=target;
    origOutline=target.style.outline||'';
    target.style.setProperty('outline','3px solid #EA4335','important');
    var sel=cssPath(target);
    try{
      if(window.AlexBrowserNative&&window.AlexBrowserNative.onElementZapperSelected){
        window.AlexBrowserNative.onElementZapperSelected(sel, target.tagName.toLowerCase(), zapToken);
      }
    }catch(err){}
    return false;
  }

  window.__asConfirmZap=function(){
    if(!selectedEl) return '';
    var sel=cssPath(selectedEl);
    var toHide=selectedEl;
    clearHighlight();
    try{toHide.style.setProperty('display','none','important');}catch(e){}
    return sel;
  };

  window.__asZapperCleanup=function(){
    window.__asZapperActive=false;
    zapToken='';
    clearHighlight();
    document.removeEventListener('click',onClickCapture,true);
  };

  document.addEventListener('click',onClickCapture,true);
})();
            """.trimIndent()
        }
    }
}