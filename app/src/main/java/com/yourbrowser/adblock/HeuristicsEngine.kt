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
        "slot88", "slot77", "slot99", "slotgacor", "hoki88", "gacor88", "gacor77",
        "maxwin", "maxwin88", "jp88", "bet88", "judislot", "judibola", "judi88", "judol",
        "daftar-slot", "daftarslot", "link-gacor", "linkgacor", "rtp-slot", "rtpslot",
        "pragmatic", "pgsoft", "mahjongways", "mahjong-ways", "sbobet", "ibcbet",
        "togel", "toto88", "toto4d", "poker88", "domino88", "pkvgames", "idnpoker", "idnslot",
        "casino88", "zeus88", "olympus88", "sultan88", "raja88", "dewa88", "cuan88",
        "garuda88", "kakekzeus", "scatter88", "mpo88", "mposlot", "pay4d", "bolagacor",
        "kubet", "shbet", "jun88", "f8bet", "fun88",
        "789bet", "okvip", "rikvip", "78win", "gem88"
    )

    private val GAMBLING_URL_KEYWORDS: List<String> = listOf(
        "slot-gacor", "slot_gacor", "slotgacor", "rtp-slot", "rtp_slot", "rtpslot",
        "rtp-live", "daftar-slot", "link-gacor", "link-alternatif-slot",
        "situs-judi", "judi-online", "judi-slot", "judislot", "judi-bola",
        "bocoran-slot", "pola-gacor", "pola-slot", "akun-pro-slot",
        "mahjong-ways", "gates-of-olympus", "starlight-princess", "sweet-bonanza",
        "kakek-zeus", "scatter-hitam", "depo-pulsa", "depo-qris",
        "bandar-togel", "bandar-slot", "agen-slot", "agen-judi", "agen-sbobet",
        "sbobet88", "pragmatic88", "pgsoft88", "maxwin88", "gacor88", "slot88"
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

    private val ULTRA_AGGRESSIVE_PATH_PATTERNS: List<String> = listOf(
        "/clickunder",
        "/adframe",
        "/adservice",
        "/adscript",
        "/ad_data",
        "/ad-unit",
        "/ad_handler",
        "/adbanner",
        "/ad-banner",
        "/nativeads",
        "/clicktrack",
        "/out/click",
        "/track/click",
        "/push-ad",
        "/webpush",
        "/interstitial",
        "/floating-ad",
        "adurl=",
        "click_url=",
        "banner_id=",
        "zoneid="
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

    fun isGamblingHost(cleanHost: String): Boolean {
        val normalized = cleanHost.lowercase(Locale.US).trim().trimEnd('.').removePrefix("www.")
        if (normalized.isEmpty()) return false
        for (token in GAMBLING_HOST_TOKENS) {
            if (matchesHostToken(normalized, token)) return true
        }
        return false
    }

    internal fun containsKeywordAtTokenBoundary(text: String, keyword: String): Boolean {
        if (text.isEmpty() || keyword.isEmpty()) return false
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(keyword, searchFrom)
            if (idx == -1) return false
            val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
            val endIdx = idx + keyword.length
            val afterOk = endIdx >= text.length || !text[endIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            searchFrom = idx + 1
        }
    }

    /**
     * Deteksi apakah URL (host, path, atau query) merupakan situs / pop-up judi online (Judol).
     */
    fun isGamblingUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val schemeIdx = lower.indexOf("://")
        val startHost = if (schemeIdx != -1) schemeIdx + 3 else 0
        val slashIdx = lower.indexOf('/', startHost)
        val qIdx = lower.indexOf('?', startHost)
        val hostEnd = when {
            slashIdx != -1 && qIdx != -1 -> minOf(slashIdx, qIdx)
            slashIdx != -1 -> slashIdx
            qIdx != -1 -> qIdx
            else -> lower.length
        }
        val rawHost = lower.substring(startHost, hostEnd).substringBefore(':').removePrefix("www.")
        if (rawHost.isEmpty()) return false
        if (isGamblingHost(rawHost)) return true
        val pathAndQuery = extractPathAndQuery(lower)
        if (pathAndQuery.isNotEmpty()) {
            for (kw in GAMBLING_URL_KEYWORDS) {
                if (containsKeywordAtTokenBoundary(pathAndQuery, kw)) return true
            }
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
            AbpRuleEngine.ResourceType.XHR_FETCH,
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
        if (aggressive) {
            for (pattern in ULTRA_AGGRESSIVE_PATH_PATTERNS) {
                if (pathPart.contains(pattern)) {
                    return "heuristic-aggressive-path"
                }
            }
            for (kw in GAMBLING_URL_KEYWORDS) {
                if (containsKeywordAtTokenBoundary(pathPart, kw)) {
                    return "heuristic-gambling-path"
                }
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
