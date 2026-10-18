package com.example.manager

import android.util.Base64
import android.util.Log
import android.webkit.WebView

/**
 * Upgraded Bug-Free Cosmetic Filter Injector (V2).
 * Safely hides explicit ad containers without breaking site layouts, buttons, modals, or SPAs.
 */
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

    // Forbidden substrings in EasyList cosmetic selectors that could hide legitimate UI elements
    private val DANGEROUS_SELECTOR_TOKENS = listOf(
        "body", "html", "main", "header", "footer", "nav", "article", "section",
        "#app", "#root", "#main", "#content", ".content", ".container", ".wrapper",
        ".modal", ".dialog", ".popup", ".overlay", ".drawer", ".sidebar", ".navbar",
        ".menu", ".btn", ".button", ".floating-btn", "video", "canvas", "form", "input",
        ":-abp-", ":has(", ":has-text", ":xpath", ":matches-css", ":upward", ":remove",
        "+js(", "{", "}", "@media", "@import"
    )

    private fun isSafeCssSelector(selector: String): Boolean {
        val s = selector.trim()
        if (s.length < 3 || s.length > 120) return false
        if (s == "*" || s.startsWith("*") || s.endsWith("*")) return false
        val lower = s.lowercase()
        if (DANGEROUS_SELECTOR_TOKENS.any { lower.contains(it) }) return false
        // Must start with class (.), id (#), tag+attr (div[ / ins[ / iframe[ / a[), or attribute ([data-ad)
        return s.startsWith(".") ||
            s.startsWith("#") ||
            s.startsWith("ins.") ||
            s.startsWith("div[") ||
            s.startsWith("iframe[") ||
            s.startsWith("a[") ||
            s.startsWith("[data-ad") ||
            s.startsWith("[data-google")
    }

    fun inject(webView: WebView, customSelectors: List<String> = emptyList()) {
        val rules = FilterListManager.getCachedRules()
        val safeSelectors = LinkedHashSet<String>()
        safeSelectors.addAll(SAFE_BUILT_IN_SELECTORS)

        customSelectors.forEach { sel ->
            if (isSafeCssSelector(sel)) safeSelectors.add(sel.trim())
        }

        // Only include strictly verified EasyList selectors (capped at 220 to keep CSS parsing instantaneous)
        rules?.cosmeticSelectors
            ?.asSequence()
            ?.filter { isSafeCssSelector(it) }
            ?.take(220)
            ?.forEach { safeSelectors.add(it.trim()) }

        if (safeSelectors.isEmpty()) return

        // Group selectors in small chunks of 25 so a single unsupported selector in WebView never invalidates the rest
        val cssRules = safeSelectors.chunked(25).joinToString("\n") { chunk ->
            chunk.joinToString(", ") + " { display: none !important; visibility: hidden !important; pointer-events: none !important; }"
        }

        val b64css = Base64.encodeToString(cssRules.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = """
            (function() {
                try {
                    var style = document.getElementById('__alex_adblock_css');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = '__alex_adblock_css';
                        style.type = 'text/css';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = window.atob('$b64css');
                } catch(e) {}
            })();
        """.trimIndent()

        try {
            webView.evaluateJavascript(js, null)
            Log.v(TAG, "Injected ${safeSelectors.size} safe cosmetic rules")
        } catch (e: Exception) {
            Log.w(TAG, "Cosmetic injection failed: ${e.message}")
        }
    }
}
