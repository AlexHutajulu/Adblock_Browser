package com.example

import com.example.manager.BackgroundMediaController
import com.example.manager.CertificatePinning
import com.example.manager.SearchEngine
import com.example.manager.SearchEngineManager
import com.example.ui.components.NativeMessageBridge
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
}

