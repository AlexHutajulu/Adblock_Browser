package com.yourbrowser.adblock

import com.example.manager.AbpRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeuristicsEngineTest {

    @Test
    fun blocksKnownAdRootDomains() {
        val reason = HeuristicsEngine.heuristicReason(
            host = "pagead2.googlesyndication.com",
            lowerUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            isFirstParty = false,
            resType = AbpRuleEngine.ResourceType.SCRIPT,
            aggressive = false
        )
        assertEquals("heuristic-domain", reason)
    }

    @Test
    fun doesNotBlockFirstPartyBundleOnPathPattern() {
        val reason = HeuristicsEngine.heuristicReason(
            host = "cdn.tokoonline.co.id",
            lowerUrl = "https://cdn.tokoonline.co.id/static/js/main.bundle.js",
            isFirstParty = true,
            resType = AbpRuleEngine.ResourceType.SCRIPT,
            aggressive = true
        )
        assertNull(reason)
    }

    @Test
    fun balanceModeIgnoresImageForPathHeuristics() {
        val reasonBalance = HeuristicsEngine.heuristicReason(
            host = "img.thirdparty-cdn.org",
            lowerUrl = "https://img.thirdparty-cdn.org/pagead/banner.png",
            isFirstParty = false,
            resType = AbpRuleEngine.ResourceType.IMAGE,
            aggressive = false
        )
        assertNull(reasonBalance)

        val reasonAggressive = HeuristicsEngine.heuristicReason(
            host = "img.thirdparty-cdn.org",
            lowerUrl = "https://img.thirdparty-cdn.org/pagead/banner.png",
            isFirstParty = false,
            resType = AbpRuleEngine.ResourceType.IMAGE,
            aggressive = true
        )
        assertEquals("heuristic-path", reasonAggressive)
    }
}
