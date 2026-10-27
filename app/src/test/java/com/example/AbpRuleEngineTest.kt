package com.example

import com.example.manager.AbpRuleEngine
import com.example.manager.CosmeticFilterInjector
import org.junit.Assert.*
import org.junit.Test

class AbpRuleEngineTest {

    @Test
    fun testDomainAndSubdomainBlocking() {
        val rules = listOf(
            "||doubleclick.net^",
            "||adservice.google.com^"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        assertTrue(ruleSet.isDomainBlocked("doubleclick.net"))
        assertTrue(ruleSet.isDomainBlocked("ad.doubleclick.net"))
        assertTrue(ruleSet.isDomainBlocked("sub.ad.doubleclick.net"))
        assertFalse(ruleSet.isDomainBlocked("notdoubleclick.net"))
        assertFalse(ruleSet.isDomainBlocked("google.com"))

        val match = ruleSet.shouldBlock("https://ad.doubleclick.net/ad.js", "example.com")
        assertNotNull(match)
        assertFalse(match!!.isAllow)
    }

    @Test
    fun testPathExceptionDoesNotWhitelistEntireDomain() {
        val rules = listOf(
            "||example.com^",
            "@@||example.com/allowed-login.js^"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // The allowed path should NOT be blocked
        val allowedMatch = ruleSet.shouldBlock("https://example.com/allowed-login.js", "example.com")
        assertNull("Path matching @@ must be allowed", allowedMatch)

        // Other paths on example.com MUST still be blocked!
        val blockedMatch = ruleSet.shouldBlock("https://example.com/ad.png", "example.com")
        assertNotNull("Other paths on example.com must remain blocked", blockedMatch)
    }

    @Test
    fun testThirdPartyOption() {
        val rules = listOf(
            "||tracker.com^\$third-party",
            "||firstparty-only.com^\$~third-party"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // tracker.com as third party -> blocked
        assertNotNull(ruleSet.shouldBlock("https://tracker.com/t.js", "news.com"))
        // tracker.com as first party -> allowed
        assertNull(ruleSet.shouldBlock("https://tracker.com/t.js", "tracker.com"))

        // firstparty-only.com as first party -> blocked
        assertNotNull(ruleSet.shouldBlock("https://firstparty-only.com/t.js", "firstparty-only.com"))
        // firstparty-only.com as third party -> allowed
        assertNull(ruleSet.shouldBlock("https://firstparty-only.com/t.js", "news.com"))
    }

    @Test
    fun testResourceTypeFiltering() {
        val rules = listOf(
            "||cdn.com/pixel.png\$image",
            "||cdn.com/app.js\$script"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // image type matched -> blocked
        assertNotNull(ruleSet.shouldBlock("https://cdn.com/pixel.png", "site.com", AbpRuleEngine.ResourceType.IMAGE))
        // same url requested as script -> not blocked by image rule
        assertNull(ruleSet.shouldBlock("https://cdn.com/pixel.png", "site.com", AbpRuleEngine.ResourceType.SCRIPT))

        // script type matched -> blocked
        assertNotNull(ruleSet.shouldBlock("https://cdn.com/app.js", "site.com", AbpRuleEngine.ResourceType.SCRIPT))
        assertNull(ruleSet.shouldBlock("https://cdn.com/app.js", "site.com", AbpRuleEngine.ResourceType.STYLESHEET))
    }

    @Test
    fun testDomainOptionConstraint() {
        val rules = listOf(
            "||banner.com^\$domain=news.com|blog.org",
            "||widget.com^\$domain=~safe-news.com"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // banner.com loaded from news.com -> blocked
        assertNotNull(ruleSet.shouldBlock("https://banner.com/img.jpg", "news.com"))
        // banner.com loaded from other.com -> allowed
        assertNull(ruleSet.shouldBlock("https://banner.com/img.jpg", "other.com"))

        // widget.com loaded from safe-news.com -> allowed (excluded)
        assertNull(ruleSet.shouldBlock("https://widget.com/w.js", "safe-news.com"))
        // widget.com loaded from regular site -> blocked
        assertNotNull(ruleSet.shouldBlock("https://widget.com/w.js", "random.com"))
    }

    @Test
    fun testRegexAndUnsupportedFiltersAreSkippedSafely() {
        val rules = listOf(
            "/ad[0-9]+/",
            "||example.com^\$redirect=noopjs",
            "||bad.com^\$badfilter",
            "||csp.com^\$csp=default-src 'none'",
            "#?#.unsupported-extended"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())
        assertEquals(5, ruleSet.totalRulesSkipped)
        assertEquals(0, ruleSet.allowRules.size)
        assertEquals(0, ruleSet.blockRules.size)
        assertEquals(0, ruleSet.domainOnlyBlock.size)
    }

    @Test
    fun testCosmeticFiltersGlobalDomainAndException() {
        val rules = listOf(
            "##.ad-banner",
            "news.com##.sponsor-post",
            "~safenews.com##.promoted",
            "special.news.com#@#.sponsor-post"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        val newsSelectors = ruleSet.cosmetic.selectorsFor("news.com")
        assertTrue(newsSelectors.contains(".ad-banner"))
        assertTrue(newsSelectors.contains(".sponsor-post"))

        val specialNewsSelectors = ruleSet.cosmetic.selectorsFor("special.news.com")
        assertTrue(specialNewsSelectors.contains(".ad-banner"))
        assertFalse(specialNewsSelectors.contains(".sponsor-post")) // un-hidden by #@#

        val otherSelectors = ruleSet.cosmetic.selectorsFor("blog.org")
        assertTrue(otherSelectors.contains(".ad-banner"))
        assertFalse(otherSelectors.contains(".sponsor-post"))
    }

    @Test
    fun testRegistrableDomainCoId() {
        assertEquals("tokopedia.com", AbpRuleEngine.getRegistrableDomain("www.tokopedia.com"))
        assertEquals("tokopedia.co.id", AbpRuleEngine.getRegistrableDomain("shop.tokopedia.co.id"))
        assertEquals("kompas.com", AbpRuleEngine.getRegistrableDomain("megapolitan.kompas.com"))
        assertEquals("bbc.co.uk", AbpRuleEngine.getRegistrableDomain("news.bbc.co.uk"))

        // isThirdParty comparison
        assertFalse(AbpRuleEngine.isThirdParty("api.tokopedia.co.id", "tokopedia.co.id"))
        assertTrue(AbpRuleEngine.isThirdParty("tracker.adnetwork.com", "tokopedia.co.id"))
    }

    @Test
    fun testInferType() {
        assertEquals(AbpRuleEngine.ResourceType.SCRIPT, AbpRuleEngine.inferType("https://example.com/app.js?v=1"))
        assertEquals(AbpRuleEngine.ResourceType.STYLESHEET, AbpRuleEngine.inferType("https://example.com/style.css"))
        assertEquals(AbpRuleEngine.ResourceType.IMAGE, AbpRuleEngine.inferType("https://example.com/logo.png"))
        assertEquals(AbpRuleEngine.ResourceType.MEDIA, AbpRuleEngine.inferType("https://example.com/stream.m3u8"))
        assertEquals(AbpRuleEngine.ResourceType.FONT, AbpRuleEngine.inferType("https://example.com/font.woff2"))
        assertEquals(AbpRuleEngine.ResourceType.WEBSOCKET, AbpRuleEngine.inferType("wss://example.com/socket"))
        assertEquals(AbpRuleEngine.ResourceType.PING, AbpRuleEngine.inferType("https://google-analytics.com/collect"))
        assertEquals(AbpRuleEngine.ResourceType.XMLHTTPREQUEST, AbpRuleEngine.inferType("https://example.com/api/data", "application/json"))
    }

    @Test
    fun genericRuleDoesNotBlockFirstPartyCss() {
        val ruleSet = AbpRuleEngine.build(sequenceOf("/banner."))
        val result = ruleSet.shouldBlock(
            url = "https://situs-saya.com/css/banner.css",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.STYLESHEET
        )
        assertNull("Generic rule without domain anchor must not block first-party CSS", result)
    }

    @Test
    fun genericRuleDoesNotBlockFirstPartyImage() {
        val ruleSet = AbpRuleEngine.build(sequenceOf("/banner."))
        val result = ruleSet.shouldBlock(
            url = "https://situs-saya.com/img/banner.png",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.IMAGE
        )
        assertNull("Generic rule without domain anchor must not block first-party image", result)
    }

    @Test
    fun thirdPartyGenericRuleCanStillBlock() {
        val ruleSet = AbpRuleEngine.build(sequenceOf("/banner."))
        val result = ruleSet.shouldBlock(
            url = "https://cdn.adnetwork.com/img/banner.png",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.IMAGE
        )
        assertNotNull("Generic rule must still block third-party ad resource", result)
        assertFalse(result!!.isAllow)
    }

    @Test
    fun thirdPartyOptionRuleDoesNotBlockFirstParty() {
        val ruleSet = AbpRuleEngine.build(sequenceOf("/banner.\$third-party"))
        val firstPartyResult = ruleSet.shouldBlock(
            url = "https://situs-saya.com/img/banner.png",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.IMAGE
        )
        assertNull("Rule with \$third-party must not block first-party resource", firstPartyResult)

        val thirdPartyResult = ruleSet.shouldBlock(
            url = "https://cdn.adnetwork.com/img/banner.png",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.IMAGE
        )
        assertNotNull("Rule with \$third-party must block third-party resource", thirdPartyResult)
    }

    @Test
    fun domainAnchoredRuleStillWorksOnFirstParty() {
        val ruleSet = AbpRuleEngine.build(
            sequenceOf(
                "||situs-saya.com/ads/banner.js",
                "|https://situs-saya.com/ads/popup.js",
                "/promo-track.js\$domain=situs-saya.com",
                "/self-ad.js\$~third-party"
            )
        )

        val anchoredResult = ruleSet.shouldBlock(
            url = "https://situs-saya.com/ads/banner.js",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.SCRIPT
        )
        assertNotNull("Domain-anchored rule ||domain/path must still block on first-party", anchoredResult)

        val schemeAnchoredResult = ruleSet.shouldBlock(
            url = "https://situs-saya.com/ads/popup.js",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.SCRIPT
        )
        assertNotNull("Start-anchored scheme+host rule |https://domain/path must still block on first-party", schemeAnchoredResult)

        val domainOptionResult = ruleSet.shouldBlock(
            url = "https://situs-saya.com/js/promo-track.js",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.SCRIPT
        )
        assertNotNull("Rule with explicit \$domain=situs-saya.com must still block on first-party", domainOptionResult)

        val explicitFirstPartyResult = ruleSet.shouldBlock(
            url = "https://situs-saya.com/js/self-ad.js",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.SCRIPT
        )
        assertNotNull("Rule with explicit \$~third-party must still block on first-party", explicitFirstPartyResult)
    }

    @Test
    fun exceptionRuleOverridesBlockingRule() {
        val ruleSet = AbpRuleEngine.build(
            sequenceOf(
                "||adnetwork.com/banner.png",
                "@@||adnetwork.com/banner.png"
            )
        )
        val result = ruleSet.shouldBlock(
            url = "https://adnetwork.com/banner.png",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.IMAGE
        )
        assertNull("Exception rule @@ must override blocking rule", result)
    }

    @Test
    fun nonMatchingUrlIsNotBlocked() {
        val ruleSet = AbpRuleEngine.build(sequenceOf("/banner."))
        val result = ruleSet.shouldBlock(
            url = "https://cdn.adnetwork.com/js/app.js",
            sourceHost = "situs-saya.com",
            resourceType = AbpRuleEngine.ResourceType.SCRIPT
        )
        assertNull("Non-matching URL must not be blocked", result)
    }

    @Test
    fun cosmeticSelectorRejectsGenericBannerAndModalSubstrings() {
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("[class*=\"banner\"]"))
        assertFalse(AbpRuleEngine.isSafeCosmeticSelector("[class*=\"banner\"]"))

        assertFalse(CosmeticFilterInjector.isSafeCssSelector("[id*=\"modal\"]"))
        assertFalse(AbpRuleEngine.isSafeCosmeticSelector("[id*=\"modal\"]"))

        assertFalse(CosmeticFilterInjector.isSafeCssSelector("div[class*=\"popup\"]"))
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("div[id*=\"overlay\"]"))
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("[class*=\"dialog\"]"))
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("[class*=\"carousel\"]"))
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("[class*=\"player\"]"))
    }

    @Test
    fun cosmeticSelectorRejectsBareTagsUniversalAndEmptyAttribute() {
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("div"))
        assertFalse(AbpRuleEngine.isSafeCosmeticSelector("div"))

        assertFalse(CosmeticFilterInjector.isSafeCssSelector("body"))
        assertFalse(AbpRuleEngine.isSafeCosmeticSelector("body"))

        assertFalse(CosmeticFilterInjector.isSafeCssSelector("*"))
        assertFalse(AbpRuleEngine.isSafeCosmeticSelector("*"))

        assertFalse(CosmeticFilterInjector.isSafeCssSelector("[class*=\"\"]"))
        assertFalse(AbpRuleEngine.isSafeCosmeticSelector("[class*=\"\"]"))
    }

