package com.example

import com.example.manager.ScriptletEngine
import com.yourbrowser.adblock.CleanAdEngine
import com.yourbrowser.adblock.HeuristicsEngine
import org.junit.Assert.*
import org.junit.Test

class EngineTest {

    @Test
    fun testScriptletRulesAreParsedNotDiscarded() {
        val rules = listOf(
            "example.com##+js(set-constant, adsbygoogle, true)",
            "example.com##+js(abort-on-property-read, adBlockDetected)",
            "other.com##+js(set-constant, foo, 123)"
        )
        val ruleSet = CleanAdEngine.build(rules.asSequence())
        val forExample = ruleSet.scriptlets.scriptletsFor("www.example.com")
        assertEquals(2, forExample.size)
        assertTrue(forExample.any { it.name == "set-constant" && it.args == listOf("adsbygoogle", "true") })
        assertTrue(forExample.any { it.name == "abort-on-property-read" })
        // Aturan other.com tidak berlaku untuk example.com
        assertTrue(ruleSet.scriptlets.scriptletsFor("example.com").none { it.args.contains("foo") })
    }

    @Test
    fun testScriptletExceptionRemovesRule() {
        val rules = listOf(
            "example.com##+js(set-constant, foo, true)",
            "example.com#@#+js(set-constant, foo, true)"
        )
        val ruleSet = CleanAdEngine.build(rules.asSequence())
        assertTrue(ruleSet.scriptlets.scriptletsFor("example.com").isEmpty())
    }

    @Test
    fun testUnsupportedScriptletIsNotExecuted() {
        val rules = listOf(
            "example.com##+js(some-unknown-scriptlet, foo)"
        )
        val ruleSet = CleanAdEngine.build(rules.asSequence())
        // Diparsing (tersimpan), tapi scriptletsFor() menyaringnya karena
        // tidak ada di ScriptletEngine.SUPPORTED_SCRIPTLETS.
        assertTrue(ruleSet.scriptlets.scriptletsFor("example.com").isEmpty())
    }

    @Test
    fun testAdGuardScriptletSyntax() {
        val parsed = ScriptletEngine.parseScriptletRule(
            "example.com#%#//scriptlet('set-constant', 'foo', 'true')"
        )
        assertNotNull(parsed)
        assertEquals("set-constant", parsed!!.name)
        assertEquals(listOf("foo", "true"), parsed.args)
    }

    @Test
    fun testScriptletInvocationJsEscapesQuotes() {
        val ruleSet = ScriptletEngine.ScriptletRuleSet(
            listOf(
                ScriptletEngine.ScriptletRule(
                    setOf("example.com"), "set-constant", listOf("foo", "a\"b"), false
                )
            )
        )
        val js = ScriptletEngine.buildInvocationJs("example.com", ruleSet)
        assertTrue(js.contains("set-constant"))
        assertTrue(js.contains("a\\\"b"))
        assertFalse(js.contains("a\"b"))
    }

    @Test
    fun testHeuristicDomain() {
        assertEquals(
            "heuristic-domain",
            HeuristicsEngine.heuristicReason(
                "ads.doubleclick.net", "https://ads.doubleclick.net/x.js", false
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                "news.example.com", "https://news.example.com/article", false
            )
        )
    }

    @Test
    fun testHeuristicGamblingAndPathThirdPartyOnly() {
        // Third-party + kata kunci gambling di host -> blok (label utuh untuk kata pendek)
        assertEquals(
            "heuristic-gambling",
            HeuristicsEngine.heuristicReason(
                "kubet.example.com", "https://kubet.example.com/", false
            )
        )
        // First-party dengan path iklan -> TIDAK diblok lapisan heuristik
        // (ABP tetap yang memutuskan; ini sengaja untuk melindungi
        // first-party yang sah, berbeda dari  asli).
        assertNull(
            HeuristicsEngine.heuristicReason(
                "news.example.com", "https://news.example.com/popunder/page", true
            )
        )
        // Third-party dengan pola path -> blok
        assertEquals(
            "heuristic-path",
            HeuristicsEngine.heuristicReason(
                "cdn.example.com", "https://cdn.example.com/popunder.js", false
            )
        )
    }

