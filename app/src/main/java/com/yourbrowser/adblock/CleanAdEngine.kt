package com.yourbrowser.adblock

import com.example.manager.AbpRuleEngine
import com.example.manager.SiteDomainPolicy
import java.util.Locale

enum class AdblockMode { BALANCED, AGGRESSIVE }

object CleanAdEngine {
    const val MAX_RULE_LINE_LENGTH = AbpRuleEngine.MAX_RULE_LINE_LENGTH
    const val MAX_COSMETIC_SELECTORS_PER_HOST = AbpRuleEngine.MAX_COSMETIC_SELECTORS_PER_HOST

    @Volatile
    var currentMode: AdblockMode = AdblockMode.BALANCED

    private val adKeywords = listOf(
        "/ads/", "/banner/", "doubleclick.net", "adsystem", "adserver",
        "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "/pagead/", "adnxs.com", "criteo.com", "taboola.com", "outbrain.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "adroll.com",
        "propellerads.com", "exoclick.com", "adsterra.com", "popads.net",
        "popcash.net", "mgid.com", "revcontent.com", "adcash.com",
        "smartadserver.com", "moatads.com", "fundingchoicesmessages.google.com"
    )
    private val trackerKeywords = listOf(
        "analytics", "pixel", "telemetry", "beacon", "tracker",
        "hotjar.com", "clarity.ms", "mixpanel.com", "amplitude.com",
        "segment.io", "fullstory.com", "logrocket.com", "mouseflow.com",
        "scorecardresearch.com", "quantserve.com", "/ptracking",
        "/youtubei/v1/log_event"
    )

    fun shouldBlock(url: String, pageHost: String, isThirdParty: Boolean): Boolean {
        val lowerUrl = url.lowercase(Locale.US)

        if (currentMode == AdblockMode.BALANCED) {
            if (!isThirdParty) return false
            return adKeywords.any { lowerUrl.contains(it) }
        }

        if (currentMode == AdblockMode.AGGRESSIVE) {
            val isAd = adKeywords.any { lowerUrl.contains(it) }
            val isTracker = trackerKeywords.any { lowerUrl.contains(it) }
            return isAd || isTracker
        }

        return false
    }

    fun cleanHost(host: String?): String = AbpRuleEngine.cleanHost(host)

    fun extractHost(url: String?): String = AbpRuleEngine.extractHost(url)

    fun getRegistrableDomain(host: String): String = SiteDomainPolicy.registrableDomain(host)

    fun isThirdParty(requestHost: String, pageHost: String): Boolean =
        SiteDomainPolicy.isThirdParty(requestHost = requestHost, sourceHost = pageHost)

    fun isSafeCosmeticSelector(selector: String): Boolean =
        AbpRuleEngine.isSafeCosmeticSelector(selector)

    fun inferType(url: String, acceptHeader: String? = null): AbpRuleEngine.ResourceType =
        AbpRuleEngine.inferType(url, acceptHeader)

    fun build(lines: Sequence<String>): AbpRuleEngine.RuleSet = AbpRuleEngine.build(lines)
}