    @Test
    fun cosmeticSelectorAllowsSpecificAdSelectors() {
        assertTrue(CosmeticFilterInjector.isSafeCssSelector(".adsbox"))
        assertTrue(AbpRuleEngine.isSafeCosmeticSelector(".adsbox"))

        assertTrue(CosmeticFilterInjector.isSafeCssSelector("#ad-slot-1"))
        assertTrue(AbpRuleEngine.isSafeCosmeticSelector("#ad-slot-1"))

        assertTrue(CosmeticFilterInjector.isSafeCssSelector("[class*=\"ads-widget\"]"))
        assertTrue(AbpRuleEngine.isSafeCosmeticSelector("[class*=\"ads-widget\"]"))

        assertTrue(CosmeticFilterInjector.isSafeCssSelector("div[id^=\"google_ads_\"]"))
        assertTrue(AbpRuleEngine.isSafeCosmeticSelector("div[id^=\"google_ads_\"]"))
    }

    @Test
    fun cosmeticRuleSetFiltersUnsafeSelectorsBeforeQuota() {
        val rules = sequenceOf(
            "##[class*=\"banner\"]",
            "##[id*=\"modal\"]",
            "##div",
            "##body",
            "##*",
            "##[class*=\"\"]",
            "##.adsbox",
            "###ad-slot-1",
            "##[class*=\"ads-widget\"]",
            "##div[id^=\"google_ads_\"]"
        )
        val ruleSet = AbpRuleEngine.build(rules)
        val selectors = ruleSet.cosmetic.selectorsFor("example.com")

        assertFalse(selectors.contains("[class*=\"banner\"]"))
        assertFalse(selectors.contains("[id*=\"modal\"]"))
        assertFalse(selectors.contains("div"))
        assertFalse(selectors.contains("body"))
        assertFalse(selectors.contains("*"))
        assertFalse(selectors.contains("[class*=\"\"]"))

        assertTrue(selectors.contains(".adsbox"))
        assertTrue(selectors.contains("#ad-slot-1"))
        assertTrue(selectors.contains("[class*=\"ads-widget\"]"))
        assertTrue(selectors.contains("div[id^=\"google_ads_\"]"))
    }
}
