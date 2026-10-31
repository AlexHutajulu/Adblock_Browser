package com.example.manager

import java.util.Locale

object AbpRuleEngine {
    const val MAX_RULE_LINE_LENGTH = 2048
    const val MAX_COSMETIC_SELECTORS_PER_HOST = 1500

    enum class ResourceType {
        DOCUMENT,
        SCRIPT,
        IMAGE,
        STYLESHEET,
        XMLHTTPREQUEST,
        XHR_FETCH,
        SUBDOCUMENT,
        MEDIA,
        MEDIA_MANIFEST,
        MEDIA_SEGMENT,
        SUBTITLE,
        FONT,
        PING,
        WEBSOCKET,
        OTHER;

        val isMediaFamily: Boolean
            get() = this == MEDIA || this == MEDIA_MANIFEST || this == MEDIA_SEGMENT || this == SUBTITLE

        val isXhrOrFetch: Boolean
            get() = this == XMLHTTPREQUEST || this == XHR_FETCH
    }

    private val MASK_MEDIA_FAMILY: Int =
        (1 shl ResourceType.MEDIA.ordinal) or
            (1 shl ResourceType.MEDIA_MANIFEST.ordinal) or
            (1 shl ResourceType.MEDIA_SEGMENT.ordinal) or
            (1 shl ResourceType.SUBTITLE.ordinal)

    private val MASK_XHR_FAMILY: Int =
        (1 shl ResourceType.XMLHTTPREQUEST.ordinal) or
            (1 shl ResourceType.XHR_FETCH.ordinal)

    private val EXPLICIT_VIDEO_AD_INDICATORS: List<String> = listOf(
        "/vast",
        "/vpaid",
        "/pagead/",
        "/doubleclick/",
        "/adserver/",
        "/ads/",
        "/preroll",
        "/midroll",
        "/adbreak",
        "ad_type=",
        "adformat="
    )

    private val EXPLICIT_IMAGE_AD_INDICATORS: List<String> = listOf(
        "/ads/",
        "/ad/",
        "/adserver",
        "/adbanner",
        "/ad-banner",
        "-ad-banner",
        "_ad_banner",
        "/banner-ad",
        "-banner-ad",
        "/pagead/",
        "/doubleclick/",
        "/sponsor-",
        "/sponsored/",
        "/popunder",
        "/popads",
        "/pixel",
        "/beacon",
        "/collect",
        "/ptracking",
        "ad_type=",
        "adformat="
    )

    private val CONTENT_IMAGE_PATH_INDICATORS: List<String> = listOf(
        "/wp-content/uploads/",
        "/uploads/",
        "/thumbnails/",
        "/thumbnail/",
        "/thumb/",
        "/thumbs/",
        "/posters/",
        "/poster/",
        "/covers/",
        "/cover/",
        "/avatars/",
        "/avatar/",
        "/gallery/",
        "/manga/",
        "/anime/"
    )

    private val SAME_SITE_IMAGE_PROXY_HOSTS: Set<String> = setOf(
        "i0.wp.com",
        "i1.wp.com",
        "i2.wp.com",
        "i3.wp.com"
    )

    data class MatchResult(
        val isAllow: Boolean,
        val ruleText: String,
        val reason: String
    )

    internal fun isAbpSeparatorChar(ch: Char): Boolean {
        return !ch.isLetterOrDigit() && ch != '_' && ch != '-' && ch != '.' && ch != '%'
    }

    class CustomRule(
        val rawLine: String,
        val isAllow: Boolean,
        val isDomainAnchored: Boolean,
        val domainTarget: String?,
        val substringToken: String?,
        val thirdPartyOnly: Boolean?,
        val includedDomains: Set<String>?,
        val excludedDomains: Set<String>?,
        val resourceTypes: Int,
        val excludedResourceTypes: Int = 0,
        val wildcardTokens: List<String>? = null,
        val isImportant: Boolean = false,
        val requireTrailingSeparator: Boolean = false
    ) {
        fun matchesType(type: ResourceType): Boolean {
            val bit = 1 shl type.ordinal
            if (excludedResourceTypes != 0 && (excludedResourceTypes and bit) != 0) {
                return false
            }
            if (resourceTypes == 0) return true
            return (resourceTypes and bit) != 0
        }

        fun hasExplicitMediaType(): Boolean {
            return (resourceTypes and MASK_MEDIA_FAMILY) != 0
        }

        fun hasExplicitImageType(): Boolean {
            return (resourceTypes and (1 shl ResourceType.IMAGE.ordinal)) != 0
        }

        fun matchesParty(isThirdParty: Boolean): Boolean {
            return when (thirdPartyOnly) {
                true -> isThirdParty
                false -> !isThirdParty
                null -> true
            }
        }

        fun matchesSourceDomain(sourceHost: String): Boolean {
            if (includedDomains == null && excludedDomains == null) return true
            val cleanSource = cleanHost(sourceHost)
            if (excludedDomains != null && excludedDomains.any { cleanSource == it || cleanSource.endsWith(".$it") }) {
                return false
            }
            if (includedDomains != null) {
                return includedDomains.any { cleanSource == it || cleanSource.endsWith(".$it") }
            }
            return true
        }

        private fun hasValidTrailingSeparator(lowerUrl: String, endIdx: Int): Boolean {
            if (!requireTrailingSeparator) return true
            if (endIdx >= lowerUrl.length) return true
            return isAbpSeparatorChar(lowerUrl[endIdx])
        }

        fun matchesUrl(lowerUrl: String, reqHost: String): Boolean {
            if (domainTarget != null) {
                if (reqHost != domainTarget && !reqHost.endsWith(".$domainTarget")) return false
            }
            if (!wildcardTokens.isNullOrEmpty()) {
                var searchFrom = 0
                for (part in wildcardTokens) {
                    if (part.isEmpty()) continue
                    val idx = lowerUrl.indexOf(part, searchFrom)
                    if (idx == -1) return false
                    searchFrom = idx + part.length
                }
                return hasValidTrailingSeparator(lowerUrl, searchFrom)
            }
            if (!substringToken.isNullOrEmpty()) {
                var searchFrom = 0
                while (true) {
                    val idx = lowerUrl.indexOf(substringToken, searchFrom)
                    if (idx == -1) return false
                    val endIdx = idx + substringToken.length
                    if (hasValidTrailingSeparator(lowerUrl, endIdx)) {
                        return true
                    }
                    searchFrom = idx + 1
                }
            }
            return domainTarget != null
        }
    }

