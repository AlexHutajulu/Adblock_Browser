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
}