    @Test
    fun testAbpExceptionStillWinsOverHeuristicDomain() {
        val rules = listOf(
            "@@||doubleclick.net^",
            "||doubleclick.net^"
        )
        val ruleSet = CleanAdEngine.build(rules.asSequence())
        assertNull(ruleSet.shouldBlock("https://doubleclick.net/ad.js", "news.com"))
        assertTrue(ruleSet.matchesAllowRule("https://doubleclick.net/ad.js", "news.com"))
        // Tanpa exception, matchesAllowRule harus false.
        val rules2 = listOf("||doubleclick.net^")
        val ruleSet2 = CleanAdEngine.build(rules2.asSequence())
        assertFalse(ruleSet2.matchesAllowRule("https://doubleclick.net/ad.js", "news.com"))
    }

    @Test
    fun testAb03HeuristicsMandatoryScenarios() {
        // 1. https://cdn.123bank.co.id/app.js pada 123bank.co.id -> tidak terblokir
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "cdn.123bank.co.id",
                lowerUrl = "https://cdn.123bank.co.id/app.js",
                isFirstParty = true,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "cdn.123bank.co.id",
                lowerUrl = "https://cdn.123bank.co.id/app.js",
                isFirstParty = false,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )
        assertFalse(HeuristicsEngine.matchesDomainKeyword("cdn.123bank.co.id", "123b"))
        assertTrue(HeuristicsEngine.matchesDomainKeyword("123b.casino.example", "123b"))
        assertTrue(HeuristicsEngine.matchesDomainKeyword("play-123b.casino.example", "123b"))

