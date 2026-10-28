package com.yourbrowser.adblock

import com.example.manager.AbpRuleEngine
import java.util.Locale

/**
 * Lapisan heuristik mandiri (tanpa ketergantungan pada daftar domain proyek lain).
 *
 * Aturan operasi:
 *  - Domain iklan pada [KNOWN_AD_ROOT_DOMAINS] hanya berisi domain induk jaringan iklan
 *    umum yang terdokumentasi publik (IAB/dokumentasi vendor).
 *  - Di mode Balance ([aggressive] = false), pola path hanya berlaku untuk
 *    request third-party bertipe SCRIPT atau SUBDOCUMENT.
 *  - Di mode Aggressive ([aggressive] = true), pola path juga berlaku untuk
 *    XMLHTTPREQUEST dan IMAGE third-party.
 */
object HeuristicsEngine {

    private val KNOWN_AD_ROOT_DOMAINS: Set<String> = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "moatads.com",
        "adsafeprotected.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "adform.net",
        "smartadserver.com",
        "teads.tv",
        "sharethrough.com",
        "indexexchange.com",
        "33across.com",
        "bidswitch.net",
        "media.net",
        "adroll.com",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "exosrv.com",
        "popads.net",
        "popcash.net",
        "adcash.com",
        "hilltopads.net",
        "hilltopads.com",
        "clickadu.com",
        "trafficjunky.com",
        "trafficstars.com",
        "juicyads.com",
        "ero-advertising.com",
        "a-ads.com",
        "adcolony.com",
        "unityads.unity3d.com",
        "applovin.com",
        "chartboost.com",
        "vungle.com",
        "inmobi.com"
    )

    private val GAMBLING_HOST_TOKENS: List<String> = listOf(
        "slot88", "hoki88", "gacor88", "maxwin88", "jp88",
        "bet88", "judislot", "daftar-slot", "link-gacor", "rtp-slot",
        "kubet", "shbet", "jun88", "f8bet", "fun88",
        "789bet", "okvip", "rikvip", "78win", "gem88"
    )

    private val AD_SCRIPT_PATH_PATTERNS: List<String> = listOf(
        "/pagead/",
        "/pagead.js",
        "/adsbygoogle.js",
        "/gpt.js",
        "/prebid",
        "/popunder",
        "/popads",
        "/adserver/",
        "/vast.xml",
        "/vpaid.js",
        "/inpagepush",
        "/doubleclick/"
    )

    internal fun matchesHostToken(host: String, token: String): Boolean {
        val h = host.lowercase(Locale.US).trim().trim('.')
        val t = token.lowercase(Locale.US).trim().trim('.')
        if (h.isEmpty() || t.isEmpty()) return false
        val labels = h.split('.')
        for (label in labels) {
            if (label == t || label.startsWith("$t-") || label.endsWith("-$t")) {
                return true
            }
            if (t.length >= 6 && label.contains(t)) {
                return true
            }
        }
        return false
    }

    // Backwards-compatible alias for unit tests if any call matchesDomainKeyword
    internal fun matchesDomainKeyword(host: String, keyword: String): Boolean =
        matchesHostToken(host, keyword)

    private fun isGamblingHost(cleanHost: String): Boolean {
        for (token in GAMBLING_HOST_TOKENS) {
            if (matchesHostToken(cleanHost, token)) return true
        }
        return false
    }

    fun isHeuristicAdDomain(host: String): Boolean {
        var cursor: String? = host.lowercase(Locale.US).trim().trimEnd('.').removePrefix("www.")
        while (!cursor.isNullOrEmpty()) {
            if (KNOWN_AD_ROOT_DOMAINS.contains(cursor)) return true
            val dot = cursor.indexOf('.')
            cursor = if (dot != -1) cursor.substring(dot + 1) else null
        }
        return false
    }

    fun heuristicReason(
        host: String,
        lowerUrl: String,
        isFirstParty: Boolean,
        resType: AbpRuleEngine.ResourceType = AbpRuleEngine.ResourceType.OTHER,
        aggressive: Boolean = false
    ): String? {
        val cleanHost = host.lowercase(Locale.US).trim().trimEnd('.').removePrefix("www.")
        if (cleanHost.isEmpty()) return null

        if (isHeuristicAdDomain(cleanHost)) {
            return "heuristic-domain"
        }

        if (isFirstParty) return null

        if (isGamblingHost(cleanHost)) {
            return "heuristic-gambling"
        }

        val typeAllowedForPathCheck = when (resType) {
            AbpRuleEngine.ResourceType.SCRIPT,
            AbpRuleEngine.ResourceType.SUBDOCUMENT -> true
            AbpRuleEngine.ResourceType.XMLHTTPREQUEST,
            AbpRuleEngine.ResourceType.IMAGE -> aggressive
            else -> false
        }
        if (!typeAllowedForPathCheck) return null

        val pathPart = extractPathAndQuery(lowerUrl)
        for (pattern in AD_SCRIPT_PATH_PATTERNS) {
            if (pathPart.contains(pattern)) {
                return "heuristic-path"
            }
        }
        return null
    }

    private fun extractPathAndQuery(lowerUrl: String): String {
        val schemeIdx = lowerUrl.indexOf("://")
        val startHost = if (schemeIdx != -1) schemeIdx + 3 else 0
        val slashIdx = lowerUrl.indexOf('/', startHost)
        return if (slashIdx != -1) lowerUrl.substring(slashIdx) else ""
    }
}