    class CosmeticRuleSet(
        val globalSelectors: List<String>,
        val domainSelectors: Map<String, List<String>>,
        val domainExceptions: Map<String, Set<String>>
    ) {
        fun selectorsFor(host: String): List<String> {
            val clean = cleanHost(host)
            val result = LinkedHashSet<String>(64)
            val activeExceptions = HashSet<String>()

            domainExceptions[""]?.let { activeExceptions.addAll(it) }
            var h = clean
            while (h.isNotEmpty()) {
                domainExceptions[h]?.let { activeExceptions.addAll(it) }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            h = clean
            while (h.isNotEmpty()) {
                domainSelectors[h]?.forEach { sel ->
                    if (!activeExceptions.contains(sel) && isSafeCosmeticSelector(sel)) {
                        result.add(sel)
                        if (result.size >= MAX_COSMETIC_SELECTORS_PER_HOST) return result.toList()
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            for (sel in globalSelectors) {
                if (!activeExceptions.contains(sel) && isSafeCosmeticSelector(sel)) {
                    result.add(sel)
                    if (result.size >= MAX_COSMETIC_SELECTORS_PER_HOST) break
                }
            }
            return result.toList()
        }
    }

    class RuleSet(
        val domainOnlyBlock: Map<String, List<CustomRule>>,
        val domainOnlyAllow: Map<String, List<CustomRule>>,
        val allowRules: List<CustomRule>,
        val blockRules: List<CustomRule>,
        val cosmetic: CosmeticRuleSet,
        val totalRulesParsed: Int,
        val totalRulesSkipped: Int,
        val scriptlets: ScriptletEngine.ScriptletRuleSet = ScriptletEngine.ScriptletRuleSet(emptyList())
    ) {
        fun isDomainBlocked(host: String): Boolean {
            val clean = cleanHost(host)
            if (clean.isEmpty()) return false
            var h = clean
            while (h.isNotEmpty()) {
                val allowList = domainOnlyAllow[h]
                if (!allowList.isNullOrEmpty() && allowList.any {
                        it.resourceTypes == 0 && it.excludedResourceTypes == 0 && it.includedDomains == null
                    }
                ) {
                    return false
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }
            h = clean
            while (h.isNotEmpty()) {
                val rules = domainOnlyBlock[h]
                if (!rules.isNullOrEmpty() && rules.any {
                        it.resourceTypes == 0 && it.excludedResourceTypes == 0 && it.includedDomains == null
                    }
                ) {
                    return true
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }
            return false
        }

        fun matchesAllowRule(
            url: String,
            sourceHost: String,
            resourceType: ResourceType = ResourceType.OTHER
        ): Boolean {
            val lowerUrl = url.lowercase(Locale.US)
            val reqHost = cleanHost(extractHost(lowerUrl))
            val srcHost = cleanHost(sourceHost)
            val isThirdParty = isThirdParty(reqHost, srcHost)

            var h = reqHost
            while (h.isNotEmpty()) {
                domainOnlyAllow[h]?.forEach { rule ->
                    if (rule.matchesType(resourceType) &&
                        rule.matchesParty(isThirdParty) &&
                        rule.matchesSourceDomain(srcHost)
                    ) {
                        return true
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            for (rule in allowRules) {
                if (rule.matchesType(resourceType) &&
                    rule.matchesParty(isThirdParty) &&
                    rule.matchesSourceDomain(srcHost) &&
                    rule.matchesUrl(lowerUrl, reqHost)
                ) {
                    return true
                }
            }
            return false
        }

        fun shouldBlock(
            url: String,
            sourceHost: String,
            resourceType: ResourceType = ResourceType.OTHER
        ): MatchResult? {
            val lowerUrl = url.lowercase(Locale.US)
            val reqHost = cleanHost(extractHost(lowerUrl))
            val srcHost = cleanHost(sourceHost)
            val isProxiedSameSiteImage = resourceType == ResourceType.IMAGE &&
                isSameSiteImageProxy(reqHost, srcHost, lowerUrl)
            val isThirdParty = isThirdParty(reqHost, srcHost) && !isProxiedSameSiteImage

            // 1. Evaluate $important block rules first (per ABP/uBO spec, $important overrides @@ exception rules)
            var hImp = reqHost
            while (hImp.isNotEmpty()) {
                domainOnlyBlock[hImp]?.forEach { rule ->
                    if (rule.isImportant &&
                        rule.matchesType(resourceType) &&
                        rule.matchesParty(isThirdParty) &&
                        rule.matchesSourceDomain(srcHost)
                    ) {
                        return MatchResult(false, rule.rawLine, "important-domain-rule")
                    }
                }
                val dot = hImp.indexOf('.')
                hImp = if (dot != -1) hImp.substring(dot + 1) else ""
            }
            for (rule in blockRules) {
                if (!rule.isImportant) continue
                if (rule.matchesType(resourceType) &&
                    rule.matchesParty(isThirdParty) &&
                    rule.matchesSourceDomain(srcHost) &&
                    rule.matchesUrl(lowerUrl, reqHost)
                ) {
                    return MatchResult(false, rule.rawLine, "important-custom-rule")
                }
            }

            if (matchesAllowRule(lowerUrl, srcHost, resourceType)) {
                return null
            }

            var h = reqHost
            while (h.isNotEmpty()) {
                domainOnlyBlock[h]?.forEach { rule ->
                    if (rule.matchesType(resourceType) &&
                        rule.matchesParty(isThirdParty) &&
                        rule.matchesSourceDomain(srcHost)
                    ) {
                        return MatchResult(false, rule.rawLine, "domain-rule")
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            for (rule in blockRules) {
                // Protect first-party from unanchored generic substring rules
                if (!isThirdParty && !rule.isDomainAnchored && rule.includedDomains == null && rule.thirdPartyOnly != false) {
                    continue
                }
                // Protect media streams, manifests, segments, and subtitles from overly broad unanchored generic rules
                // that do not explicitly specify $media and are not scoped to specific domains, unless explicit video-ad
                // indicators are present in the URL.
                if (resourceType.isMediaFamily &&
                    !rule.isDomainAnchored &&
                    rule.includedDomains == null &&
                    !rule.hasExplicitMediaType() &&
                    !hasExplicitVideoAdIndicator(lowerUrl)
                ) {
                    continue
                }
                // Protect legitimate content images & thumbnails from overly broad unanchored substring rules
                // unless the URL has an explicit image-ad indicator.
                if (resourceType == ResourceType.IMAGE &&
                    !rule.isDomainAnchored &&
                    rule.includedDomains == null &&
                    !hasExplicitImageAdIndicator(lowerUrl) &&
                    (!rule.hasExplicitImageType() || isLikelyContentImagePath(lowerUrl))
                ) {
                    continue
                }
                if (rule.matchesType(resourceType) &&
                    rule.matchesParty(isThirdParty) &&
                    rule.matchesSourceDomain(srcHost) &&
                    rule.matchesUrl(lowerUrl, reqHost)
                ) {
                    return MatchResult(false, rule.rawLine, "custom-rule")
                }
            }
            return null
        }
    }

    internal fun hasExplicitVideoAdIndicator(lowerUrl: String): Boolean {
        for (indicator in EXPLICIT_VIDEO_AD_INDICATORS) {
            if (lowerUrl.contains(indicator)) return true
        }
        return false
    }

    internal fun hasExplicitImageAdIndicator(lowerUrl: String): Boolean {
        for (indicator in EXPLICIT_IMAGE_AD_INDICATORS) {
            if (lowerUrl.contains(indicator)) return true
        }
        return false
    }

    internal fun isLikelyContentImagePath(lowerUrl: String): Boolean {
        if (hasExplicitImageAdIndicator(lowerUrl)) return false
        val cleanPath = extractCleanPath(lowerUrl)
        if (cleanPath.isEmpty()) return false
        for (indicator in CONTENT_IMAGE_PATH_INDICATORS) {
            if (cleanPath.contains(indicator)) return true
        }
        return false
    }

    internal fun isSameSiteImageProxy(reqHost: String, srcHost: String, lowerUrl: String): Boolean {
        val cleanReq = cleanHost(reqHost)
        val cleanSrc = cleanHost(srcHost)
        if (cleanReq.isEmpty() || cleanSrc.isEmpty()) return false
        if (!SAME_SITE_IMAGE_PROXY_HOSTS.contains(cleanReq)) return false
        val cleanPath = extractCleanPath(lowerUrl).removePrefix("/")
        if (cleanPath.isEmpty()) return false
        val proxiedHost = cleanHost(cleanPath.substringBefore('/'))
        if (proxiedHost.isEmpty()) return false
        return SiteDomainPolicy.isSameSite(proxiedHost, cleanSrc)
    }

    fun cleanHost(host: String?): String {
        if (host.isNullOrBlank()) return ""
        return host.trim()
            .lowercase(Locale.US)
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .removePrefix("www.")
            .trim('.')
    }

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val trimmed = url.trim()
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx == -1) {
            if (trimmed.startsWith("//")) {
                val rest = trimmed.substring(2)
                return cleanHost(rest.substringBefore("/").substringBefore("?").substringBefore("#"))
            }
            return ""
        }
        val start = schemeIdx + 3
        if (start >= trimmed.length) return ""
        val slashIdx = trimmed.indexOf('/', start)
        val queryIdx = trimmed.indexOf('?', start)
        val hashIdx = trimmed.indexOf('#', start)
        var end = trimmed.length
        if (slashIdx != -1 && slashIdx < end) end = slashIdx
        if (queryIdx != -1 && queryIdx < end) end = queryIdx
        if (hashIdx != -1 && hashIdx < end) end = hashIdx
        return cleanHost(trimmed.substring(start, end))
    }

    internal fun extractCleanPath(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val lower = url.trim().lowercase(Locale.US)
        val withoutFragment = lower.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val schemeIdx = withoutQuery.indexOf("://")
        val rawPath = when {
            schemeIdx != -1 -> {
                val slashIdx = withoutQuery.indexOf('/', schemeIdx + 3)
                if (slashIdx != -1) withoutQuery.substring(slashIdx) else ""
            }
            withoutQuery.startsWith("//") -> {
                val slashIdx = withoutQuery.indexOf('/', 2)
                if (slashIdx != -1) withoutQuery.substring(slashIdx) else ""
            }
            else -> withoutQuery
        }
        if (rawPath.isEmpty()) return ""
        return if (rawPath.contains(';')) {
            rawPath.split('/').joinToString("/") { it.substringBefore(';') }
        } else {
            rawPath
        }
    }

    internal fun extractQuery(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val withoutFragment = url.trim().lowercase(Locale.US).substringBefore('#')
        val qIdx = withoutFragment.indexOf('?')
        return if (qIdx != -1 && qIdx + 1 < withoutFragment.length) {
            withoutFragment.substring(qIdx + 1)
        } else {
            ""
        }
    }

    private fun headerValue(headers: Map<String, String>?, name: String): String? {
        if (headers.isNullOrEmpty()) return null
        headers[name]?.let { return it }
        for ((k, v) in headers) {
            if (k.equals(name, ignoreCase = true)) return v
        }
        return null
    }

    private fun queryContainsAny(query: String, vararg tokens: String): Boolean {
        if (query.isEmpty()) return false
        for (token in tokens) {
            if (query.contains(token)) return true
        }
        return false
    }

    fun getRegistrableDomain(host: String): String = SiteDomainPolicy.registrableDomain(host)

    fun isThirdParty(requestHost: String, sourceHost: String): Boolean =
        SiteDomainPolicy.isThirdParty(requestHost, sourceHost)

    fun isSafeCosmeticSelector(selector: String): Boolean =
        CosmeticFilterInjector.isSafeCssSelector(selector.trim())

    fun inferType(
        url: String,
        acceptHeader: String? = null,
        requestHeaders: Map<String, String>? = null,
        isForMainFrame: Boolean = false
    ): ResourceType {
        val lower = url.trim().lowercase(Locale.US)
        if (lower.startsWith("ws://") || lower.startsWith("wss://")) return ResourceType.WEBSOCKET

        val accept = (acceptHeader ?: headerValue(requestHeaders, "Accept")).orEmpty().lowercase(Locale.US)
        val secFetchDest = headerValue(requestHeaders, "Sec-Fetch-Dest").orEmpty().lowercase(Locale.US)
        val secFetchMode = headerValue(requestHeaders, "Sec-Fetch-Mode").orEmpty().lowercase(Locale.US)
        val requestedWith = headerValue(requestHeaders, "X-Requested-With").orEmpty().lowercase(Locale.US)
        val rangeHeader = headerValue(requestHeaders, "Range").orEmpty().lowercase(Locale.US)

        if (isForMainFrame || secFetchDest == "document") {
            return ResourceType.DOCUMENT
        }

        val cleanPath = extractCleanPath(lower)
        val query = extractQuery(lower)

        return when {
            // 1. Streaming manifests (HLS / DASH / Smooth Streaming)
            cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".m3u") ||
                cleanPath.endsWith(".mpd") || cleanPath.endsWith(".ism") || cleanPath.endsWith(".isml") ||
                cleanPath.contains(".m3u8/") || cleanPath.contains(".mpd/") ||
                accept.contains("application/vnd.apple.mpegurl") ||
                accept.contains("application/x-mpegurl") ||
                accept.contains("audio/mpegurl") ||
                accept.contains("audio/x-mpegurl") ||
                accept.contains("application/dash+xml") ||
                queryContainsAny(
                    query,
                    "format=m3u8", "format=mpd", "type=m3u8", "type=mpd",
                    "mime=application/x-mpegurl", "mime=application/vnd.apple.mpegurl",
                    "mime=application/dash+xml"
                ) -> ResourceType.MEDIA_MANIFEST

            // 2. Streaming media segments (MPEG-TS, CMAF/fMP4 segments)
            cleanPath.endsWith(".m4s") || cleanPath.endsWith(".ts") || cleanPath.endsWith(".m2ts") ||
                cleanPath.endsWith(".m4f") || cleanPath.endsWith(".cmfv") || cleanPath.endsWith(".cmfa") ||
                cleanPath.endsWith(".fmp4") ||
                accept.contains("video/mp2t") || accept.contains("video/iso.segment") ||
                ((cleanPath.contains("/segment/") || cleanPath.contains("/segments/") ||
                    cleanPath.contains("/chunk/") || cleanPath.contains("/frag/")) &&
                    (rangeHeader.startsWith("bytes=") || accept.startsWith("video/") || accept.startsWith("audio/"))) -> ResourceType.MEDIA_SEGMENT

            // 3. Subtitles & captions
            cleanPath.endsWith(".vtt") || cleanPath.endsWith(".webvtt") ||
                cleanPath.endsWith(".srt") || cleanPath.endsWith(".ass") || cleanPath.endsWith(".ssa") ||
                cleanPath.endsWith(".ttml") || cleanPath.endsWith(".dfxp") || cleanPath.endsWith(".sbv") ||
                secFetchDest == "track" ||
                accept.contains("text/vtt") || accept.contains("application/ttml+xml") ||
                accept.contains("application/x-subrip") ||
                queryContainsAny(query, "format=vtt", "format=srt", "format=ttml", "fmt=vtt", "fmt=srv3", "mime=text/vtt") -> ResourceType.SUBTITLE

            // 4. Direct media files & dynamic media streaming endpoints
            cleanPath.endsWith(".mp4") || cleanPath.endsWith(".webm") || cleanPath.endsWith(".mkv") ||
                cleanPath.endsWith(".m4v") || cleanPath.endsWith(".mov") || cleanPath.endsWith(".ogv") ||
                cleanPath.endsWith(".ogg") || cleanPath.endsWith(".oga") || cleanPath.endsWith(".mp3") ||
                cleanPath.endsWith(".m4a") || cleanPath.endsWith(".aac") || cleanPath.endsWith(".flac") ||
                cleanPath.endsWith(".opus") || cleanPath.endsWith(".wav") || cleanPath.endsWith(".weba") ||
                cleanPath.endsWith(".3gp") ||
                secFetchDest == "video" || secFetchDest == "audio" ||
                accept.startsWith("video/") || accept.startsWith("audio/") || accept.contains("application/ogg") ||
                queryContainsAny(
                    query,
                    "mime=video/", "mime=audio/", "mime=video%2f", "mime=audio%2f",
                    "content_type=video/", "content_type=audio/"
                ) ||
                ((cleanPath.endsWith("/stream") || cleanPath.contains("/stream/") ||
                    cleanPath.endsWith("/videoplayback") || cleanPath.contains("/videoplayback/")) &&
                    !accept.contains("text/html") && !accept.contains("javascript") &&
                    !accept.contains("text/css") && !accept.startsWith("image/") &&
                    !accept.contains("application/json")) ||
                (rangeHeader.startsWith("bytes=") &&
                    !cleanPath.endsWith(".js") && !cleanPath.endsWith(".css") &&
                    !cleanPath.endsWith(".pdf") && !cleanPath.endsWith(".zip")) -> ResourceType.MEDIA

            // 5. Scripts
            cleanPath.endsWith(".js") || cleanPath.endsWith(".mjs") || cleanPath.endsWith(".cjs") ||
                secFetchDest == "script" || accept.contains("javascript") || accept.contains("ecmascript") -> ResourceType.SCRIPT

            // 6. Stylesheets
            cleanPath.endsWith(".css") || secFetchDest == "style" || accept.contains("text/css") -> ResourceType.STYLESHEET

            // 7. Images & thumbnails
            cleanPath.endsWith(".png") || cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") ||
                cleanPath.endsWith(".gif") || cleanPath.endsWith(".webp") || cleanPath.endsWith(".svg") ||
                cleanPath.endsWith(".ico") || cleanPath.endsWith(".avif") || cleanPath.endsWith(".bmp") ||
                cleanPath.endsWith(".apng") || cleanPath.endsWith(".jfif") ||
                secFetchDest == "image" || accept.startsWith("image/") -> ResourceType.IMAGE

            // 8. Fonts
            cleanPath.endsWith(".woff") || cleanPath.endsWith(".woff2") || cleanPath.endsWith(".ttf") ||
                cleanPath.endsWith(".otf") || cleanPath.endsWith(".eot") ||
                secFetchDest == "font" || accept.startsWith("font/") || accept.contains("application/font-") -> ResourceType.FONT

            // 9. Ping / beacon telemetry
            lower.contains("/collect") || lower.contains("/beacon") || lower.contains("/ptracking") ||
                secFetchDest == "ping" -> ResourceType.PING

            // 10. Subdocuments (iframes / embeds)
            secFetchDest == "iframe" || secFetchDest == "frame" || secFetchDest == "embed" -> ResourceType.SUBDOCUMENT

            // 11. XHR / JSON / XML / Fetch API requests
            accept.contains("application/json") || cleanPath.endsWith(".json") ||
                cleanPath.endsWith(".xml") || accept.contains("application/xml") ||
                requestedWith == "xmlhttprequest" -> ResourceType.XMLHTTPREQUEST

            secFetchDest == "empty" || secFetchMode == "cors" ||
                cleanPath.contains("/api/") || cleanPath.contains("/graphql") || cleanPath.contains("/ajax/") -> ResourceType.XHR_FETCH

            // 12. HTML subdocuments
            accept.contains("text/html") || accept.contains("application/xhtml+xml") ||
                cleanPath.endsWith(".html") || cleanPath.endsWith(".htm") -> ResourceType.SUBDOCUMENT

            else -> ResourceType.OTHER
        }
    }

    private fun parsePatternTokens(rawPattern: String): Pair<String?, List<String>?> {
        val normalized = rawPattern.replace("^", "")
        if (!normalized.contains('*')) {
            return normalized.ifEmpty { null } to null
        }
        val parts = normalized.split('*').filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> null to null
            parts.size == 1 -> parts[0] to null
            else -> parts[0] to parts
        }
    }

    fun build(lines: Sequence<String>): RuleSet {
        val domainOnlyBlock = HashMap<String, MutableList<CustomRule>>()
        val domainOnlyAllow = HashMap<String, MutableList<CustomRule>>()
        val allowRules = ArrayList<CustomRule>()
        val blockRules = ArrayList<CustomRule>()
        val globalCosmetics = ArrayList<String>()
        val domainCosmetics = HashMap<String, MutableList<String>>()
        val domainCosmeticExceptions = HashMap<String, MutableSet<String>>()
        val scriptletRules = ArrayList<ScriptletEngine.ScriptletRule>()
        var parsedCount = 0
        var skippedCount = 0

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            if (line.length > MAX_RULE_LINE_LENGTH) {
                skippedCount++
                continue
            }

            if (line.contains("##+js") || line.contains("#@#+js") || line.contains("#%#//scriptlet") || line.contains("#@$#//scriptlet")) {
                val sr = ScriptletEngine.parseScriptletRule(line)
                if (sr != null && scriptletRules.size < ScriptletEngine.MAX_SCRIPTLET_RULES) {
                    scriptletRules.add(sr)
                    parsedCount++
                } else {
                    skippedCount++
                }
                continue
            }

            if (line.contains("#?#") || line.contains("#$#")) {
                skippedCount++
                continue
            }

            if (line.contains("#@#") || line.contains("##")) {
                val isException = line.contains("#@#")
                val marker = if (isException) "#@#" else "##"
                val parts = line.split(marker, limit = 2)
                if (parts.size == 2) {
                    val domainsRaw = parts[0].trim()
                    val selector = parts[1].trim()
                    if (isException) {
                        if (domainsRaw.isEmpty()) {
                            domainCosmeticExceptions.getOrPut("") { HashSet() }.add(selector)
                        } else {
                            domainsRaw.split(',').forEach { d ->
                                val cleanD = cleanHost(d.removePrefix("~"))
                                if (cleanD.isNotEmpty()) {
                                    domainCosmeticExceptions.getOrPut(cleanD) { HashSet() }.add(selector)
                                }
                            }
                        }
                        parsedCount++
                    } else if (isSafeCosmeticSelector(selector)) {
                        if (domainsRaw.isEmpty()) {
                            globalCosmetics.add(selector)
                        } else {
                            var hasPositive = false
                            domainsRaw.split(',').forEach { d ->
                                val t = d.trim()
                                if (t.startsWith("~")) {
                                    val cleanEx = cleanHost(t.substring(1))
                                    if (cleanEx.isNotEmpty()) {
                                        domainCosmeticExceptions.getOrPut(cleanEx) { HashSet() }.add(selector)
                                    }
                                } else {
                                    val cleanD = cleanHost(t)
                                    if (cleanD.isNotEmpty()) {
                                        hasPositive = true
                                        domainCosmetics.getOrPut(cleanD) { ArrayList() }.add(selector)
                                    }
                                }
                            }
                            if (!hasPositive) {
                                globalCosmetics.add(selector)
                            }
                        }
                        parsedCount++
                    } else {
                        skippedCount++
                    }
                } else {
                    skippedCount++
                }
                continue
            }

            if (line.startsWith("/") && line.endsWith("/") && line.length > 2) {
                skippedCount++
                continue
            }

            val isAllow = line.startsWith("@@")
            val bodyAndOpts = if (isAllow) line.substring(2) else line
            val dollarIdx = bodyAndOpts.indexOf('$')
            val patternPart = (if (dollarIdx != -1) bodyAndOpts.substring(0, dollarIdx) else bodyAndOpts).trim()
            val optionsPart = if (dollarIdx != -1) bodyAndOpts.substring(dollarIdx + 1).trim() else ""

            var thirdPartyOnly: Boolean? = null
            var includedDomains: MutableSet<String>? = null
            var excludedDomains: MutableSet<String>? = null
            var resourceMask = 0
            var excludedResourceMask = 0
            var unsupportedOption = false
            var hasNonSubresourceModifier = false
            var isImportant = false

            if (optionsPart.isNotEmpty()) {
                for (opt in optionsPart.split(',')) {
                    val o = opt.trim().lowercase(Locale.US)
                    if (o.isEmpty()) continue
                    when {
                        o == "third-party" || o == "3p" -> thirdPartyOnly = true
                        o == "~third-party" || o == "1p" || o == "first-party" -> thirdPartyOnly = false
                        o.startsWith("domain=") -> {
                            val doms = o.removePrefix("domain=").split('|')
                            for (d in doms) {
                                val dt = d.trim()
                                if (dt.startsWith("~")) {
                                    val c = cleanHost(dt.substring(1))
                                    if (c.isNotEmpty()) {
                                        if (excludedDomains == null) excludedDomains = HashSet()
                                        excludedDomains.add(c)
                                    }
                                } else {
                                    val c = cleanHost(dt)
                                    if (c.isNotEmpty()) {
                                        if (includedDomains == null) includedDomains = HashSet()
                                        includedDomains.add(c)
                                    }
                                }
                            }
                        }
                        o == "document" || o == "doc" -> resourceMask = resourceMask or (1 shl ResourceType.DOCUMENT.ordinal)
                        o == "~document" || o == "~doc" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.DOCUMENT.ordinal)
                        o == "script" -> resourceMask = resourceMask or (1 shl ResourceType.SCRIPT.ordinal)
                        o == "~script" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.SCRIPT.ordinal)
                        o == "image" -> resourceMask = resourceMask or (1 shl ResourceType.IMAGE.ordinal)
                        o == "~image" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.IMAGE.ordinal)
                        o == "stylesheet" || o == "css" -> resourceMask = resourceMask or (1 shl ResourceType.STYLESHEET.ordinal)
                        o == "~stylesheet" || o == "~css" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.STYLESHEET.ordinal)
                        o == "xmlhttprequest" || o == "xhr" -> resourceMask = resourceMask or MASK_XHR_FAMILY
                        o == "~xmlhttprequest" || o == "~xhr" -> excludedResourceMask = excludedResourceMask or MASK_XHR_FAMILY
                        o == "subdocument" || o == "frame" -> resourceMask = resourceMask or (1 shl ResourceType.SUBDOCUMENT.ordinal)
                        o == "~subdocument" || o == "~frame" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.SUBDOCUMENT.ordinal)
                        o == "media" -> resourceMask = resourceMask or MASK_MEDIA_FAMILY
                        o == "~media" -> excludedResourceMask = excludedResourceMask or MASK_MEDIA_FAMILY
                        o == "font" -> resourceMask = resourceMask or (1 shl ResourceType.FONT.ordinal)
                        o == "~font" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.FONT.ordinal)
                        o == "ping" || o == "beacon" -> resourceMask = resourceMask or (1 shl ResourceType.PING.ordinal)
                        o == "~ping" || o == "~beacon" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.PING.ordinal)
                        o == "websocket" -> resourceMask = resourceMask or (1 shl ResourceType.WEBSOCKET.ordinal)
                        o == "~websocket" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.WEBSOCKET.ordinal)
                        o == "other" || o == "object" -> resourceMask = resourceMask or (1 shl ResourceType.OTHER.ordinal)
                        o == "~other" || o == "~object" -> excludedResourceMask = excludedResourceMask or (1 shl ResourceType.OTHER.ordinal)
                        o == "popup" || o == "elemhide" || o == "ehide" ||
                            o == "generichide" || o == "ghide" || o == "specifichide" || o == "shide" ||
                            o == "inline-script" || o == "inline-font" || o == "webrtc" || o == "all" -> {
                            hasNonSubresourceModifier = true
                        }
                        o == "important" -> {
                            isImportant = true
                        }
                        o == "match-case" || o == "~match-case" -> {
                            // Recognized modifier that does not change resource mask
                        }
                        o.startsWith("redirect=") || o.startsWith("redirect-rule=") ||
                            o.startsWith("csp=") || o == "badfilter" || o.startsWith("removeparam=") ||
                            o.startsWith("rewrite=") || o.startsWith("header=") || o.startsWith("denyallow=") -> {
                            unsupportedOption = true
                        }
                    }
                }
            }

            // If a rule only specifies non-subresource modifiers (e.g., $popup, $elemhide, $generichide)
            // without any positive or negated subresource type, it must NOT become a wildcard (resourceMask == 0)
            // subresource network blocker.
            if (hasNonSubresourceModifier && resourceMask == 0 && excludedResourceMask == 0) {
                skippedCount++
                continue
            }

            if (unsupportedOption || patternPart.isEmpty()) {
                skippedCount++
                continue
            }

            val lowerPattern = patternPart.lowercase(Locale.US).removeSuffix("|")
            val isPipePipe = lowerPattern.startsWith("||")
            val isSinglePipeScheme = lowerPattern.startsWith("|http://") || lowerPattern.startsWith("|https://")

            if (isPipePipe) {
                val withoutPipes = lowerPattern.substring(2).removeSuffix("^")
                val slashIdx = withoutPipes.indexOf('/')
                val caretIdx = withoutPipes.indexOf('^')
                if (slashIdx == -1 && caretIdx == -1 && !withoutPipes.contains('*')) {
                    val dom = cleanHost(withoutPipes)
                    if (dom.isNotEmpty()) {
                        val rule = CustomRule(
                            rawLine = line,
                            isAllow = isAllow,
                            isDomainAnchored = true,
                            domainTarget = dom,
                            substringToken = null,
                            thirdPartyOnly = thirdPartyOnly,
                            includedDomains = includedDomains,
                            excludedDomains = excludedDomains,
                            resourceTypes = resourceMask,
                            excludedResourceTypes = excludedResourceMask,
                            wildcardTokens = null,
                            isImportant = isImportant
                        )
                        val targetMap = if (isAllow) domainOnlyAllow else domainOnlyBlock
                        targetMap.getOrPut(dom) { ArrayList() }.add(rule)
                        parsedCount++
                        continue
                    }
                } else {
                    val hostEnd = when {
                        slashIdx != -1 && caretIdx != -1 -> minOf(slashIdx, caretIdx)
                        slashIdx != -1 -> slashIdx
                        caretIdx != -1 -> caretIdx
                        else -> withoutPipes.length
                    }
                    val rawHostPart = withoutPipes.substring(0, hostEnd)
                    val dom = if (rawHostPart.contains('*')) "" else cleanHost(rawHostPart)
                    val rawRest = withoutPipes.substring(hostEnd)
                    val hasTrailingSep = rawRest.removePrefix("^").endsWith("^") || patternPart.endsWith("^")
                    val (firstToken, wildcardParts) = parsePatternTokens(rawRest)
                    if (dom.isEmpty() && firstToken == null && wildcardParts == null) {
                        skippedCount++
                        continue
                    }
                    val rule = CustomRule(
                        rawLine = line,
                        isAllow = isAllow,
                        isDomainAnchored = dom.isNotEmpty(),
                        domainTarget = dom.ifEmpty { null },
                        substringToken = firstToken,
                        thirdPartyOnly = thirdPartyOnly,
                        includedDomains = includedDomains,
                        excludedDomains = excludedDomains,
                        resourceTypes = resourceMask,
                        excludedResourceTypes = excludedResourceMask,
                        wildcardTokens = wildcardParts,
                        isImportant = isImportant,
                        requireTrailingSeparator = hasTrailingSep
                    )
                    if (isAllow) allowRules.add(rule) else blockRules.add(rule)
                    parsedCount++
                    continue
                }
            } else if (isSinglePipeScheme) {
                val rawUrl = lowerPattern.substring(1)
                val dom = extractHost(rawUrl)
                val hasTrailingSep = rawUrl.endsWith("^")
                val (firstToken, wildcardParts) = parsePatternTokens(rawUrl)
                val rule = CustomRule(
                    rawLine = line,
                    isAllow = isAllow,
                    isDomainAnchored = dom.isNotEmpty(),
                    domainTarget = dom.ifEmpty { null },
                    substringToken = firstToken,
                    thirdPartyOnly = thirdPartyOnly,
                    includedDomains = includedDomains,
                    excludedDomains = excludedDomains,
                    resourceTypes = resourceMask,
                    excludedResourceTypes = excludedResourceMask,
                    wildcardTokens = wildcardParts,
                    isImportant = isImportant,
                    requireTrailingSeparator = hasTrailingSep
                )
                if (isAllow) allowRules.add(rule) else blockRules.add(rule)
                parsedCount++
                continue
            } else {
                val rawBody = lowerPattern.removePrefix("|")
                val hasTrailingSep = rawBody.endsWith("^")
                val (firstToken, wildcardParts) = parsePatternTokens(rawBody)
                val effectiveLength = wildcardParts?.sumOf { it.length } ?: (firstToken?.length ?: 0)
                if (effectiveLength < 3) {
                    skippedCount++
                    continue
                }
                val rule = CustomRule(
                    rawLine = line,
                    isAllow = isAllow,
                    isDomainAnchored = false,
                    domainTarget = null,
                    substringToken = firstToken,
                    thirdPartyOnly = thirdPartyOnly,
                    includedDomains = includedDomains,
                    excludedDomains = excludedDomains,
                    resourceTypes = resourceMask,
                    excludedResourceTypes = excludedResourceMask,
                    wildcardTokens = wildcardParts,
                    isImportant = isImportant,
                    requireTrailingSeparator = hasTrailingSep
                )
                if (isAllow) allowRules.add(rule) else blockRules.add(rule)
                parsedCount++
            }
        }

        return RuleSet(
            domainOnlyBlock = domainOnlyBlock,
            domainOnlyAllow = domainOnlyAllow,
            allowRules = allowRules,
            blockRules = blockRules,
            cosmetic = CosmeticRuleSet(globalCosmetics, domainCosmetics, domainCosmeticExceptions),
            totalRulesParsed = parsedCount,
            totalRulesSkipped = skippedCount,
            scriptlets = ScriptletEngine.ScriptletRuleSet(scriptletRules)
        )
    }
}
