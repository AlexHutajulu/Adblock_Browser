package com.example.ui.components

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.devtools.DevToolsJsBridge
import com.example.manager.AppDownloadManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * SECURITY FIX (audit H2): replaces `addJavascriptInterface`.
 *
 * `addJavascriptInterface` exposes the native object to every frame,
 * including third-party ad iframes, with no way to tell who is calling.
 * This bridge uses `WebViewCompat.addWebMessageListener`, which gives the
 * caller's origin and whether the message came from the main frame. Messages
 * from iframes, opaque/non-web origins, or origins that do not match the page
 * currently loaded in the tab are dropped.
 *
 * Page-side API:
 * - [SHIM_JS] defines `window.AlexBrowserNative` in the top frame as a thin
 *   wrapper that sends `{"m": method, "a": [args]}` over [NATIVE_PORT], so
 *   existing injected scripts keep calling `window.AlexBrowserNative.x(...)`.
 * - `getCosmeticCss` is asynchronous now: use
 *   `AlexBrowserNative.getCosmeticCssAsync(host, callback)`.
 * - DevTools posts the selected-element JSON directly to [DEVTOOLS_PORT].
 *
 * If the installed WebView does not support WEB_MESSAGE_LISTENER, the bridge
 * is not installed at all and the related features are turned off. We do not
 * fall back to the insecure interface.
 */
object NativeMessageBridge {
    private const val TAG = "NativeMessageBridge"

    const val NATIVE_PORT = "__alexNativePort"
    const val DEVTOOLS_PORT = "__alexDevToolsPort"

    /** Largest message accepted: a blob data URI plus JSON envelope overhead. */
    internal const val MAX_MESSAGE_LENGTH = AppDownloadManager.MAX_DATA_URI_LENGTH + 64 * 1024
    private const val MAX_METHOD_LENGTH = 64
    private const val MAX_ARGS = 8

    /**
     * The object has to be injected for any site the user visits, so the
     * origin rule is a wildcard. Trust is decided per message in
     * [isTrustedSender] (main frame plus matching page origin).
     */
    private val ALLOWED_ORIGIN_RULES = setOf("*")

