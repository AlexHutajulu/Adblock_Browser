package com.example.manager

import java.util.Locale

object AbpRuleEngine {
    const val MAX_RULE_LINE_LENGTH = 2048
    const val MAX_COSMETIC_SELECTORS_PER_HOST = 1500

    enum class ResourceType {
        SCRIPT,
        IMAGE,
        STYLESHEET,
        XMLHTTPREQUEST,
        SUBDOCUMENT,
        MEDIA,
        FONT,
        PING,
        WEBSOCKET,
        OTHER
    }

    data class MatchResult(
        val isAllow: Boolean,
        val ruleText: String,
        val reason: String
    )

    class CustomRule(
        val rawLine: String,
        val isAllow: Boolean,
        val isDomainAnchored: Boolean,
        val domainTarget: String?,
        val substringToken: String?,
        val thirdPartyOnly: Boolean?,
        val includedDomains: Set<String>?,
        val excludedDomains: Set<String>?,
        val resourceTypes: Int
    ) {
        fun matchesType(type: ResourceType): Boolean {
            if (resourceTypes == 0) return true
            return (resourceTypes and (1 shl type.ordinal)) != 0
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

        fun matchesUrl(lowerUrl: String, reqHost: String): Boolean {
            if (domainTarget != null) {
                if (reqHost != domainTarget && !reqHost.endsWith(".$domainTarget")) return false
            }
            if (!substringToken.isNullOrEmpty()) {
                return lowerUrl.contains(substringToken)
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
                if (domainOnlyAllow.containsKey(h)) return false
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }
            h = clean
            while (h.isNotEmpty()) {
                val rules = domainOnlyBlock[h]
                if (!rules.isNullOrEmpty()) return true
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
            val isThirdParty = isThirdParty(reqHost, srcHost)

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
                return cleanHost(rest.substringBefore("/"))
            }
            return ""
        }
        val start = schemeIdx + 3
        if (start >= trimmed.length) return ""
        val slashIdx = trimmed.indexOf('/', start)
        val queryIdx = trimmed.indexOf('?', start)
        val end = when {
            slashIdx != -1 && queryIdx != -1 -> minOf(slashIdx, queryIdx)
            slashIdx != -1 -> slashIdx
            queryIdx != -1 -> queryIdx
            else -> trimmed.length
        }
        return cleanHost(trimmed.substring(start, end))
    }

    fun getRegistrableDomain(host: String): String = SiteDomainPolicy.registrableDomain(host)

    fun isThirdParty(requestHost: String, sourceHost: String): Boolean =
        SiteDomainPolicy.isThirdParty(requestHost, sourceHost)

    fun isSafeCosmeticSelector(selector: String): Boolean =
        CosmeticFilterInjector.isSafeCssSelector(selector.trim())

    fun inferType(url: String, acceptHeader: String? = null): ResourceType {
        val lower = url.lowercase(Locale.US)
        val accept = acceptHeader?.lowercase(Locale.US).orEmpty()
        if (lower.startsWith("ws://") || lower.startsWith("wss://")) return ResourceType.WEBSOCKET
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") || accept.contains("javascript") -> ResourceType.SCRIPT
            path.endsWith(".css") || accept.contains("text/css") -> ResourceType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") || accept.startsWith("image/") -> ResourceType.IMAGE
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") ||
                path.endsWith(".mpd") || path.endsWith(".ts") || path.endsWith(".m4s") ||
                accept.startsWith("video/") || accept.startsWith("audio/") -> ResourceType.MEDIA
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") -> ResourceType.FONT
            lower.contains("/collect") || lower.contains("/beacon") || lower.contains("/ptracking") -> ResourceType.PING
            accept.contains("application/json") || path.endsWith(".json") -> ResourceType.XMLHTTPREQUEST
            accept.contains("text/html") -> ResourceType.SUBDOCUMENT
            else -> ResourceType.OTHER
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
            var unsupportedOption = false

            if (optionsPart.isNotEmpty()) {
                for (opt in optionsPart.split(',')) {
                    val o = opt.trim().lowercase(Locale.US)
                    when {
                        o == "third-party" || o == "3p" -> thirdPartyOnly = true
                        o == "~third-party" || o == "1p" -> thirdPartyOnly = false
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
                        o == "script" -> resourceMask = resourceMask or (1 shl ResourceType.SCRIPT.ordinal)
                        o == "image" -> resourceMask = resourceMask or (1 shl ResourceType.IMAGE.ordinal)
                        o == "stylesheet" || o == "css" -> resourceMask = resourceMask or (1 shl ResourceType.STYLESHEET.ordinal)
                        o == "xmlhttprequest" || o == "xhr" -> resourceMask = resourceMask or (1 shl ResourceType.XMLHTTPREQUEST.ordinal)
                        o == "subdocument" -> resourceMask = resourceMask or (1 shl ResourceType.SUBDOCUMENT.ordinal)
                        o == "media" -> resourceMask = resourceMask or (1 shl ResourceType.MEDIA.ordinal)
                        o == "font" -> resourceMask = resourceMask or (1 shl ResourceType.FONT.ordinal)
                        o == "ping" || o == "beacon" -> resourceMask = resourceMask or (1 shl ResourceType.PING.ordinal)
                        o == "websocket" -> resourceMask = resourceMask or (1 shl ResourceType.WEBSOCKET.ordinal)
                        o.startsWith("redirect=") || o.startsWith("csp=") || o == "badfilter" || o.startsWith("removeparam=") -> {
                            unsupportedOption = true
                        }
                    }
                }
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
                            resourceTypes = resourceMask
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
                    val dom = cleanHost(withoutPipes.substring(0, hostEnd))
                    val rest = withoutPipes.substring(hostEnd).replace("^", "").substringBefore('*')
                    val rule = CustomRule(
                        rawLine = line,
                        isAllow = isAllow,
                        isDomainAnchored = dom.isNotEmpty(),
                        domainTarget = dom.ifEmpty { null },
                        substringToken = rest.ifEmpty { null },
                        thirdPartyOnly = thirdPartyOnly,
                        includedDomains = includedDomains,
                        excludedDomains = excludedDomains,
                        resourceTypes = resourceMask
                    )
                    if (isAllow) allowRules.add(rule) else blockRules.add(rule)
                    parsedCount++
                    continue
                }
            } else if (isSinglePipeScheme) {
                val rawUrl = lowerPattern.substring(1).replace("^", "")
                val dom = extractHost(rawUrl)
                val token = rawUrl.substringBefore('*')
                val rule = CustomRule(
                    rawLine = line,
                    isAllow = isAllow,
                    isDomainAnchored = dom.isNotEmpty(),
                    domainTarget = dom.ifEmpty { null },
                    substringToken = token,
                    thirdPartyOnly = thirdPartyOnly,
                    includedDomains = includedDomains,
                    excludedDomains = excludedDomains,
                    resourceTypes = resourceMask
                )
                if (isAllow) allowRules.add(rule) else blockRules.add(rule)
                parsedCount++
                continue
            } else {
                val token = lowerPattern.removePrefix("|").replace("^", "").substringBefore('*')
                if (token.length < 3) {
                    skippedCount++
                    continue
                }
                val rule = CustomRule(
                    rawLine = line,
                    isAllow = isAllow,
                    isDomainAnchored = false,
                    domainTarget = null,
                    substringToken = token,
                    thirdPartyOnly = thirdPartyOnly,
                    includedDomains = includedDomains,
                    excludedDomains = excludedDomains,
                    resourceTypes = resourceMask
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
