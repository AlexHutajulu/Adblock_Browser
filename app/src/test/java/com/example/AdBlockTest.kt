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
}



