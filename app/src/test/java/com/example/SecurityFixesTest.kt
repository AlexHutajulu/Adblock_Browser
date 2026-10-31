package com.example

import com.example.manager.BackgroundMediaController
import com.example.manager.CertificatePinning
import com.example.manager.SearchEngine
import com.example.manager.SearchEngineManager
import com.example.ui.components.NativeMessageBridge
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityFixesTest {

    private fun findSource(relative: String): File {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("../app/src/main/java/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: File("app/src/main/java/$relative")
    }

    private fun findRes(relative: String): File {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
            File("../app/src/main/res/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: File("app/src/main/res/$relative")
    }

    @Test
    fun testNetworkSecurityDeniesCleartextByDefault() {
        val config = findRes("xml/network_security_config.xml")
        assertTrue("network_security_config.xml must exist", config.exists())
        val text = config.readText()
        assertTrue(
            "base-config must deny cleartext",
            text.contains("cleartextTrafficPermitted=\"false\"")
        )
        assertFalse(
            "base-config must not allow cleartext",
            text.contains("cleartextTrafficPermitted=\"true\"")
        )
    }

    @Test
    fun testScreenRecordingIsBlockedByDefault() {
        val source = findSource("com/example/manager/SettingsManager.kt").readText()
        assertTrue(
            "allowScreenRecording default must be false (FLAG_SECURE by default)",
            source.contains("getBoolean(KEY_ALLOW_SCREEN_RECORDING, false)")
        )
        assertFalse(
            "allowScreenRecording must not default to true",
            source.contains("getBoolean(KEY_ALLOW_SCREEN_RECORDING, true)")
        )
    }

    @Test
    fun testDatabaseNeverFallsBackToPlaintextFile() {
        val source = findSource("com/example/data/AppDatabase.kt").readText()
        assertFalse(
            "AppDatabase must never set PLAINTEXT_FALLBACK as a runtime status",
            source.contains("_encryptionStatus.value = DatabaseEncryptionStatus.PLAINTEXT_FALLBACK")
        )
        assertTrue(
            "AppDatabase fallback must be in-memory",
            source.contains("Room.inMemoryDatabaseBuilder")
        )
    }

    @Test
    fun testEncryptedPreferencesNeverFallBackToPlaintextFile() {
        val source = findSource("com/example/manager/EncryptedPreferencesManager.kt").readText()
        assertFalse(
            "EncryptedPreferencesManager must not fall back to a plaintext SharedPreferences file",
            source.contains("getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)")
        )
        assertTrue(
            "EncryptedPreferencesManager must use an in-memory store on Keystore failure",
            source.contains("InMemorySharedPreferences")
        )
    }

    @Test
    fun testFilterListContentValidation() {
        assertTrue(
            "Normal filter rules must pass",
            CertificatePinning.isSafeFilterListContent("||doubleclick.net^\n@@||example.com^\n")
        )
        assertFalse(
            "Filter list with a script tag must be rejected",
            CertificatePinning.isSafeFilterListContent("||example.com^\n<script>alert(1)</script>")
        )
        assertFalse(
            "Filter list with javascript: must be rejected",
            CertificatePinning.isSafeFilterListContent("||example.com^\njavascript:alert(1)")
        )
        assertFalse(
            "Filter list with data:text/html must be rejected",
            CertificatePinning.isSafeFilterListContent("data:text/html,<script>alert(1)</script>")
        )
    }

    @Test
    fun testCertificatePinnerIsExplicitWhenUnconfigured() {
        // Pins must be filled from a real network before release (see
        // CertificatePinning.kt). Until then, buildPinner() must return null
        // explicitly, not silently claim pinning is active.
        assertNull(CertificatePinning.buildPinner())
        assertEquals(0, CertificatePinning.FILTER_LIST_PINS.size)
    }

    @Test
    fun testJsBridgeBlocksExecutablesAndUaIsNotFrozenChrome124() {
        val source = findSource("com/example/ui/components/CustomWebView.kt").readText()
        assertTrue(
            "JS bridge must block executable blob downloads",
            source.contains("Blocked executable blob download via JS bridge")
        )
        assertTrue(
            "Desktop UA must be derived from the system WebView UA",
            source.contains("desktopUserAgentFromDefault")
        )
        assertFalse(
            "Hard-coded Chrome/124 UA must be gone",
            source.contains("Chrome/124.0.0.0")
        )
    }

    @Test
    fun testH1ArtworkUrlValidationBlocksLocalAndNonHttps() {
        assertTrue(
            "Valid public HTTPS artwork URL must be accepted",
            BackgroundMediaController.isAcceptableArtworkUrl("https://i.ytimg.com/vi/abc12345678/hqdefault.jpg")
        )
        assertFalse(
            "Cleartext HTTP artwork URL must be rejected",
            BackgroundMediaController.isAcceptableArtworkUrl("http://i.ytimg.com/vi/abc12345678/hqdefault.jpg")
        )
        assertFalse(
            "Localhost artwork URL must be rejected",
            BackgroundMediaController.isAcceptableArtworkUrl("https://localhost/logo.png")
        )
        assertFalse(
            "Loopback IPv4 artwork URL must be rejected",
            BackgroundMediaController.isAcceptableArtworkUrl("https://127.0.0.1/admin.png")
        )
        assertFalse(
            "Private LAN 192.168.x.x artwork URL must be rejected",
            BackgroundMediaController.isAcceptableArtworkUrl("https://192.168.1.1/status.png")
        )
        assertFalse(
            "Private LAN 10.x.x.x artwork URL must be rejected",
            BackgroundMediaController.isAcceptableArtworkUrl("https://10.0.0.1/router.png")
        )
        assertFalse(
            "Oversized artwork URL must be rejected",
            BackgroundMediaController.isAcceptableArtworkUrl("https://example.com/" + "a".repeat(2100))
        )
    }

    @Test
    fun testH2NativeMessageBridgeEnforcesMainFrameAndMatchingOrigin() {
        val customWebViewSource = findSource("com/example/ui/components/CustomWebView.kt").readText()
        assertFalse(
            "CustomWebView must not expose addJavascriptInterface anymore",
            customWebViewSource.contains("addJavascriptInterface(")
        )
        assertTrue(
            "CustomWebView must use NativeMessageBridge",
            customWebViewSource.contains("NativeMessageBridge.install(")
        )

        // Main frame + matching host -> trusted
        assertTrue(
            NativeMessageBridge.isTrustedSender(
                sourceOrigin = "https://www.youtube.com",
                isMainFrame = true,
                currentPageUrl = "https://www.youtube.com/watch?v=123"
            )
        )
        // Third-party iframe (isMainFrame = false) -> rejected even if origin matches
        assertFalse(
            NativeMessageBridge.isTrustedSender(
                sourceOrigin = "https://www.youtube.com",
                isMainFrame = false,
                currentPageUrl = "https://www.youtube.com/watch?v=123"
            )
        )
        // Cross-origin sender -> rejected
        assertFalse(
            NativeMessageBridge.isTrustedSender(
                sourceOrigin = "https://ads.evil.example",
                isMainFrame = true,
                currentPageUrl = "https://www.youtube.com/watch?v=123"
            )
        )
        // Opaque / null / non-web origin -> rejected
        assertFalse(
            NativeMessageBridge.isTrustedSender(
                sourceOrigin = "null",
                isMainFrame = true,
                currentPageUrl = "https://www.youtube.com/watch?v=123"
            )
        )
        assertFalse(
            NativeMessageBridge.isTrustedSender(
                sourceOrigin = "file:///sdcard/evil.html",
                isMainFrame = true,
                currentPageUrl = "https://www.youtube.com/watch?v=123"
            )
        )

        // Valid envelope parses
        val parsed = NativeMessageBridge.parseCall(
            """{"m":"openUrlInNewTab","a":["https://example.com"]}""",
            "example.com"
        )
        assertNotNull(parsed)
        assertEquals("openUrlInNewTab", parsed?.method)
        assertEquals("https://example.com", parsed?.args?.optString(0))

        // Invalid method name rejected
        assertNull(
            NativeMessageBridge.parseCall(
                """{"m":"../evil","a":[]}""",
                "example.com"
            )
        )
    }

    @Test
    fun testMediumAndLowAuditRemediations() {
        // M1: syncAlexShieldV5State must not be @Synchronized on the hot shouldInterceptRequest path
        val customWebViewSource = findSource("com/example/ui/components/CustomWebView.kt").readText()
        assertFalse(
            "syncAlexShieldV5State must not use method-level @Synchronized",
            customWebViewSource.contains("@Synchronized\n                        private fun syncAlexShieldV5State")
        )
        assertTrue(
            "CustomWebView must use shared mainHandler instead of per-call Handler allocations",
            customWebViewSource.contains("private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())")
        )
        assertFalse(
            "SSL error logger must not leak full error?.toString() URL to Log.e",
            customWebViewSource.contains("val errorDesc = error?.toString()")
        )

        // M2: SearchEngineManager must use https:// for bare localhost / IPv4 inputs to match cleartextTrafficPermitted=false
        assertEquals(
            "https://localhost:8080",
            SearchEngineManager.parseUrlOrQuery("localhost:8080", SearchEngine.GOOGLE)
        )
        assertEquals(
            "https://192.168.1.1",
            SearchEngineManager.parseUrlOrQuery("192.168.1.1", SearchEngine.GOOGLE)
        )

        // M3: AppDatabaseEncrypted must not duplicate @Database Room definition
        val dbEncryptedSource = findSource("com/example/data/AppDatabaseEncrypted.kt").readText()
        assertFalse(
            "AppDatabaseEncrypted must not be a duplicate @Database RoomDatabase class",
            dbEncryptedSource.contains("abstract class AppDatabaseEncrypted : RoomDatabase()")
        )
        assertTrue(
            "AppDatabaseEncrypted must remain a passphrase manager object",
            dbEncryptedSource.contains("object AppDatabaseEncrypted")
        )
    }

    @Test
    fun testAuditVulnerabilityRemediations() {
        val customWebViewSource = findSource("com/example/ui/components/CustomWebView.kt").readText()
        val securityGuardSource = findSource("com/example/manager/SecurityGuardEngine.kt").readText()
        val bridgeSource = findSource("com/example/ui/components/NativeMessageBridge.kt").readText()
        val viewModelSource = findSource("com/example/ui/viewmodel/BrowserViewModel.kt").readText()
        val faviconSource = findSource("com/example/manager/FaviconStore.kt").readText()
        val dbEncryptedSource = findSource("com/example/data/AppDatabaseEncrypted.kt").readText()

        // 1. Incognito session cookie & WebStorage isolation
        assertTrue(
            "BrowserViewModel must track private session hosts",
            viewModelSource.contains("_privateSessionHosts")
        )
        assertTrue(
            "BrowserViewModel must purge private cookies and web storage origins on close",
            viewModelSource.contains("webStorage.deleteOrigin(\"https://\$host\")")
        )

        // 2. Drive-by blob download token protection
        assertTrue(
            "downloadVerifiedBlob in CustomWebView must verify token",
            customWebViewSource.contains("pendingBlobDownloadTokens.remove(token.trim())")
        )
        assertTrue(
            "NativeMessageBridge shim must forward token for downloadVerifiedBlob",
            bridgeSource.contains("downloadVerifiedBlob: function(b, d, f, m, tok)")
        )

        // 3. Incognito favicon leak prevention
        assertTrue(
            "FaviconStore must not write to disk when isPrivate is true",
            faviconSource.contains("if (!isPrivate)")
        )
        assertTrue(
            "FaviconStore must provide cache purge for private session hosts",
            faviconSource.contains("fun clearPrivateSessionFavicons(hosts: Set<String>)")
        )
        assertTrue(
            "CustomWebView must forward tab.isPrivate to FaviconStore.put",
            customWebViewSource.contains("FaviconStore.put(view?.url, icon, tab.isPrivate)")
        )

        // 4. Intent scheme extra & custom category stripping
        assertTrue(
            "SecurityGuardEngine must strip custom categories except CATEGORY_BROWSABLE",
            securityGuardSource.contains("parsed.removeCategory(cat)")
        )
        assertTrue(
            "SecurityGuardEngine must strip untrusted intent extras",
            securityGuardSource.contains("parsed.replaceExtras(android.os.Bundle())")
        )

        // 5. Document-start script race condition fix
        assertTrue(
            "CustomWebView must install client security guard at document start",
            customWebViewSource.contains("SecurityGuardEngine.buildClientSecurityScript(") &&
                customWebViewSource.contains("addDocumentStartJavaScript")
        )

        // 6. Cryptographically secure 256-bit database passphrase
        assertTrue(
            "AppDatabaseEncrypted must use SecureRandom 256-bit token for database passphrase",
            dbEncryptedSource.contains("java.security.SecureRandom().nextBytes(randomBytes)")
        )

        // 7. User-initiated new-tab links are allowed while hidden popups without user gesture are blocked
        val clientScript = com.example.manager.SecurityGuardEngine.buildClientSecurityScript(
            blockClipboardHijack = true,
            blockHiddenPopups = true
        )
        assertTrue(
            "Client security script must gate window.open on hasRecentUserGesture() rather than unconditionally returning null",
            clientScript.contains("!hasRecentUserGesture()") &&
                clientScript.contains("origOpen(strUrl") &&
                clientScript.contains("noopener,noreferrer")
        )
        assertTrue(
            "CustomWebView onCreateWindow must only block non-gesture popups when blockHiddenPopups is enabled",
            customWebViewSource.contains("if (!isUserGesture && viewModel.blockHiddenPopups.value)")
        )
    }

    @Test
    fun testPriority1AuditFixes() {
        val extSource = findSource("com/example/manager/BrowserExtensionManager.kt").readText()
        val dlSource = findSource("com/example/manager/AppDownloadManager.kt").readText()
        val tabPersistenceSource = findSource("com/example/manager/TabPersistenceService.kt").readText()
        val customWebViewSource = findSource("com/example/ui/components/CustomWebView.kt").readText()
        val viewModelSource = findSource("com/example/ui/viewmodel/BrowserViewModel.kt").readText()
        val faviconSource = findSource("com/example/manager/FaviconStore.kt").readText()
        val securityGuardSource = findSource("com/example/manager/SecurityGuardEngine.kt").readText()
        val mainActivitySource = findSource("com/example/MainActivity.kt").readText()

        // 1. N-01: SSRF via TOCTOU DNS Rebinding eliminated by binding OkHttpClient to FilterListManager.SAFE_PUBLIC_DNS
        assertTrue(
            "BrowserExtensionManager must use FilterListManager.SAFE_PUBLIC_DNS",
            extSource.contains("FilterListManager.SAFE_PUBLIC_DNS")
        )
        assertFalse(
            "BrowserExtensionManager must not use unpinned HttpURLConnection.openConnection()",
            extSource.contains(".openConnection()")
        )
        assertTrue(
            "AppDownloadManager must use FilterListManager.SAFE_PUBLIC_DNS",
            dlSource.contains("FilterListManager.SAFE_PUBLIC_DNS")
        )
        assertFalse(
            "AppDownloadManager must not use unpinned HttpURLConnection.openConnection()",
            dlSource.contains(".openConnection()")
        )
        assertTrue(
            "TabPersistenceService must use FilterListManager.SAFE_PUBLIC_DNS",
            tabPersistenceSource.contains("FilterListManager.SAFE_PUBLIC_DNS")
        )
        assertFalse(
            "TabPersistenceService must not use unpinned HttpURLConnection.openConnection()",
            tabPersistenceSource.contains(".openConnection()")
        )

        // 2. N-02: Renderer crash deadlock recovery in onRenderProcessGone
        assertTrue(
            "CustomWebView onRenderProcessGone must destroy and remove crashed WebView",
            customWebViewSource.contains("view?.safeDestroy()") &&
                customWebViewSource.contains("viewModel.destroyAndRemoveWebView(tab.id)") &&
                customWebViewSource.contains("crashRecoveryCounter++")
        )

        // 3. H-04: Multi-Profile WebView incognito isolation and private favicon memory cache
        assertTrue(
            "CustomWebView must use WebViewFeature.MULTI_PROFILE for private tabs",
            customWebViewSource.contains("WebViewFeature.MULTI_PROFILE") &&
                customWebViewSource.contains("INCOGNITO_WEBVIEW_PROFILE_NAME")
        )
        assertTrue(
            "BrowserViewModel must purge isolated incognito WebView profile on close",
            viewModelSource.contains("WebViewFeature.MULTI_PROFILE") &&
                viewModelSource.contains("INCOGNITO_WEBVIEW_PROFILE_NAME")
        )
        assertTrue(
            "FaviconStore must isolate private favicons in dedicated privateCache",
            faviconSource.contains("privateCache")
        )

        // 4. N-06: Default browser ACTION_VIEW intent handling in MainActivity
        assertTrue(
            "MainActivity must handle incoming ACTION_VIEW intents safely",
            mainActivitySource.contains("handleIncomingBrowserIntent") &&
                mainActivitySource.contains("Intent.ACTION_VIEW")
        )

        // 5. H-02: Runtime Safe Browsing initialization & verification
        assertTrue(
            "SecurityGuardEngine must initialize Safe Browsing via WebViewCompat.startSafeBrowsing",
            securityGuardSource.contains("initSafeBrowsingIfSupported") &&
                securityGuardSource.contains("WebViewCompat.startSafeBrowsing")
        )

        // 6. H-03 / N-04: Extension sandbox hardening (closure-isolated GM storage & critical host protection)
        assertTrue(
            "GM_setValue/GM_getValue must use closure-private __gmPrivateStore instead of host localStorage",
            extSource.contains("__gmPrivateStore") &&
                !extSource.contains("localStorage.setItem('__gm_'")
        )
        assertTrue(
            "BrowserExtensionManager must block non-built-in extensions on critical protected hosts even on manual trigger",
            extSource.contains("SecurityGuardEngine.isCriticalProtectedHost(activeUrl)")
        )
    }

    @Test
    fun testPriority2AuditFixes() {
        val customWebViewSource = findSource("com/example/ui/components/CustomWebView.kt").readText()
        val securityGuardSource = findSource("com/example/manager/SecurityGuardEngine.kt").readText()
        val dlSource = findSource("com/example/manager/AppDownloadManager.kt").readText()
        val bridgeSource = findSource("com/example/ui/components/NativeMessageBridge.kt").readText()
        val viewModelSource = findSource("com/example/ui/viewmodel/BrowserViewModel.kt").readText()
        val devToolsModelsSource = findSource("com/example/devtools/DevToolsModels.kt").readText()

        // 1. N-03: Document-start injection for Incognito storage isolation and WebRTC leak protection
        assertTrue(
            "CustomWebView must register buildIncognitoStorageIsolationScript via addDocumentStartJavaScript",
            customWebViewSource.contains("SecurityGuardEngine.buildIncognitoStorageIsolationScript()") &&
                customWebViewSource.contains("SecurityGuardEngine.buildWebRtcProtectionScript(")
        )

        // 2. N-05: Non-destructive Web Audio noise injection in Strict Privacy Shield
        assertTrue(
            "Strict AFP script must wrap origCreateOscillator instead of returning a broken stub object",
            securityGuardSource.contains("origCreateOscillator.apply(this, arguments)") &&
                securityGuardSource.contains("AudioBuffer.prototype.getChannelData")
        )

        // 3. M-05 & L-08: Streamed Base64 data URI decoding (10 MB cap) and 32 KB control message cap in NativeMessageBridge
        assertEquals(
            10 * 1024 * 1024,
            com.example.manager.AppDownloadManager.MAX_DATA_URI_LENGTH
        )
        assertTrue(
            "AppDownloadManager must stream-decode Base64 data URIs via Base64InputStream",
            dlSource.contains("Base64InputStream")
        )
        assertTrue(
            "NativeMessageBridge must enforce MAX_CONTROL_MESSAGE_LENGTH for non-blob calls",
            bridgeSource.contains("MAX_CONTROL_MESSAGE_LENGTH")
        )
        val oversizedControlPayload = """{"m":"openUrlInNewTab","a":["https://example.com/${"a".repeat(40000)}"]}"""
        assertNull(
            "Oversized non-blob control message must be rejected by NativeMessageBridge.parseCall",
            NativeMessageBridge.parseCall(oversizedControlPayload, "example.com")
        )

        // 4. M-02: DevToolsStateController extracted from BrowserViewModel
        assertTrue(
            "DevToolsStateController must be extracted into DevToolsModels.kt and used by BrowserViewModel",
            devToolsModelsSource.contains("class DevToolsStateController") &&
                viewModelSource.contains("DevToolsStateController(")
        )
    }

    @Test
    fun testPriority3AuditFixes() {
        val filterManagerSource = findSource("com/example/manager/FilterListManager.kt").readText()
        val heuristicsSource = findSource("com/yourbrowser/adblock/HeuristicsEngine.kt").readText()
        val adBlockClientSource = findSource("com/yourbrowser/adblock/AdBlockWebViewClient.kt").readText()
        val customWebViewSource = findSource("com/example/ui/components/CustomWebView.kt").readText()
        val settingsSource = findSource("com/example/manager/SettingsManager.kt").readText()
        val frameSyncSource = findSource("com/example/manager/FrameMetricsTelemetrySync.kt").readText()

        // 1. M-04: Cooldown & exponential backoff for filter list downloads
        assertTrue(
            "FilterListManager must implement MIN_REFRESH_COOLDOWN_MS and computeBackoffDelayMs",
            filterManagerSource.contains("MIN_REFRESH_COOLDOWN_MS") &&
                filterManagerSource.contains("computeBackoffDelayMs")
        )
        assertEquals(30_000L, com.example.manager.FilterListManager.computeBackoffDelayMs(1))
        assertEquals(60_000L, com.example.manager.FilterListManager.computeBackoffDelayMs(2))

        // 2. M-10: Token-boundary matching for gambling URL keywords
        assertTrue(
            "HeuristicsEngine must match gambling URL keywords at token boundaries",
            heuristicsSource.contains("containsKeywordAtTokenBoundary")
        )
        assertTrue(
            com.yourbrowser.adblock.HeuristicsEngine.containsKeywordAtTokenBoundary(
                "/promo/slot-gacor/daftar",
                "slot-gacor"
            )
        )
        assertFalse(
            com.yourbrowser.adblock.HeuristicsEngine.containsKeywordAtTokenBoundary(
                "/articles/myslot-gacorx-review",
                "slot-gacor"
            )
        )

        // 3. L-02: Bounded decision cache key in AdBlockWebViewClient
        assertTrue(
            "AdBlockWebViewClient must bound decision cache keys via buildBoundedCacheKey",
            adBlockClientSource.contains("buildBoundedCacheKey")
        )

        // 4. L-07: MutableContextWrapper baseContext reset in WebView.safeDestroy()
        assertTrue(
            "safeDestroy must reset MutableContextWrapper baseContext to applicationContext",
            customWebViewSource.contains("wrapper.baseContext = wrapper.baseContext.applicationContext")
        )

        // 5. L-09: Lock-free AtomicLong counters in SettingsManager
        assertTrue(
            "SettingsManager must use AtomicLong for blocked statistics counters",
            settingsSource.contains("AtomicLong") &&
                !settingsSource.contains("@Synchronized\n    fun incrementTrackerCookies")
        )

        // 6. M-01: Plaintext documentation for XOR constants in FrameMetricsTelemetrySync
        assertTrue(
            "FrameMetricsTelemetrySync must document plaintext values for MOD_PKGS XOR arrays",
            frameSyncSource.contains("com.gmail.heagoo.apkeditor")
        )
    }

    @Test
    fun testAdvancedCryptographyUpgrade() {
        val keystoreCryptoSource = findSource("com/example/security/KeystoreCrypto.kt").readText()
        val securePrefsSource = findSource("com/example/security/SecurePreferences.kt").readText()
        val encPrefsSource = findSource("com/example/manager/EncryptedPreferencesManager.kt").readText()
        val dbEncryptedSource = findSource("com/example/data/AppDatabaseEncrypted.kt").readText()
        val extSource = findSource("com/example/manager/BrowserExtensionManager.kt").readText()
        val customJsSource = findSource("com/example/manager/CustomJsToolbarManager.kt").readText()

        // 1. Hardware-backed Keystore & AES-256-GCM verification
        assertTrue(
            "KeystoreCrypto must use StrongBox key property with TEE fallback",
            keystoreCryptoSource.contains("setIsStrongBoxBacked(true)")
        )
        assertTrue(
            "KeystoreCrypto must use AES/GCM/NoPadding cipher",
            keystoreCryptoSource.contains("AES/GCM/NoPadding")
        )
        assertTrue(
            "KeystoreCrypto must support AAD authentication data binding",
            keystoreCryptoSource.contains("cipher.updateAAD(aad)")
        )
        assertTrue(
            "KeystoreCrypto must support HMAC-SHA256 integrity tags",
            keystoreCryptoSource.contains("HmacSHA256")
        )

        // 2. Custom Keystore SecurePreferences replacement
        assertTrue(
            "EncryptedPreferencesManager must adopt SecurePreferences",
            encPrefsSource.contains("SecurePreferences.create(")
        )
        assertTrue(
            "SecurePreferences must use atomic temp file replacement",
            securePrefsSource.contains(".tmp")
        )

        // 3. Database key envelope encryption
        assertTrue(
            "AppDatabaseEncrypted must support getDatabasePassphraseBytes",
            dbEncryptedSource.contains("fun getDatabasePassphraseBytes")
        )
        assertTrue(
            "AppDatabaseEncrypted must support envelope wrapping via KeystoreCrypto",
            dbEncryptedSource.contains("KeystoreCrypto.wrapDatabaseKey") &&
                dbEncryptedSource.contains("KeystoreCrypto.unwrapDatabaseKey")
        )

        // 4. HMAC-SHA256 tamper prevention in extensions & custom scripts
        assertTrue(
            "BrowserExtensionManager must store and verify extensions HMAC",
            extSource.contains("KEY_EXTENSIONS_HMAC") &&
                extSource.contains("verifyIntegrityMac") &&
                extSource.contains("computeIntegrityMac")
        )
        assertTrue(
            "CustomJsToolbarManager must store and verify custom scripts HMAC",
            customJsSource.contains("KEY_SCRIPTS_HMAC") &&
                customJsSource.contains("verifyIntegrityMac") &&
                customJsSource.contains("computeIntegrityMac")
        )

        // 5. Functional crypto operations
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // HMAC computation and verification
        val payload = "alex_browser_security_payload_test_data".toByteArray(Charsets.UTF_8)
        val mac = com.example.security.KeystoreCrypto.computeIntegrityMac(payload, context)
        assertTrue(
            "HMAC tag must verify against original payload",
            com.example.security.KeystoreCrypto.verifyIntegrityMac(payload, mac, context)
        )
        val alteredPayload = "alex_browser_security_payload_tampered".toByteArray(Charsets.UTF_8)
        assertFalse(
            "HMAC tag must fail when payload is altered",
            com.example.security.KeystoreCrypto.verifyIntegrityMac(alteredPayload, mac, context)
        )

        // Keystore encryption & decryption with AAD binding
        val secretBytes = "super_secret_envelope_dek_content".toByteArray(Charsets.UTF_8)
        val aad = "test_bound_context_id".toByteArray(Charsets.UTF_8)
        val encrypted = com.example.security.KeystoreCrypto.encryptGcm(
            secretBytes,
            com.example.security.KeystoreCrypto.ALIAS_DB_KEK,
            aad,
            context
        )
        val decrypted = com.example.security.KeystoreCrypto.decryptGcm(
            encrypted,
            com.example.security.KeystoreCrypto.ALIAS_DB_KEK,
            aad,
            context
        )
        assertEquals("super_secret_envelope_dek_content", decrypted.toString(Charsets.UTF_8))

        var aadFailed = false
        try {
            com.example.security.KeystoreCrypto.decryptGcm(
                encrypted,
                com.example.security.KeystoreCrypto.ALIAS_DB_KEK,
                "wrong_aad_context".toByteArray(Charsets.UTF_8),
                context
            )
        } catch (_: Exception) {
            aadFailed = true
        }
        assertTrue("Decryption must fail with authentication error if AAD does not match", aadFailed)

        // SecurePreferences functional test
        val securePrefs = com.example.security.SecurePreferences.create(context, "test_crypto_storage")
        securePrefs.edit()
            .putString("crypto_test_key", "secret_value_123")
            .putInt("crypto_test_int", 999)
            .putBoolean("crypto_test_bool", true)
            .commit()

        assertEquals("secret_value_123", securePrefs.getString("crypto_test_key", null))
        assertEquals(999, securePrefs.getInt("crypto_test_int", 0))
        assertTrue(securePrefs.getBoolean("crypto_test_bool", false))

        val secureFile = java.io.File(context.noBackupFilesDir, "test_crypto_storage.json")
        if (secureFile.exists()) {
            val rawStorage = secureFile.readText()
            assertFalse(
                "Plaintext value must never be stored on disk",
                rawStorage.contains("secret_value_123")
            )
            assertFalse(
                "Plaintext key name must never be stored on disk",
                rawStorage.contains("crypto_test_key")
            )
        }

        // Database passphrase bytes
        val dbBytes = com.example.data.AppDatabaseEncrypted.getDatabasePassphraseBytes(context)
        assertTrue("Database passphrase bytes must be present", dbBytes.isNotEmpty())
    }
}