        // 2. https://cdn.jsdelivr.net/npm/tag.min.js pada situs-saya.com -> tidak terblokir
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "cdn.jsdelivr.net",
                lowerUrl = "https://cdn.jsdelivr.net/npm/tag.min.js",
                isFirstParty = false,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )

        // 3. https://situs-saya.com/js/bn.js pada situs-saya.com (maupun third-party CDN) -> tidak terblokir
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "situs-saya.com",
                lowerUrl = "https://situs-saya.com/js/bn.js",
                isFirstParty = true,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "cdn.situs-lain.com",
                lowerUrl = "https://cdn.situs-lain.com/js/bn.js",
                isFirstParty = false,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )

        // 4. https://situs-saya.com/static/pop.js pada situs-saya.com (maupun third-party CDN) -> tidak terblokir
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "situs-saya.com",
                lowerUrl = "https://situs-saya.com/static/pop.js",
                isFirstParty = true,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                host = "cdn.situs-lain.com",
                lowerUrl = "https://cdn.situs-lain.com/static/pop.js",
                isFirstParty = false,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )

        // 5. https://ads.doubleclick.net/pagead/ads.js pada situs-saya.com -> terblokir
        assertEquals(
            "heuristic-domain",
            HeuristicsEngine.heuristicReason(
                host = "ads.doubleclick.net",
                lowerUrl = "https://ads.doubleclick.net/pagead/ads.js",
                isFirstParty = false,
                resType = CleanAdEngine.ResourceType.SCRIPT
            )
        )
    }

    @Test
    fun testAb04AntiAdblockNeutralizerProtectsLegitimateDialogs() {
        // 1. Modal login tidak dianggap anti-adblock
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "modal login-dialog auth-overlay",
                textContent = "Sign in to continue to your account"
            )
        )
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "adblock-overlay",
                textContent = "Enter your password and OTP code",
                hasProtectedFormOrInput = true
            )
        )

        // 2. Cookie consent / GDPR tidak dianggap anti-adblock
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "cookie-consent-modal gdpr-banner",
                textContent = "We use cookies to personalize content and respect your privacy."
            )
        )

        // 3. Paywall / langganan tidak dianggap anti-adblock
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "paywall-overlay subscribe-modal",
                textContent = "Subscribe now to unlock unlimited articles and checkout."
            )
        )

        // 4. Age-gate / konfirmasi umur tidak dianggap anti-adblock
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "age-gate-modal",
                textContent = "Please confirm you are 18+ adult to view this content."
            )
        )

        // 5. Overlay dengan class .fc-ab-root atau teks "Please disable your adblock" terdeteksi sebagai anti-adblock
        assertTrue(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "fc-ab-root",
                textContent = ""
            )
        )
        assertTrue(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "custom-wall-dialog",
                textContent = "Adblock detected! Please disable your adblock or allow ads to continue."
            )
        )
        assertTrue(
            com.yourbrowser.adblock.AdBlockWebViewClient.isAntiAdblockModalCandidate(
                classAndId = "notice-box",
                textContent = "Mohon matikan adblock atau nonaktifkan adblock untuk membaca."
            )
        )

        // 6. Pastikan script JS memuat daftar penjaga konteks penting dan hanya unlockScroll jika defused > 0
        val script = com.yourbrowser.adblock.AdBlockWebViewClient.ANTI_ADBLOCK_NEUTRALIZER_SCRIPT
        assertTrue(script.contains("isProtectedModal"))
        assertTrue(script.contains("hasPositiveAntiAdblockSignal"))
        assertTrue(script.contains("if(defused>0)"))
        assertTrue(script.contains("disable your adblock"))
        assertTrue(script.contains("paywall"))
        assertTrue(script.contains("cookie"))
        assertTrue(script.contains("login"))
    }

    @Test
    fun testAb05BaseCleanupScriptProtectsFirstPartyAndVideoIframes() {
        // 1. First-party iframe tidak dibersihkan walaupun URL mengandung kata kunci iklan
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://situs-saya.com/embed/player.html?ad=doubleclick",
                pageHost = "www.situs-saya.com"
            )
        )
        // Relative first-party iframe tidak dibersihkan
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "/embed/video-player.html",
                pageHost = "situs-saya.com"
            )
        )

        // 2. Third-party video player embed (YouTube, Vimeo, Dailymotion, Twitch) tidak dibersihkan
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://www.youtube.com/embed/dQw4w9WgXcQ",
                pageHost = "situs-saya.com"
            )
        )
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://player.vimeo.com/video/123456",
                pageHost = "situs-saya.com"
            )
        )

        // 3. Elemen di dalam container video player tidak dibersihkan
        assertFalse(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://googleads.g.doubleclick.net/pagead/ads",
                pageHost = "situs-saya.com",
                isInsideVideoPlayer = true
            )
        )

        // 4. Third-party iframe dengan domain iklan jelas (doubleclick, googlesyndication, taboola, criteo) dibersihkan
        assertTrue(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://googleads.g.doubleclick.net/pagead/ads?client=ca-pub-123",
                pageHost = "situs-saya.com"
            )
        )
        assertTrue(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://tpc.googlesyndication.com/safeframe/1-0-40/html/container.html",
                pageHost = "situs-saya.com"
            )
        )
        assertTrue(
            com.yourbrowser.adblock.AdBlockWebViewClient.shouldCleanIframeInBaseCleanup(
                iframeSrc = "https://cdn.taboola.com/libtrc/impl.html",
                pageHost = "situs-saya.com"
            )
        )

        // 5. Verifikasi script Balanced memakai stealth hide dan melindungi VIDEO/AUDIO/player
        val balancedScript = com.yourbrowser.adblock.AdBlockWebViewClient.buildBaseCleanupScript(aggressive = false)
        assertTrue(balancedScript.contains("var AGG=false;"))
        assertTrue(balancedScript.contains("isProtectedVideoOrPlayer"))
        assertTrue(balancedScript.contains("regDomain(iHost)===regDomain(pHost)"))
        assertTrue(balancedScript.contains("SAFE_EMBED_DOMAINS"))
    }
}
