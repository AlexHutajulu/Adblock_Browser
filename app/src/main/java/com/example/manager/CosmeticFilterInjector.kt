package com.example.manager

import android.util.Base64
import android.util.Log
import android.webkit.WebView

object CosmeticFilterInjector {

    private const val TAG = "CosmeticFilter"

    // Strictly scoped ad-only selectors — never includes generic classes like .floating-btn, .modal, or .popup
    private val SAFE_BUILT_IN_SELECTORS = listOf(
        "ins.adsbygoogle",
        "#google_ads_frame",
        "div[id^='google_ads_']",
        "div[data-ad-slot]",
        "div[data-ad-unit]",
        "div[data-google-query-id]",
        "div[data-adsbygoogle-status]",
        ".ad-banner",
        ".ad-slot-banner",
        ".ads-banner-container",
        ".advertisement-box",
        ".advertise-here",
        "div[id^='taboola-']",
        "div[class*='taboola-']",
        ".OUTBRAIN",
        "div[id^='ob_holder']",
        "div[id*='adblock-warning']",
        "div[class*='adblock-notice']",
        ".fc-ab-root",
        // Gambling / Judol explicit ad banners
        "div[class*='jackpot-banner']",
        "div[class*='casino-banner']",
        "div[class*='slot-banner']",
        "div[class*='judi-banner']",
        "a[class*='floating-ad-banner']"
    )

    private val BARE_HTML_TAGS = setOf(
        "*", "html", "body", "main", "header", "footer", "nav", "section",
        "article", "aside", "div", "span", "p", "a", "img", "video",
        "canvas", "form", "input", "button", "iframe"
    )

    private val EXACT_GENERIC_UI_SELECTORS = setOf(
        ".banner", "#banner", ".header", "#header", ".footer", "#footer",
        ".hero", "#hero", ".slider", "#slider", ".carousel", "#carousel",
        ".cookie", "#cookie", ".auth", "#auth", ".login", "#login",
        ".player", "#player", ".card", "#card", ".panel", "#panel",
        ".modal", "#modal", ".dialog", "#dialog", ".popup", "#popup",
        ".overlay", "#overlay", ".drawer", "#drawer", ".sidebar", "#sidebar",
        ".navbar", "#navbar", ".menu", "#menu", ".wrapper", "#wrapper",
        ".container", "#container", ".content", "#content"
    )

    // Forbidden substrings in EasyList cosmetic selectors that could hide legitimate UI elements or inject rules
    private val DANGEROUS_SELECTOR_TOKENS = listOf(
        "body", "html", "main", "header", "footer", "nav", "article", "section",
        "#app", "#root", "#main", "#content", ".content", ".container", ".wrapper",
        ".modal", ".dialog", ".popup", ".overlay", ".drawer", ".sidebar", ".navbar",
        ".menu", ".btn", ".button", ".floating-btn", "video", "canvas", "form", "input",
        ":-abp-", ":has(", ":has-text", ":xpath", ":matches-css", ":upward", ":remove",
        "+js(", "{", "}", "@media", "@import", "url(", "expression(", "javascript:",
        "/*", "*/", "\\", ";", "<", ">"
    )

    private val ATTR_BLOCK_RE = Regex("""\[([^\]]*)\]""")
    private val ATTR_OPERATOR_RE =
        Regex("""^\s*([a-zA-Z0-9_-]+)\s*(\*=|\^=|\$=|~=|!=|\|=|=)\s*(.*?)\s*$""")

    private val WEAK_SUBSTRING_WORDS = listOf(
        "head", "load", "down", "up", "shad", "grad", "read", "lead", "road", "thread"
    )

    private val GENERIC_UI_ATTRIBUTE_WORDS = listOf(
        "modal", "popup", "overlay", "dialog", "header", "footer", "content",
        "nav", "hero", "slider", "carousel", "cookie", "auth", "login",
        "player", "video", "card", "panel", "drawer", "sidebar", "menu",
        "wrapper", "container"
    )

    private val EXPLICIT_AD_BANNER_TOKENS = listOf(
        "ad-banner", "ads-banner", "ad_banner", "ads_banner",
        "jackpot-banner", "casino-banner", "slot-banner", "judi-banner"
    )

    internal fun isSafeCssSelector(selector: String): Boolean {
        val s = selector.trim()
        if (s.length < 3 || s.length > 120) return false
        if (s == "*" || s.startsWith("*") || s.endsWith("*")) return false
        if (s.count { it == '\'' } % 2 != 0 || s.count { it == '"' } % 2 != 0) return false
        if (s.count { it == '[' } != s.count { it == ']' }) return false
        if (s.count { it == '(' } != s.count { it == ')' }) return false
        val lower = s.lowercase()
        if (lower in BARE_HTML_TAGS || lower in EXACT_GENERIC_UI_SELECTORS) return false
        if (DANGEROUS_SELECTOR_TOKENS.any { lower.contains(it) }) return false
        val prefixOk = s.startsWith(".") || s.startsWith("#") || s.startsWith("ins.") ||
            s.startsWith("div[") || s.startsWith("iframe[") || s.startsWith("a[") ||
            s.startsWith("aside[") || s.startsWith("span[") || s.startsWith("ins[") ||
            s.startsWith("[")
        if (!prefixOk) return false
        return !hasWeakAttributeSubstring(s)
    }

