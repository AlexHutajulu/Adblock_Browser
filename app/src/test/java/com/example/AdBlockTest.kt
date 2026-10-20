package com.example

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.yourbrowser.adblock.AdBlockWebViewClient
import com.yourbrowser.adblock.PrivacyMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdBlockTest {

    private fun createRequest(url: String, forMainFrame: Boolean = false): WebResourceRequest {
        return object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = forMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
    }

    @Test
    fun testBalancedBlocksKnownAdDomain() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://example.com")
        
        val request = createRequest("https://doubleclick.net/js/ads.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNotNull("Known ad domain doubleclick.net should be blocked (non-null response) in BALANCED mode", response)
    }

    @Test
    fun testOffModeDoesNotBlock() {
        val client = AdBlockWebViewClient(PrivacyMode.OFF)
        client.updateCurrentPageUrl("https://example.com")
        
        val request = createRequest("https://doubleclick.net/js/ads.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Ad blocking should be disabled entirely in OFF mode", response)
    }

    @Test
    fun testMainFrameNeverBlocked() {
        val client = AdBlockWebViewClient(PrivacyMode.AGGRESSIVE)
        
        val request = createRequest("https://doubleclick.net", forMainFrame = true)
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Main frame/document loading must never be blocked under any mode", response)
    }

    @Test
    fun testSafeDomainsAreNeverBlocked() {
        val client = AdBlockWebViewClient(PrivacyMode.AGGRESSIVE)
        client.updateCurrentPageUrl("https://example.com")
        
        val request = createRequest("https://recaptcha.net/recaptcha/api.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Safe domains must never be blocked even in AGGRESSIVE mode", response)
    }

    @Test
    fun testWhitelistedHostBypassesBlocking() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://example.com")
        client.whitelistHost("doubleclick.net")
        
        val request = createRequest("https://doubleclick.net/js/ads.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Whitelisted ad domain should bypass blocking", response)
    }

    @Test
    fun testNoLegacyAdBlockEngineCallInCustomWebView() {
        // This test ensures CustomWebView.kt uses AdBlockWebViewClient as the primary and ONLY active blocking engine.
        var customWebViewFile = File("src/main/java/com/example/ui/components/CustomWebView.kt")
        if (!customWebViewFile.exists()) {
            customWebViewFile = File("app/src/main/java/com/example/ui/components/CustomWebView.kt")
        }
        if (!customWebViewFile.exists()) {
            customWebViewFile = File("../app/src/main/java/com/example/ui/components/CustomWebView.kt")
        }
        assertTrue("CustomWebView.kt must exist, checked standard relative paths", customWebViewFile.exists())

        val content = customWebViewFile.readText()
        
        // Ensure no active, uncommented call to AdBlockEngine.shouldBlock remains
        val containsActiveCall = content.lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("//") && !it.startsWith("/*") && !it.endsWith("*/") }
            .any { it.contains("AdBlockEngine.shouldBlock(") }

        assertFalse(
            "Legacy AdBlockEngine.shouldBlock MUST NOT be called in CustomWebView.kt to prevent double-blocking & waste",
            containsActiveCall
        )
    }

    @Test
    fun testLruCacheEvictionOnModeAndWhitelistChange() {
        var blockedCount = 0
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED) { _, _ -> blockedCount++ }
        client.updateCurrentPageUrl("https://news.example.com")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        val req = createRequest("https://doubleclick.net/pagead/ads.js")

        // First call populates LruCache and blocks
        assertNotNull(client.shouldInterceptRequest(webView, req))
        assertEquals(1, blockedCount)

        // Second call hits LruCache and still increments blockedCount
        assertNotNull(client.shouldInterceptRequest(webView, req))
        assertEquals(2, blockedCount)

        // Whitelisting host evicts LruCache and allows request
        client.whitelistHost("doubleclick.net")
        assertNull(client.shouldInterceptRequest(webView, req))
    }

    @Test
    fun testYtPageScriptUsesMutationObserverWithoutAggressiveSkipSelector() {
        var adBlockFile = File("src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt")
        if (!adBlockFile.exists()) {
            adBlockFile = File("app/src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt")
        }
        if (!adBlockFile.exists()) {
            adBlockFile = File("../app/src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt")
        }
        assertTrue("AdBlockWebViewClient.kt must exist", adBlockFile.exists())

        val source = adBlockFile.readText()
        assertFalse(
            "YT_PAGE_SCRIPT must not contain aggressive button[class*=\"skip\" i] selector",
            source.contains("button[class*=\"skip\" i]")
        )
        assertTrue(
            "YT_PAGE_SCRIPT must use MutationObserver for YouTube ad skipping",
            source.contains("new MutationObserver(function(){skip();})")
        )
    }

    @Test
    fun testStealthSurrogateJsAndGifResponsesPreventOnError() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://portal.example.com")
        val webView = WebView(ApplicationProvider.getApplicationContext())

        // 1. Blocked AdSense script should return 200 OK with surrogate window.adsbygoogle stub
        val jsReq = createRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
        val jsResp = client.shouldInterceptRequest(webView, jsReq)
        assertNotNull("AdSense script must be intercepted", jsResp)
        assertEquals(200, jsResp!!.statusCode)
        assertEquals("application/javascript", jsResp.mimeType)
        val jsBody = jsResp.data.bufferedReader().readText()
        assertTrue("Surrogate JS must spoof window.adsbygoogle", jsBody.contains("window.adsbygoogle"))

        // 2. Blocked ad pixel/banner image should return 200 OK with valid 1x1 GIF (GIF89a)
        val imgReq = createRequest("https://doubleclick.net/ads/banner.gif")
        val imgResp = client.shouldInterceptRequest(webView, imgReq)
        assertNotNull("Ad image must be intercepted", imgResp)
        assertEquals(200, imgResp!!.statusCode)
        assertEquals("image/gif", imgResp.mimeType)
        val gifBytes = imgResp.data.readBytes()
        assertEquals(42, gifBytes.size)
        assertEquals("GIF89a", String(gifBytes.sliceArray(0 until 6), Charsets.US_ASCII))
    }

    @Test
    fun testStealthEarlyScriptContainsNativeCloakingAndBaitProxy() {
        val script = AdBlockWebViewClient.EARLY_CORE_SCRIPT
        assertTrue(
            "EARLY_CORE_SCRIPT must cloak Function.prototype.toString with [native code]",
            script.contains("[native code]")
        )
        assertTrue(
            "EARLY_CORE_SCRIPT must spoof offsetHeight for bait elements",
            script.contains("offsetHeight") && script.contains("getComputedStyle")
        )
    }

    @Test
    fun testCustomElementZapperCssGeneration() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.setCustomZapperSelectors("www.example.com", setOf("#floating-promo", "div.annoying-banner"))
        val css = client.buildCustomZapperCSS("example.com")
        assertTrue(css.contains("#floating-promo"))
        assertTrue(css.contains("div.annoying-banner"))
        assertTrue(css.contains("display:none !important"))
    }

    @Test
    fun testAtomicSetWhitelistAndFirstPartyStatsAssetPreservation() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://espn.example.com")
        val webView = WebView(ApplicationProvider.getApplicationContext())

        // Legitimate first-party /stats or /pixel asset on site must not be blocked
        val statsAssetReq = createRequest("https://espn.example.com/assets/player-stats.json")
        assertNull("First-party stats JSON must never be blocked", client.shouldInterceptRequest(webView, statsAssetReq))

        // Atomic setWhitelist replaces whitelist cleanly
        client.setWhitelist(setOf("www.doubleclick.net", "taboola.com"))
        assertNull(
            "Whitelisted domain via atomic setWhitelist must bypass blocking",
            client.shouldInterceptRequest(webView, createRequest("https://doubleclick.net/pagead/ads.js"))
        )
    }

    @Test
    fun testUpgradedFullScreenToolRegistrationAndScript() {
        val fsScript = com.example.manager.CustomJsToolbarManager.UPGRADED_FULLSCREEN_SCRIPT
        assertTrue("Script must support multi-vendor requestFullscreen", fsScript.contains("requestFullscreen") && fsScript.contains("webkitRequestFullscreen"))
        assertTrue("Script must integrate with native AlexBrowserNative bridge", fsScript.contains("AlexBrowserNative") && fsScript.contains("setImmersiveFullscreen"))
        assertTrue("Script must provide auto-hiding exit button and Escape handler", fsScript.contains("__kiwi_fs_exit") && fsScript.contains("__kiwiExitFs"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shortcuts = com.example.manager.CustomJsToolbarManager.getEnabledBuiltInShortcuts(context)
        assertTrue(
            "FULLSCREEN_PAGE shortcut must be enabled by default",
            shortcuts.contains(com.example.manager.BuiltInShortcut.FULLSCREEN_PAGE.id)
        )
        assertTrue(
            "Default JS scripts must include tpl_fullscreen_kiwi_pro",
            com.example.manager.CustomJsToolbarManager.loadScripts(context).any { it.id == "tpl_fullscreen_kiwi_pro" }
        )
    }

    @Test
    fun testAutoTiltSensorHysteresisAndDesktopZoomPersistence() {
        val mgr = com.example.manager.CustomJsToolbarManager
        // 1. Direct tilt detection across 4 quadrants
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT,
            mgr.computeTiltQuadrantWithHysteresis(0, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_RIGHT,
            mgr.computeTiltQuadrantWithHysteresis(90, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_LEFT,
            mgr.computeTiltQuadrantWithHysteresis(270, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.PORTRAIT_INVERTED,
            mgr.computeTiltQuadrantWithHysteresis(180, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )

        // 2. Hysteresis deadband prevents jitter near 45-degree boundary
        assertEquals(
            "42 degrees while in Portrait Upright must stay Portrait Upright (deadband)",
            com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT,
            mgr.computeTiltQuadrantWithHysteresis(42, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            "48 degrees while in Landscape Left must stay Landscape Left (deadband)",
            com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_LEFT,
            mgr.computeTiltQuadrantWithHysteresis(48, com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_LEFT)
        )

        // 3. Desktop Zoom Scale clamping and persistence
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        mgr.setDesktopZoomScale(context, 130)
        assertEquals(130, mgr.getDesktopZoomScale(context))
        mgr.setDesktopZoomScale(context, 25) // Clamped to min 50%
        assertEquals(50, mgr.getDesktopZoomScale(context))
        mgr.setDesktopZoomScale(context, 300) // Clamped to max 200%
        assertEquals(200, mgr.getDesktopZoomScale(context))
        mgr.setDesktopZoomScale(context, 100)
    }

    @Test
    fun testSecurityAndPersistenceAuditFixes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.example.manager.SettingsManager(context)

        // 1. Verify JSON tab persistence handles special delimiter strings without corruption
        val trickyTabs = listOf(
            com.example.ui.viewmodel.BrowserTab(
                id = "tab-1",
                url = "https://example.com/?q=a|||field|||b|||tab|||c",
                title = "Tricky |||tab||| Title \"Quotes\"",
                isPrivate = false
            )
        )
        settings.saveTabs(trickyTabs, "tab-1")
        val loadedTabs = settings.loadTabs()
        assertNotNull(loadedTabs)
        assertEquals(1, loadedTabs!!.size)
        assertEquals("https://example.com/?q=a|||field|||b|||tab|||c", loadedTabs[0].url)
        assertEquals("Tricky |||tab||| Title \"Quotes\"", loadedTabs[0].title)

        // 2. Verify JSON zapper rules persistence
        settings.saveCustomZapperRules("example.com", listOf("#banner-1", "div.promo-box"))
        val loadedZapper = settings.getCustomZapperRules("www.example.com")
        assertEquals(listOf("#banner-1", "div.promo-box"), loadedZapper)

        // 3. Verify DevToolsJsBridge rejects oversized (>32KB) payloads
        var callbackInvoked = false
        val bridge = com.example.devtools.DevToolsJsBridge(
            onElementSelected = { callbackInvoked = true },
            onConsoleBridgeMessage = { callbackInvoked = true }
        )
        val oversizedJson = "{\"message\":\"" + "A".repeat(40_000) + "\"}"
        bridge.onConsoleMessage(oversizedJson)
        bridge.onElementSelected(oversizedJson)
        assertFalse("Oversized payload (>32KB) must be dropped by DevToolsJsBridge", callbackInvoked)
    }

    @Test
    fun testFrameMetricsPortProbeChecksPortsIndependently() {
        val sync = com.example.manager.FrameMetricsTelemetrySync
        val detectedPorts = mutableListOf<Int>()

        // Bind an ephemeral loopback socket to act as an open port
        val serverSocket = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val openPort = serverSocket.localPort
        // Use a closed port before the open port
        val closedPort = if (openPort > 1025) openPort - 1 else openPort + 1

        try {
            // Test that closedPort being closed does NOT terminate probing before checking openPort
            sync.checkSuspiciousPorts(
                ports = intArrayOf(closedPort, openPort),
                timeoutMs = 150,
                onPortDetected = { detectedPorts.add(openPort) }
            )
            assertTrue(
                "Port probe must inspect each candidate port independently and detect openPort",
                detectedPorts.contains(openPort)
            )
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun testCertificateBlacklistAndFingerprintIntegrity() {
        val sync = com.example.manager.FrameMetricsTelemetrySync

        // Test blacklisted modding subjects/issuers
        assertTrue("apkeditor keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=ApkEditor, O=Mod, C=US"))
        assertTrue("luckypatcher keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=LuckyPatcher, O=Chelpus"))
        assertTrue("mtmanager keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=MTManager, O=BinMT"))
        assertTrue("npmanager keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=NPManager"))

        // Test legitimate subjects
        assertFalse("Clean subject must pass", sync.isBlacklistedIssuerOrSubject("CN=Google, O=Google LLC, C=US"))
        assertFalse("AlexBrowser subject must pass", sync.isBlacklistedIssuerOrSubject("CN=AlexBrowser, O=Alex, C=ID"))

        // Test SHA-256 digest computation
        val testBytes = "AlexBrowserTestSignature".toByteArray(Charsets.UTF_8)
        val hash = sync.computeSha256(testBytes)
        assertEquals("SHA-256 hex string must be 64 characters", 64, hash.length)
    }

    @Test
    fun testDisplayFrameStateRenderToken() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sync = com.example.manager.FrameMetricsTelemetrySync
        val token = sync.syncDisplayFrameState(context)
        assertEquals(0x7F3A9C12L, token)
        assertTrue("Render token must validate in test environment", sync.validateRenderToken(context))
    }

    @Test
    fun testRoomDatabaseInitializationAndMigration1to2() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val migration = com.example.data.AppDatabase.MIGRATION_1_2

        // 1. Verify migration versions contract
        assertEquals("Migration start version must be 1", 1, migration.startVersion)
        assertEquals("Migration end version must be 2", 2, migration.endVersion)

        // 2. Verify database initialization with migration registered
        val db = com.example.data.AppDatabase.getDatabase(context)
        assertNotNull("Database instance must not be null", db)
        assertNotNull("HistoryDao must be initialized", db.historyDao())
        assertNotNull("BookmarkDao must be initialized", db.bookmarkDao())
        assertNotNull("DownloadDao must be initialized", db.downloadDao())

        // 3. Verify DownloadItem schema integrity via DAO operations
        kotlinx.coroutines.runBlocking {
            val downloadItem = com.example.data.DownloadItem(
                id = "test-uuid-1",
                fileName = "test.pdf",
                url = "https://example.com/test.pdf",
                domain = "example.com",
                mimeType = "application/pdf",
                totalBytes = 1024L,
                downloadedBytes = 1024L,
                status = "COMPLETED",
                fileUri = "/data/user/0/com.example/files/downloads/test.pdf",
                createdAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
                failureReason = "",
                isIncognito = false
            )
            db.downloadDao().insertOrUpdate(downloadItem)
            val retrieved = db.downloadDao().getDownloadById("test-uuid-1")
            assertNotNull("Inserted download item must be retrievable", retrieved)
            assertEquals("test.pdf", retrieved?.fileName)
            assertEquals("COMPLETED", retrieved?.status)
            assertEquals(1024L, retrieved?.totalBytes)
        }
    }

    @Test
    fun testFileProviderSecurityAndWhitelistedPaths() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val authority = "${context.packageName}.fileprovider"

        // 1. Legitimate camera capture file in cache/camera/ must succeed
        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val cameraFile = File(cameraDir, "camera_capture_test.jpg").apply { writeText("fake image data") }
        val cameraUri = androidx.core.content.FileProvider.getUriForFile(context, authority, cameraFile)
        assertNotNull("Camera URI must be generated", cameraUri)
        assertTrue("Camera URI must have content scheme", cameraUri.scheme == "content")
        assertTrue("Camera URI authority must match app", cameraUri.authority == authority)

        // 2. Legitimate download file in internal downloads/ must succeed
        val internalDownloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val downloadFile = File(internalDownloadsDir, "document_1.pdf").apply { writeText("fake pdf") }
        val downloadUri = androidx.core.content.FileProvider.getUriForFile(context, authority, downloadFile)
        assertNotNull("Internal download URI must be generated", downloadUri)
        assertTrue("Download URI must have content scheme", downloadUri.scheme == "content")

        // 3. Unauthorized sensitive file directly under filesDir (root '.') must be REJECTED with IllegalArgumentException
        val unauthorizedRootFile = File(context.filesDir, "sensitive_config.json").apply { writeText("{\"secret\":\"value\"}") }
        var securityExceptionThrown = false
        try {
            androidx.core.content.FileProvider.getUriForFile(context, authority, unauthorizedRootFile)
        } catch (e: IllegalArgumentException) {
            securityExceptionThrown = true
        }
        assertTrue(
            "FileProvider must refuse to expose files outside explicit whitelisted subdirectories (root '.' must not be shared)",
            securityExceptionThrown
        )
    }

    @Test
    fun testDownloadManagerSsrfAndFilenameSanitizing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = com.example.data.AppDatabase.getDatabase(context)
        val repo = com.example.data.DownloadRepository(db.downloadDao())
        val manager = com.example.manager.AppDownloadManager(context, repo, kotlinx.coroutines.GlobalScope)

        val isSafeMethod = com.example.manager.AppDownloadManager::class.java.getDeclaredMethod("isSafeDownloadUrl", String::class.java).apply { isAccessible = true }
        val sanitizeMethod = com.example.manager.AppDownloadManager::class.java.getDeclaredMethod("sanitizeFileName", String::class.java).apply { isAccessible = true }
        val isPathWithinMethod = com.example.manager.AppDownloadManager::class.java.getDeclaredMethod("isPathWithinDirectory", File::class.java, File::class.java).apply { isAccessible = true }

        // 1. SSRF URL checks (IPv4, IPv6, Link-Local, Loopback)
        assertTrue("Public HTTPS URL must be safe", isSafeMethod.invoke(manager, "https://example.com/file.pdf") as Boolean)
        assertTrue("Data URI must be safe", isSafeMethod.invoke(manager, "data:text/plain;base64,SGVsbG8=") as Boolean)
        assertFalse("Loopback localhost must be blocked", isSafeMethod.invoke(manager, "http://localhost/secret") as Boolean)
        assertFalse("Loopback 127.0.0.1 must be blocked", isSafeMethod.invoke(manager, "http://127.0.0.1:8080/admin") as Boolean)
        assertFalse("Private IP 10.0.0.1 must be blocked", isSafeMethod.invoke(manager, "http://10.0.0.1/status") as Boolean)
        assertFalse("Private IP 192.168.1.1 must be blocked", isSafeMethod.invoke(manager, "http://192.168.1.1/router") as Boolean)
        assertFalse("Private IP 172.16.0.1 must be blocked", isSafeMethod.invoke(manager, "http://172.16.0.1/internal") as Boolean)
        assertFalse("Link-local 169.254.169.254 metadata must be blocked", isSafeMethod.invoke(manager, "http://169.254.169.254/latest/meta-data/") as Boolean)
        assertFalse("IPv6 loopback [::1] must be blocked", isSafeMethod.invoke(manager, "http://[::1]:8080/test") as Boolean)
        assertFalse("IPv6 unique-local [fd00::1] must be blocked", isSafeMethod.invoke(manager, "http://[fd00::1]/internal") as Boolean)
        assertFalse("IPv4-mapped IPv6 loopback must be blocked", isSafeMethod.invoke(manager, "http://[::ffff:127.0.0.1]/admin") as Boolean)

        // 2. Filename sanitization checks (including double URL-encoding)
        val sanitizedPathTraversal = sanitizeMethod.invoke(manager, "../../etc/passwd") as String
        assertFalse("Sanitized filename must not contain directory traversal relative components", sanitizedPathTraversal.contains(".."))
        assertFalse("Sanitized filename must not start with slash", sanitizedPathTraversal.startsWith("/"))

        val sanitizedUrlEncoded = sanitizeMethod.invoke(manager, "%2e%2e%2f%2e%2e%2fsecret.txt") as String
        assertFalse("Url encoded traversal must be cleaned", sanitizedUrlEncoded.contains(".."))

        val sanitizedDoubleEncoded = sanitizeMethod.invoke(manager, "%252e%252e%252f%252e%252e%252fsecret.txt") as String
        assertFalse("Double URL-encoded traversal must be cleaned", sanitizedDoubleEncoded.contains(".."))

        // 3. Canonical directory boundary checks (including sibling prefix protection)
        val baseDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val validChild = File(baseDir, "report.pdf")
        val traversalEscape = File(baseDir, "../sensitive.txt")
        val siblingPrefixDir = File(context.filesDir, "downloads_evil").apply { mkdirs() }
        val siblingFile = File(siblingPrefixDir, "exploit.apk")

        assertTrue("Valid child file inside downloads must be allowed", isPathWithinMethod.invoke(manager, validChild, baseDir) as Boolean)
        assertFalse("Parent traversal ../ must be rejected", isPathWithinMethod.invoke(manager, traversalEscape, baseDir) as Boolean)
        assertFalse("Sibling directory with matching prefix (downloads_evil) must be rejected", isPathWithinMethod.invoke(manager, siblingFile, baseDir) as Boolean)
    }

    @Test
    fun testManifestAndNetworkSecurityConfigHardening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_META_DATA or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_PROVIDERS
        )

        // 1. Verify unused FOREGROUND_SERVICE permission is removed
        val requestedPerms = pkgInfo.requestedPermissions?.toList() ?: emptyList()
        assertFalse(
            "Unused FOREGROUND_SERVICE permission must not be requested",
            requestedPerms.contains(android.Manifest.permission.FOREGROUND_SERVICE)
        )

        // 2. Verify allowBackup=false and WebView SafeBrowsing / MetricsOptOut metadata
        val appInfo = pm.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(
            "allowBackup must be disabled (false)",
            0,
            appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
        )
        assertTrue(
            "WebView SafeBrowsing metadata must be enabled",
            appInfo.metaData?.getBoolean("android.webkit.WebView.EnableSafeBrowsing") == true
        )
        assertTrue(
            "WebView MetricsOptOut metadata must be enabled",
            appInfo.metaData?.getBoolean("android.webkit.WebView.MetricsOptOut") == true
        )

        // 3. Verify internal services and providers are non-exported
        pkgInfo.services?.forEach { svc ->
            assertFalse("Service ${svc.name} must not be exported", svc.exported)
        }
        pkgInfo.providers?.forEach { prv ->
            assertFalse("Provider ${prv.name} must not be exported", prv.exported)
        }

        // 4. Verify network_security_config.xml enforces cleartextTrafficPermitted=false on filter list & sensitive domains
        val parser = context.resources.getXml(R.xml.network_security_config)
        val noCleartextDomains = mutableSetOf<String>()
        var inStrictDomainConfig = false
        var eventType = parser.eventType
        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (parser.name == "domain-config") {
                    val cleartext = parser.getAttributeBooleanValue(null, "cleartextTrafficPermitted", true)
                    inStrictDomainConfig = !cleartext
                } else if (parser.name == "domain" && inStrictDomainConfig) {
                    val domainText = parser.nextText().trim()
                    if (domainText.isNotEmpty()) {
                        noCleartextDomains.add(domainText)
                    }
                }
            } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "domain-config") {
                inStrictDomainConfig = false
            }
            eventType = parser.next()
        }
        parser.close()

        val expectedProtectedDomains = listOf(
            "easylist.to",
            "pgl.yoyo.org",
            "github.com",
            "raw.githubusercontent.com",
            "google.com",
            "accounts.google.com",
            "googleapis.com",
            "midtrans.com",
            "bca.co.id",
            "bankmandiri.co.id"
        )
        for (domain in expectedProtectedDomains) {
            assertTrue(
                "Domain '$domain' must be pinned to cleartextTrafficPermitted=false in network_security_config.xml",
                noCleartextDomains.contains(domain)
            )
        }
    }

    @Test
    fun testBackupAndDataExtractionRulesExcludeSensitiveDomains() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requiredDomains = setOf("sharedpref", "database", "file")

        // 1. Verify backup_rules.xml (<full-backup-content>)
        val backupExcluded = mutableSetOf<String>()
        val backupParser = context.resources.getXml(R.xml.backup_rules)
        var event = backupParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && backupParser.name == "exclude") {
                val domain = backupParser.getAttributeValue(null, "domain")
                val path = backupParser.getAttributeValue(null, "path")
                if (domain != null && path == ".") {
                    backupExcluded.add(domain)
                }
            }
            event = backupParser.next()
        }
        backupParser.close()
        assertTrue(
            "backup_rules.xml must exclude sharedpref, database, and file domains",
            backupExcluded.containsAll(requiredDomains)
        )

        // 2. Verify data_extraction_rules.xml (<cloud-backup> and <device-transfer>)
        val cloudExcluded = mutableSetOf<String>()
        val transferExcluded = mutableSetOf<String>()
        var currentSection = ""
        val extractionParser = context.resources.getXml(R.xml.data_extraction_rules)
        event = extractionParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (extractionParser.name) {
                    "cloud-backup", "device-transfer" -> currentSection = extractionParser.name
                    "exclude" -> {
                        val domain = extractionParser.getAttributeValue(null, "domain")
                        val path = extractionParser.getAttributeValue(null, "path")
                        if (domain != null && path == ".") {
                            if (currentSection == "cloud-backup") cloudExcluded.add(domain)
                            if (currentSection == "device-transfer") transferExcluded.add(domain)
                        }
                    }
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                if (extractionParser.name == "cloud-backup" || extractionParser.name == "device-transfer") {
                    currentSection = ""
                }
            }
            event = extractionParser.next()
        }
        extractionParser.close()

        assertTrue(
            "data_extraction_rules.xml <cloud-backup> must exclude sharedpref, database, and file",
            cloudExcluded.containsAll(requiredDomains)
        )
        assertTrue(
            "data_extraction_rules.xml <device-transfer> must exclude sharedpref, database, and file",
            transferExcluded.containsAll(requiredDomains)
        )
    }

    @Test
    fun testExtensionManagerSsrfZipSlipAndHostMatchingHardening() {
        val mgr = com.example.manager.BrowserExtensionManager

        // 1. HTTPS-only & SSRF checks on remote extension URLs
        assertTrue("Public HTTPS URL must be allowed", mgr.isSafeRemoteExtensionUrl("https://greasyfork.org/scripts/test.user.js"))
        assertFalse("Cleartext HTTP URL must be rejected", mgr.isSafeRemoteExtensionUrl("http://greasyfork.org/scripts/test.user.js"))
        assertFalse("Loopback localhost must be blocked", mgr.isSafeRemoteExtensionUrl("https://localhost/evil.js"))
        assertFalse("Loopback 127.0.0.1 must be blocked", mgr.isSafeRemoteExtensionUrl("https://127.0.0.1:8443/evil.js"))
        assertFalse("Private RFC1918 10.x must be blocked", mgr.isSafeRemoteExtensionUrl("https://10.0.0.1/evil.js"))
        assertFalse("Private RFC1918 172.16.x must be blocked", mgr.isSafeRemoteExtensionUrl("https://172.16.0.5/evil.js"))
        assertFalse("Private RFC1918 192.168.x must be blocked", mgr.isSafeRemoteExtensionUrl("https://192.168.1.1/evil.js"))
        assertFalse("Cloud metadata 169.254.169.254 must be blocked", mgr.isSafeRemoteExtensionUrl("https://169.254.169.254/latest/meta-data/"))
        assertFalse("IPv6 loopback [::1] must be blocked", mgr.isSafeRemoteExtensionUrl("https://[::1]/evil.js"))
        assertFalse("IPv6 unique-local [fd00::1] must be blocked", mgr.isSafeRemoteExtensionUrl("https://[fd00::1]/evil.js"))

        // 2. Zip-Slip path traversal checks on ZIP entry names
        assertEquals("manifest.json", mgr.sanitizeZipEntryName("./manifest.json"))
        assertEquals("scripts/content.js", mgr.sanitizeZipEntryName("/scripts\\content.js"))
        assertNull("Parent traversal ../ must be rejected", mgr.sanitizeZipEntryName("../../etc/passwd"))
        assertNull("Nested parent traversal must be rejected", mgr.sanitizeZipEntryName("scripts/../../secret.js"))
        assertNull("Null byte injection must be rejected", mgr.sanitizeZipEntryName("content.js\u0000.txt"))

        // 3. Bounded stream reader DoS protection
        val validStream = "console.log('ok');".byteInputStream(Charsets.UTF_8)
        assertEquals("console.log('ok');", mgr.readBoundedUtf8Text(validStream, maxBytes = 1024))

        val oversizedStream = ByteArray(2048) { 'A'.code.toByte() }.inputStream()
        var sizeExceptionThrown = false
        try {
            mgr.readBoundedUtf8Text(oversizedStream, maxBytes = 1024)
        } catch (_: SecurityException) {
            sizeExceptionThrown = true
        }
        assertTrue("Stream exceeding maxBytes must throw SecurityException", sizeExceptionThrown)

        // 4. Host matching must not be bypassed by query-string/path substring spoofing
        val scopedExt = com.example.manager.BrowserExtension(
            name = "YouTube Scoped Extension",
            jsCode = "console.log(1);",
            matches = listOf("youtube.com")
        )
        assertTrue("Exact domain must match", mgr.doesExtensionMatchUrl(scopedExt, "https://youtube.com/watch?v=123"))
        assertTrue("Subdomain must match", mgr.doesExtensionMatchUrl(scopedExt, "https://m.youtube.com/watch?v=123"))
        assertFalse(
            "Attacker domain with youtube.com in query string must NOT match",
            mgr.doesExtensionMatchUrl(scopedExt, "https://evil-phishing.com/login?next=https://youtube.com")
        )
        assertFalse(
            "Attacker domain with youtube.com in path must NOT match",
            mgr.doesExtensionMatchUrl(scopedExt, "https://evil-phishing.com/youtube.com/index.html")
        )
    }

    @Test
    fun testFilterListHardeningAndExternalIntentValidation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val filterMgr = com.example.manager.FilterListManager
        val secGuard = com.example.manager.SecurityGuardEngine
        val searchMgr = com.example.manager.SearchEngineManager

        // 1. FilterListManager HTTPS-only & SSRF protection
        assertTrue(
            "Public HTTPS filter list URL must be accepted",
            filterMgr.isSafeFilterListUrl("https://easylist.to/easylist/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "Cleartext HTTP filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("http://easylist.to/easylist/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "Loopback filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("https://localhost/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "Private IP filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("https://192.168.1.1/easylist.txt", resolveDns = true)
        )
        assertFalse(
            "Loopback 127.0.0.1 filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("https://127.0.0.1/easylist.txt", resolveDns = true)
        )

        // 2. FilterListManager bounded stream reading & ReDoS/regex hardening
        val smallStream = "||doubleclick.net^\n||ads.example.com/banner(1)+*.js\$script,third-party\n/(a+)+$/".byteInputStream(Charsets.UTF_8)
        val rawRules = filterMgr.readBoundedUtf8Stream(smallStream, maxBytes = 4096)
        assertNotNull(rawRules)
        val parsed = filterMgr.parseRules(rawRules!!)
        assertTrue("Exact domain rule must be parsed", parsed.blockDomains.contains("doubleclick.net"))
        assertEquals(
            "Raw /regex/ rule must be rejected while valid wildcard || rule is compiled safely",
            1,
            parsed.blockPatterns.size
        )
        assertTrue(
            "Escaped regex metacharacters (1)+ must match literal URL path",
            parsed.blockPatterns[0].containsMatchIn("https://ads.example.com/banner(1)+test.js")
        )
        assertEquals(
            "Rule with > 6 wildcards must be rejected to prevent ReDoS",
            "",
            filterMgr.abpRuleToRegex("||evil.com/*a*b*c*d*e*f*g^")
        )
        val overLimitStream = ByteArray(2048) { 'x'.code.toByte() }.inputStream()
        assertNull(
            "Stream exceeding maxBytes must return null cleanly",
            filterMgr.readBoundedUtf8Stream(overLimitStream, maxBytes = 1024)
        )

        // 3. SearchEngineManager dangerous scheme blocking (prevents Self-XSS and file:// / content:// navigation)
        val jsQuery = searchMgr.parseUrlOrQuery("javascript:alert(document.cookie)", com.example.manager.SearchEngine.GOOGLE)
        assertTrue("javascript: input must be converted to search query, never raw scheme", jsQuery.startsWith("https://www.google.com/search?q="))
        val fileQuery = searchMgr.parseUrlOrQuery("file:///data/data/com.example/shared_prefs/prefs.xml", com.example.manager.SearchEngine.GOOGLE)
        assertTrue("file:// input must be converted to search query", fileQuery.startsWith("https://www.google.com/search?q="))
        val contentQuery = searchMgr.parseUrlOrQuery("content://${context.packageName}.fileprovider/downloads/a.txt", com.example.manager.SearchEngine.GOOGLE)
        assertTrue("content:// input must be converted to search query", contentQuery.startsWith("https://www.google.com/search?q="))

        // 4. External Intent sanitization (OWASP MASVS Intent Redirection & Confused Deputy defense)
        val maliciousIntentUri = "intent://evil#Intent;action=android.intent.action.DELETE;component=${context.packageName}/.MainActivity;i.flags=3;end"
        val sanitizedIntent = secGuard.buildSafeExternalIntent(context, maliciousIntentUri)
        assertNotNull(sanitizedIntent)
        assertEquals("Action must be forced to ACTION_VIEW", android.content.Intent.ACTION_VIEW, sanitizedIntent!!.action)
        assertNull("Component must be stripped to null", sanitizedIntent.component)
        assertNull("Selector must be stripped to null", sanitizedIntent.selector)
        assertEquals(
            "URI read/write permission flags must be stripped",
            0,
            sanitizedIntent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )

        val selfTargetIntentUri = "intent://open#Intent;package=${context.packageName};scheme=https;end"
        assertNull("Intent targeting own package must be rejected", secGuard.buildSafeExternalIntent(context, selfTargetIntentUri))

        val fileDataIntentUri = "intent:///data/data/secret#Intent;scheme=file;end"
        assertNull("Intent with embedded file:// scheme must be rejected", secGuard.buildSafeExternalIntent(context, fileDataIntentUri))

        // Safe vs unsafe browser_fallback_url extraction
        val safeFallbackIntent = "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Flanding;end"
        assertEquals("https://example.com/landing", secGuard.extractSafeFallbackUrl(safeFallbackIntent))
        val ssrfFallbackIntent = "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=http%3A%2F%2F127.0.0.1%3A9222%2Fjson;end"
        assertNull("Loopback SSRF fallback URL must be rejected", secGuard.extractSafeFallbackUrl(ssrfFallbackIntent))
        val jsFallbackIntent = "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertNull("javascript: fallback URL must be rejected", secGuard.extractSafeFallbackUrl(jsFallbackIntent))

        // 5. WebView FileChooser result URI validation (prevents internal file/ContentProvider theft)
        val cameraUri = Uri.parse("content://${context.packageName}.fileprovider/camera_captures/captured_1.jpg")
        assertTrue(
            "Explicitly created camera capture URI for current session must be allowed",
            secGuard.isSafeFileChooserResultUri(context, cameraUri, allowedCameraUri = cameraUri)
        )
        assertFalse(
            "Internal FileProvider URI not matching active camera capture must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://${context.packageName}.fileprovider/downloads/secret.pdf"),
                allowedCameraUri = cameraUri
            )
        )
        assertFalse(
            "file:// URI returned to FileChooser must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("file:///data/data/${context.packageName}/databases/browser_database"),
                allowedCameraUri = null
            )
        )
        assertTrue(
            "External system MediaStore content:// URI must be allowed",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://com.android.providers.media.documents/document/image%3A1024"),
                allowedCameraUri = null
            )
        )
    }
}