    /** Same threading model as the old binder-thread JS interface. */
    private val dispatchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlexNativeBridge").apply { isDaemon = true }
    }

    data class Call(
        val method: String,
        val args: JSONArray,
        /** Host taken from the verified sender origin, not from page data. */
        val originHost: String
    )

    val SHIM_JS: String = """
(function() {
  try {
    if (window.top !== window) return;
    var port = window.$NATIVE_PORT;
    if (!port) return;
    if (window.AlexBrowserNative && window.AlexBrowserNative.__alexShim) return;
    var send = function(m, a) {
      try { port.postMessage(JSON.stringify({ m: m, a: a })); } catch (e) {}
    };
    var cssCallbacks = [];
    port.onmessage = function(ev) {
      try {
        var d = JSON.parse(ev.data);
        if (d && d.m === 'cosmeticCss') {
          var cbs = cssCallbacks; cssCallbacks = [];
          for (var i = 0; i < cbs.length; i++) { try { cbs[i](String(d.css || '')); } catch (e) {} }
        }
      } catch (e) {}
    };
    var api = {
      __alexShim: true,
      openUrlInNewTab: function(u) { send('openUrlInNewTab', [String(u)]); },
      onAudioStateChanged: function(p) { send('onAudioStateChanged', [!!p]); },
      onMediaMetadataChanged: function(p, t, ar, c, d, art) {
        send('onMediaMetadataChanged', [!!p, String(t || ''), String(ar || ''), Number(c) || 0, Number(d) || 0, String(art || '')]);
      },
      onStealthTrapDefused: function(k) { send('onStealthTrapDefused', [String(k)]); },
      onElementZapperSelected: function(s, t, tok) { send('onElementZapperSelected', [String(s), String(t), String(tok)]); },
      onWebRtcBlocked: function(u) { send('onWebRtcBlocked', [String(u)]); },
      downloadVerifiedBlob: function(b, d, f, m) { send('downloadVerifiedBlob', [String(b), String(d), String(f), String(m)]); },
      getCosmeticCssAsync: function(h, cb) {
        if (typeof cb !== 'function') return;
        cssCallbacks.push(cb);
        send('getCosmeticCss', [String(h)]);
      }
    };
    Object.defineProperty(window, 'AlexBrowserNative', {
      value: Object.freeze(api), writable: false, configurable: false, enumerable: false
    });
  } catch (e) {}
})();
""".trimIndent()

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private fun isDocumentStartSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    // ---------------------------------------------------------------------
    // Pure validation helpers (unit-tested)
    // ---------------------------------------------------------------------

    /** Lower-cased host of an http(s) origin/URL, or null for anything else. */
    internal fun webHostOf(originOrUrl: String?): String? {
        val raw = originOrUrl?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return null
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = raw.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return null
        var rest = raw.substring(schemeEnd + 3)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        if (rest.contains('@')) rest = rest.substringAfterLast('@')
        val host = if (rest.startsWith("[")) {
            rest.substringBefore(']') + "]"
        } else {
            rest.substringBefore(':')
        }
        return host.lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
    }

    /**
     * A message is trusted only if it comes from the top-level frame, the
     * origin is a real web origin (no opaque/file/data/content), and the
     * origin host equals the host of the page currently loaded in the tab.
     */
    internal fun isTrustedSender(
        sourceOrigin: String?,
        isMainFrame: Boolean,
        currentPageUrl: String?,
        requirePageMatch: Boolean = true
    ): Boolean {
        if (!isMainFrame) return false
        val originHost = webHostOf(sourceOrigin) ?: return false
        if (!requirePageMatch) return true
        val pageHost = webHostOf(currentPageUrl) ?: return false
        return originHost == pageHost
    }

    /** Parses `{"m": "...", "a": [...]}`; returns null if malformed or oversized. */
    internal fun parseCall(raw: String?, originHost: String): Call? {
        if (raw.isNullOrEmpty() || raw.length > MAX_MESSAGE_LENGTH) return null
        return try {
            val obj = JSONObject(raw)
            val method = obj.optString("m", "")
            if (method.isEmpty() || method.length > MAX_METHOD_LENGTH) return null
            if (!method.all { it.isLetterOrDigit() || it == '_' }) return null
            val args = obj.optJSONArray("a") ?: JSONArray()
            if (args.length() > MAX_ARGS) return null
            Call(method, args, originHost)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------------

    /**
     * Installs the main bridge on [webView]. Call this before the first
     * navigation, and before any other document-start script that uses
     * `window.AlexBrowserNative`, because document-start scripts run in the
     * order they were added.
     *
     * [onCall] runs on a background thread. [onCosmeticCss] runs on a
     * background thread and returns CSS for the verified origin host; the
     * reply goes back to the page on the UI thread.
     *
     * @return false if the WebView does not support the secure bridge.
     */
    fun install(
        webView: WebView,
        currentPageUrl: () -> String?,
        onCosmeticCss: (originHost: String) -> String,
        onCall: (Call) -> Unit
    ): Boolean {
        if (!isSupported()) {
            Log.w(TAG, "WEB_MESSAGE_LISTENER unsupported; native bridge disabled")
            return false
        }
        return try {
            safeRemove(webView, NATIVE_PORT)
            WebViewCompat.addWebMessageListener(
                webView,
                NATIVE_PORT,
                ALLOWED_ORIGIN_RULES
            ) { view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy ->
                val origin = sourceOrigin.toString()
                val raw = message.data
                val originHost = webHostOf(origin)
                // Cheap pre-check before parsing; the method name decides how strict to be.
                if (!isMainFrame || originHost == null || raw == null) return@addWebMessageListener
                val call = parseCall(raw, originHost) ?: return@addWebMessageListener

                if (call.method == "getCosmeticCss") {
                    // Read-only and scoped to the sender's own origin, so the
                    // page-URL match is not needed. This message can arrive at
                    // document start, before onPageStarted has updated the URL.
                    dispatchExecutor.execute {
                        val css = try { onCosmeticCss(call.originHost) } catch (_: Exception) { "" }
                        val reply = JSONObject().put("m", "cosmeticCss").put("css", css).toString()
                        view.post {
                            try { replyProxy.postMessage(reply) } catch (_: Exception) {}
                        }
                    }
                    return@addWebMessageListener
                }

                if (!isTrustedSender(origin, isMainFrame, currentPageUrl())) {
                    Log.w(TAG, "Dropped bridge call '${call.method}' from untrusted origin")
                    return@addWebMessageListener
                }
                dispatchExecutor.execute {
                    try { onCall(call) } catch (e: Exception) {
                        Log.w(TAG, "Bridge call '${call.method}' failed: ${e.javaClass.simpleName}")
                    }
                }
            }
            installShim(webView)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install native bridge: ${e.message}")
            false
        }
    }

    private fun installShim(webView: WebView) {
        if (isDocumentStartSupported()) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, SHIM_JS, ALLOWED_ORIGIN_RULES)
            } catch (e: Exception) {
                Log.w(TAG, "Document-start shim failed: ${e.message}")
            }
        }
    }

    /**
     * For WebViews without DOCUMENT_START_SCRIPT, call this from
     * `onPageStarted`/`onPageFinished`. Safe to call more than once.
     */
    fun ensureShimFallback(webView: WebView) {
        if (!isSupported() || isDocumentStartSupported()) return
        try {
            webView.evaluateJavascript(SHIM_JS, null)
        } catch (_: Exception) {}
    }

    /**
     * Installs the DevTools listener. Like `addJavascriptInterface`, a page
     * only sees it after its next navigation.
     */
    fun installDevTools(
        webView: WebView,
        bridge: DevToolsJsBridge,
        currentPageUrl: () -> String?
    ): Boolean {
        if (!isSupported()) return false
        return try {
            safeRemove(webView, DEVTOOLS_PORT)
            WebViewCompat.addWebMessageListener(
                webView,
                DEVTOOLS_PORT,
                ALLOWED_ORIGIN_RULES
            ) { _: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
                if (!isTrustedSender(sourceOrigin.toString(), isMainFrame, currentPageUrl())) {
                    return@addWebMessageListener
                }
                val json = message.data ?: return@addWebMessageListener
                dispatchExecutor.execute { bridge.onElementSelected(json) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install DevTools bridge: ${e.message}")
            false
        }
    }

    fun removeDevTools(webView: WebView) = safeRemove(webView, DEVTOOLS_PORT)

    fun removeAll(webView: WebView) {
        safeRemove(webView, NATIVE_PORT)
        safeRemove(webView, DEVTOOLS_PORT)
    }

    private fun safeRemove(webView: WebView, name: String) {
        if (!isSupported()) return
        try {
            WebViewCompat.removeWebMessageListener(webView, name)
        } catch (_: Exception) {}
    }
}