    // Pola atribut hanya boleh jika nilainya tidak kosong, cukup spesifik, dan
    // tidak mengandung kata komponen UI sah (banner, modal, popup, overlay, dll.).
    private fun hasWeakAttributeSubstring(s: String): Boolean {
        if (s.contains("[]")) return true
        for (blockMatch in ATTR_BLOCK_RE.findAll(s)) {
            val content = blockMatch.groupValues[1].trim()
            if (content.isEmpty()) return true
            if (!content.contains("=")) {
                val attrLower = content.lowercase()
                if (!attrLower.startsWith("data-ad") && !attrLower.startsWith("data-google")) {
                    return true
                }
                continue
            }
            val opMatch = ATTR_OPERATOR_RE.matchEntire(content) ?: return true
            val attrName = opMatch.groupValues[1].lowercase()
            var rawVal = opMatch.groupValues[3].trim()
            if ((rawVal.endsWith(" i") || rawVal.endsWith(" s")) && rawVal.length > 2) {
                rawVal = rawVal.dropLast(2).trim()
            }
            if ((rawVal.startsWith("\"") && rawVal.endsWith("\"")) ||
                (rawVal.startsWith("'") && rawVal.endsWith("'"))
            ) {
                rawVal = rawVal.substring(1, rawVal.length - 1)
            }
            val v = rawVal.trim().lowercase()
            if (v.isEmpty()) return true
            if (attrName.startsWith("data-ad") || attrName.startsWith("data-google")) {
                continue
            }
            if (v.length < 6) return true
            if (WEAK_SUBSTRING_WORDS.any { v.contains(it) }) return true
            if (GENERIC_UI_ATTRIBUTE_WORDS.any { v.contains(it) }) return true
            if (v.contains("banner") && EXPLICIT_AD_BANNER_TOKENS.none { v.contains(it) }) {
                return true
            }
        }
        return false
    }

    fun inject(webView: WebView, customSelectors: List<String> = emptyList(), pageUrl: String? = null) {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT) && customSelectors.isEmpty()) {
            return
        }
        val host = if (!pageUrl.isNullOrBlank()) {
            try {
                android.net.Uri.parse(pageUrl).host?.lowercase()?.removePrefix("www.") ?: ""
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        if (host.isNotEmpty() && com.yourbrowser.adblock.AdBlockWebViewClient.CRITICAL_BYPASS_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return
        }

        val rules = FilterListManager.getCachedRules()
        val safeSelectors = LinkedHashSet<String>()
        safeSelectors.addAll(SAFE_BUILT_IN_SELECTORS.filter { isSafeCssSelector(it) })

        customSelectors.forEach { sel ->
            if (AbpRuleEngine.isSafeCosmeticSelector(sel) && isSafeCssSelector(sel)) {
                safeSelectors.add(sel.trim())
            }
        }

        // Per-domain cosmetic selectors from ABP rule engine (filtered first, then capped at MAX_COSMETIC_SELECTORS_PER_HOST)
        rules?.ruleSet?.cosmetic?.selectorsFor(host)
            ?.asSequence()
            ?.filter { AbpRuleEngine.isSafeCosmeticSelector(it) }
            ?.filter { isSafeCssSelector(it) }
            ?.take(AbpRuleEngine.MAX_COSMETIC_SELECTORS_PER_HOST)
            ?.forEach { safeSelectors.add(it.trim()) }

        if (safeSelectors.isEmpty()) return

        // Group selectors in small chunks of 25 so a single unsupported selector in WebView never invalidates the rest.
        // Use stealth offscreen + zero-opacity instead of display:none so anti-adblock offsetHeight/display checks never trigger.
        val cssRules = safeSelectors.chunked(25).joinToString("\n") { chunk ->
            chunk.joinToString(", ") + " { opacity: 0 !important; pointer-events: none !important; max-height: 1px !important; max-width: 1px !important; overflow: hidden !important; position: absolute !important; left: -9999px !important; top: -9999px !important; z-index: -9999 !important; }"
        }

        val b64css = Base64.encodeToString(cssRules.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = """
            (function() {
                try {
                    var style = window.__asCosmeticRef;
                    if (!style || !style.isConnected) {
                        style = document.createElement('style');
                        style.type = 'text/css';
                        (document.head || document.documentElement).appendChild(style);
                        try {
                            Object.defineProperty(window, '__asCosmeticRef', {
                                value: style,
                                enumerable: false,
                                configurable: true
                            });
                        } catch(e) {}
                    }
                    style.textContent = window.atob('$b64css');
                } catch(e) {}
            })();
        """.trimIndent()

        try {
            webView.evaluateJavascript(js, null)
            Log.v(TAG, "Injected ${safeSelectors.size} safe stealth cosmetic rules")
        } catch (e: Exception) {
            Log.w(TAG, "Cosmetic injection failed: ${e.message}")
        }
    }
}
