package com.example.manager

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object DocumentStartCosmetics {

    private const val SCRIPT = """
(function() {
    try {
        if (!window.AlexBrowserNative || !window.AlexBrowserNative.getCosmeticCss) return;
        var host = location.hostname;
        var css = window.AlexBrowserNative.getCosmeticCss(host);
        if (css && css.length > 0) {
            var inject = function() {
                var target = document.head || document.documentElement;
                if (!target) return false;
                var style = document.createElement('style');
                style.type = 'text/css';
                style.setAttribute('data-alex-cosmetics', 'doc-start');
                style.appendChild(document.createTextNode(css));
                target.appendChild(style);
                return true;
            };
            if (!inject()) {
                document.addEventListener('DOMContentLoaded', inject, { once: true });
            }
        }
    } catch(e) {}
})();
    """

    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    SCRIPT,
                    setOf("*")
                )
            } catch (_: Exception) {}
        }
    }

    fun buildCosmeticCssForHost(host: String): String {
        val rules = FilterListManager.getCachedRules() ?: return ""
        // Filter DULU, baru batasi jumlah: selektor tidak aman tidak boleh memakan kuota.
        val selectors = rules.ruleSet.cosmetic.selectorsFor(host)
            .asSequence()
            .filter { AbpRuleEngine.isSafeCosmeticSelector(it) }
            .filter { CosmeticFilterInjector.isSafeCssSelector(it) }
            .take(AbpRuleEngine.MAX_COSMETIC_SELECTORS_PER_HOST)
            .toList()

        if (selectors.isEmpty()) return ""

        // Satu aturan per selektor: satu selektor korup tidak merusak yang lain.
        return selectors.joinToString("\n") { sel ->
            "$sel { opacity: 0 !important; pointer-events: none !important; " +
                "max-height: 1px !important; overflow: hidden !important; }"
        }
    }
}
