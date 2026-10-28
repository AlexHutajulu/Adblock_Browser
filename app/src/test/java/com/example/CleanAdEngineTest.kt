package com.example

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.safeDestroy
import com.yourbrowser.adblock.AdBlockWebViewClient
import com.yourbrowser.adblock.AdblockMode
import com.yourbrowser.adblock.AdguardDnsClient
import com.yourbrowser.adblock.AdguardDnsResolver
import com.yourbrowser.adblock.AlexShieldV5LogStore
import com.yourbrowser.adblock.CleanAdEngine
import com.yourbrowser.adblock.DomainClassifier
import com.yourbrowser.adblock.PrivacyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CleanAdEngineTest {

    private fun fakeRequest(
        urlStr: String,
        isMainFrame: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): WebResourceRequest {
        val parsed = Uri.parse(urlStr)
        return object : WebResourceRequest {
            override fun getUrl(): Uri = parsed
            override fun isForMainFrame(): Boolean = isMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = headers.toMutableMap()
        }
    }

    @Test
    fun testT1DomainClassifierPublicSuffixAndPlatformDomains() {
        // Multi-part Indonesian & common public suffixes
        assertEquals("detik.co.id", DomainClassifier.getBaseDomain("news.detik.co.id"))
        assertEquals("kampus.ac.id", DomainClassifier.getBaseDomain("portal.kampus.ac.id"))
        assertFalse(DomainClassifier.isThirdParty("www.detik.co.id", "cdn.detik.co.id"))

        // Platform domains: distinct subdomains must be treated as third-party to each other
        assertEquals("alice.github.io", DomainClassifier.getBaseDomain("alice.github.io"))
        assertEquals("bob.github.io", DomainClassifier.getBaseDomain("bob.github.io"))
        assertTrue(DomainClassifier.isThirdParty("alice.github.io", "bob.github.io"))
        assertTrue(DomainClassifier.isThirdParty("myblog.blogspot.com", "evil.blogspot.com"))
    }

    @Test
    fun testT2PrivateTabNeverLogsBlockedUrlsAndConcurrentQueueCap() {
        AlexShieldV5LogStore.clear()
        val tabId = "private_tab_audit_test"

        AlexShieldV5LogStore.add("ad-domain", "https://doubleclick.net/ad.js?rand=12345", isPrivate = true)
        AlexShieldV5LogStore.addLogEntry("https://example.com", "doubleclick.net", "Ad", isPrivateTab = true)
        AlexShieldV5LogStore.add(
            tabId = tabId,
            pageUrl = "https://example.com",
            blockedUrl = "https://doubleclick.net/ad.js",
            reason = "ad-domain",
            isPrivateTab = true
        )
        assertTrue(
            "Private tab must never store blocked URL logs in memory (Solusi T2)",
            AlexShieldV5LogStore.forTab(tabId).isEmpty()
        )
        assertTrue(AlexShieldV5LogStore.getLogsSnapshot().isEmpty())

        // Verify MAX_LOGS = 200 cap and query string sanitization in AlexShieldV5LogStore
        for (i in 1..250) {
            AlexShieldV5LogStore.add(
                reason = "ad-engine",
                url = "https://ads.example.net/banner_$i.js?token=secret_$i",
                isPrivate = false
            )
        }
        val snapshot = AlexShieldV5LogStore.getLogsSnapshot()
        assertEquals("ConcurrentLinkedDeque log store must cap at 200 entries", 200, snapshot.size)
        assertFalse("Sanitized log entry must strip query string", snapshot.first().contains("?token="))
        AlexShieldV5LogStore.clear()
    }

    @Test
    fun testT4CleanAdEngineBalancedVsAggressiveModes() {
        // BALANCED MODE: Never block first-party, even if URL has ad/tracker keywords
        CleanAdEngine.currentMode = AdblockMode.BALANCED
        assertFalse(
            "First-party resource must never be blocked in BALANCED mode (Solusi T4)",
            CleanAdEngine.shouldBlock(
                url = "https://example.com/ads/analytics-pixel.js",
                pageHost = "example.com",
                isThirdParty = false
            )
        )
        // Third-party real ad is blocked in BALANCED mode
        assertTrue(
            CleanAdEngine.shouldBlock(
                url = "https://doubleclick.net/ads/banner.js",
                pageHost = "example.com",
                isThirdParty = true
            )
        )
        // Third-party pure tracker without ad keyword is NOT blocked by CleanAdEngine in BALANCED mode
        assertFalse(
            CleanAdEngine.shouldBlock(
                url = "https://metrics.example.net/analytics/telemetry.js",
                pageHost = "example.com",
                isThirdParty = true
            )
        )

        // AGGRESSIVE MODE: Blocks both ads and trackers, including first-party tracker scripts
        CleanAdEngine.currentMode = AdblockMode.AGGRESSIVE
        assertTrue(
            CleanAdEngine.shouldBlock(
                url = "https://example.com/js/analytics-tracker.js",
                pageHost = "example.com",
                isThirdParty = false
            )
        )
        assertTrue(
            CleanAdEngine.shouldBlock(
                url = "https://metrics.example.net/pixel/beacon.js",
                pageHost = "example.com",
                isThirdParty = true
            )
        )
        CleanAdEngine.currentMode = AdblockMode.BALANCED
    }

    @Test
    fun testT4AndT5WebViewClientFailOpenAndSafeDomainsPriority() {
        val client = AdBlockWebViewClient(mode = PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://espn.example.com/match")
        assertEquals("espn.example.com", client.currentPageHost)

        // First-party stats asset in BALANCED mode must fail-open (return null, never emptyResponse)
        val fpResponse = client.shouldInterceptRequest(
            null,
            fakeRequest("https://espn.example.com/assets/player-stats.json")
        )
        assertNull("First-party asset must return null (Fail-open T4)", fpResponse)

        // Safe domain must be checked first and never blocked even in AGGRESSIVE mode
        client.setMode(PrivacyMode.AGGRESSIVE)
        val safeResponse = client.shouldInterceptRequest(
            null,
            fakeRequest("https://cdn.jsdelivr.net/npm/axios/dist/axios.min.js")
        )
        assertNull("SAFE_DOMAINS must never be blocked (Solusi T5)", safeResponse)
    }

    @Test
    fun testAdguardDnsResolverLruCacheSinkholeBlockingAndWebViewSafeDestroy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AdguardDnsResolver.init(context)
        AdguardDnsClient.init(context)
        assertTrue(AdguardDnsResolver.isInitialized())
        assertTrue(AdguardDnsClient.isInitialized())

        AdguardDnsResolver.clearCache()
        AdguardDnsResolver.cacheHostDecision("sinkholed-ad.example.org", true)
        AdguardDnsResolver.cacheHostDecision("allowed-cdn.example.org", false)

        assertTrue(AdguardDnsResolver.isDomainBlockedByDns("sinkholed-ad.example.org"))
        assertFalse(AdguardDnsResolver.isDomainBlockedByDns("allowed-cdn.example.org"))

        val client = AdBlockWebViewClient(mode = PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://news.example.com")

        // Third-party domain sinkholed by AdGuard DNS Resolver is blocked
        val dnsBlockedResp = client.shouldInterceptRequest(
            null,
            fakeRequest("https://sinkholed-ad.example.org/script.js?cachebust=999")
        )
        assertNotNull("Domain sinkholed by AdGuard DNS Resolver must be blocked", dnsBlockedResp)

        // Allowed third-party domain returns null so native C++ Chromium handles networking
        val allowedResp = client.shouldInterceptRequest(
            null,
            fakeRequest("https://allowed-cdn.example.org/app.js?v=1")
        )
        assertNull("Allowed request must return null for native Chromium networking", allowedResp)

        AdguardDnsResolver.clearCache()

        // Verify WebView.safeDestroy() runs cleanly without throwing
        val webView = WebView(context)
        webView.safeDestroy()
    }
}
