package com.yourbrowser.adblock

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ─── Enums & Models ───────────────────────────────────────────────────────────

enum class PrivacyMode { OFF, BALANCED, AGGRESSIVE }

data class AggressiveMetrics(
    val blockedRequests: Int = 0,
    val hiddenElements: Int = 0,
    val ytAdsSkipped: Int = 0
)

// ─── AdBlockWebViewClient ─────────────────────────────────────────────────────

class AdBlockWebViewClient(
    mode: PrivacyMode = PrivacyMode.BALANCED,
    private val onBlocked: ((blockedUrl: String, reason: String) -> Unit)? = null
) {
    private val currentMode  = AtomicReference(mode)
    private val pageUrl      = AtomicReference("")
    private val whitelisted  = CopyOnWriteArraySet<String>()
    private val blockedCount = AtomicInteger(0)
    private val hiddenCount  = AtomicInteger(0)
    private val ytCount      = AtomicInteger(0)

    // Decision cache — key = "pageHost|requestHost|url", value = "" (allowed) or non-empty block reason
    private val cache = ConcurrentHashMap<String, String>(1024)

    // ── Public API ────────────────────────────────────────────────────────────

    fun setMode(m: PrivacyMode) { currentMode.set(m); cache.clear() }
    fun updateCurrentPageUrl(url: String?) { if (!url.isNullOrBlank()) pageUrl.set(url) }
    fun whitelistHost(host: String) { whitelisted.add(normalizeHost(host)); cache.clear() }
    fun clearWhitelist() { whitelisted.clear(); cache.clear() }
    fun getAggressiveMetrics() = AggressiveMetrics(blockedCount.get(), hiddenCount.get(), ytCount.get())

    // ── Lifecycle passthroughs ─────────────────────────────────────────────────

    fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        updateCurrentPageUrl(url)
        if (url.isNullOrBlank() || currentMode.get() == PrivacyMode.OFF) return
        val host = safeHost(url)
        if (isWhitelistedHost(host)) return
        view.evaluateJavascript(buildEarlyScript(host), null)
    }

    fun onPageFinished(view: WebView, url: String?) {
        updateCurrentPageUrl(url)
        if (url.isNullOrBlank() || currentMode.get() == PrivacyMode.OFF) return
        val host = safeHost(url)
        if (isWhitelistedHost(host)) return
        val css = buildCosmeticCSS(host)
        if (css.isNotBlank()) injectCSS(view, css)
        view.evaluateJavascript(buildPageScript(host), null)
    }

    fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
        // passthrough — tidak ada action khusus
    }

    // ── Main intercept ────────────────────────────────────────────────────────

    fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val mode = currentMode.get()
        if (mode == PrivacyMode.OFF) return null
        if (request.isForMainFrame) return null

        val url     = request.url?.toString() ?: return null
        val reqHost = request.url?.host?.lowercase(Locale.US) ?: return null
        val pgHost  = safeHost(pageUrl.get())

        // 1. Whitelisted page
        if (isWhitelistedHost(pgHost) || isWhitelistedHost(reqHost)) return null

        // 2. SAFE DOMAINS — harus PERTAMA, tidak boleh dipindah
        if (isSafeDomain(reqHost)) return null

        // 3. Cache check
        val cacheKey = "$pgHost|$reqHost|$url"
        cache[cacheKey]?.let { cachedReason ->
            if (cachedReason.isNotEmpty()) {
                blockedCount.incrementAndGet()
                onBlocked?.invoke(url, cachedReason)
                return emptyResponse(request.url, null)
            }
            return null
        }

        val lower = url.lowercase(Locale.US)
        val blockReason = checkRules(reqHost, pgHost, lower, mode)

        // Trim cache
        if (cache.size > 6_000) { val keys = cache.keys.take(1_500); keys.forEach { cache.remove(it) } }
        cache[cacheKey] = blockReason ?: ""

        return if (blockReason != null) {
            blockedCount.incrementAndGet()
            onBlocked?.invoke(url, blockReason)
            emptyResponse(request.url, null)
        } else null
    }

    // ── Navigation redirect check ─────────────────────────────────────────────

    fun shouldBlockNavigationRedirect(url: String, mainPageUrl: String, hasGesture: Boolean): Boolean {
        if (currentMode.get() == PrivacyMode.OFF) return false
        if (hasGesture) return false  // user initiated — jangan block
        val host = safeHost(url)
        if (isSafeDomain(host)) return false
        if (isWhitelistedHost(host)) return false
        // Hanya block redirect ke domain iklan yang sudah dikenal
        val isRedirectBlocked = REDIRECT_BLOCK_DOMAINS.any { host == it || host.endsWith(".$it") }
        if (isRedirectBlocked) {
            blockedCount.incrementAndGet()
            onBlocked?.invoke(url, "popup-redirect")
        }
        return isRedirectBlocked
    }

    // ─── Core rule check ──────────────────────────────────────────────────────

    private fun checkRules(reqHost: String, pgHost: String, lowerUrl: String, mode: PrivacyMode): String? {
        val isFirstParty = pgHost.isNotEmpty() &&
            (reqHost == pgHost || reqHost.endsWith(".$pgHost") || pgHost.endsWith(".$reqHost"))

        // Protect video/streaming segments (.m3u8, .ts, .mp4, .m4s, .mpd) from false-positive blocking
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".m4s") || lowerUrl.contains(".mpd") ||
            lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".ts") || lowerUrl.endsWith(".webm")
        ) {
            if (!AD_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return null
            }
        }

        // 1. Hardcoded explicit ad & tracker domains (always third-party ad networks)
        if (!isFirstParty) {
            if (AD_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) return "ad-domain"
            if (TRACKER_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) return "tracker-domain"
        }

        // Explicit ad/tracker path keywords
        if (AD_PATH_KEYWORDS.any { lowerUrl.contains(it) }) {
            return if (lowerUrl.contains("analytics") || lowerUrl.contains("collect") || lowerUrl.contains("ptracking") || lowerUrl.contains("log_event")) {
                "tracker-keyword"
            } else {
                "ad-keyword"
            }
        }

        // Aggressive mode extras (only on third-party or explicit ad paths)
        if (mode == PrivacyMode.AGGRESSIVE) {
            if (!isFirstParty && AGGRESSIVE_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return "aggressive-ad"
            }
            if (AGGRESSIVE_PATH_KEYWORDS.any { lowerUrl.contains(it) }) return "aggressive-keyword"
        }

        // 2. EasyList rules evaluation ( strictly third-party to prevent breaking site assets )
        if (!isFirstParty) {
            val rules = com.example.manager.FilterListManager.getCachedRules()
            if (rules != null) {
                if (rules.whitelistDomains.any { reqHost == it || reqHost.endsWith(".$it") }) return null
                if (isDomainBlocked(reqHost, rules.blockDomains)) return "easylist-domain"

                if (mode == PrivacyMode.AGGRESSIVE) {
                    for (pattern in rules.blockPatterns) {
                        if (pattern.containsMatchIn(lowerUrl)) return "easylist-pattern"
                    }
                }
            }
        }

        return null
    }

    private fun isDomainBlocked(host: String, blockSet: HashSet<String>): Boolean {
        if (host.isEmpty()) return false
        if (blockSet.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            val parent = host.substring(idx + 1)
            if (blockSet.contains(parent)) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    // ─── Safe domains (NEVER blocked) ────────────────────────────────────────

    private fun isSafeDomain(host: String): Boolean {
        return SAFE_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    private fun isWhitelistedHost(host: String): Boolean {
        val n = normalizeHost(host)
        return whitelisted.any { n == it || n.endsWith(".$it") }
    }

    // ─── CSS injection ─────────────────────────────────────────────────────────

    private fun injectCSS(view: WebView, css: String) {
        val escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
        view.evaluateJavascript("""
(function(){
  var id='__alexshield_css__';
  var el=document.getElementById(id);
  if(!el){el=document.createElement('style');el.id=id;(document.head||document.documentElement).appendChild(el);}
  el.textContent=`$escaped`;
})();""", null)
    }

    // ─── Early script (sebelum halaman JS jalan) ──────────────────────────────

    private fun buildEarlyScript(hostname: String): String = buildString {
        append(EARLY_CORE_SCRIPT)
        if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
            append("\n").append(YT_EARLY_SCRIPT)
        }
    }

    // ─── Page script (setelah DOM siap) ───────────────────────────────────────

    private fun buildPageScript(hostname: String): String {
        val mode = currentMode.get()
        return buildString {
            append(BASE_CLEANUP_SCRIPT)
            if (mode == PrivacyMode.AGGRESSIVE) {
                append("\n").append(buildVisualAdKiller(aggressive = true))
            } else {
                append("\n").append(buildVisualAdKiller(aggressive = false))
            }
            if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
                append("\n").append(YT_PAGE_SCRIPT)
            }
        }
    }

    // ─── Cosmetic CSS ──────────────────────────────────────────────────────────

    private fun buildCosmeticCSS(hostname: String): String {
        val sel = LinkedHashSet<String>(GLOBAL_COSMETIC)
        if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
            sel.addAll(YT_COSMETIC)
        }
        DOMAIN_COSMETIC.forEach { (domain, rules) ->
            if (hostname == domain || hostname.endsWith(".$domain")) sel.addAll(rules)
        }
        if (sel.isEmpty()) return ""
        return sel.joinToString(",\n") +
            " { display:none !important; visibility:hidden !important; pointer-events:none !important; }"
    }

    // ─── Empty response builder ────────────────────────────────────────────────

    private fun emptyResponse(uri: Uri?, mimeHint: String?): WebResourceResponse {
        val path = uri?.path.orEmpty().lowercase(Locale.US)
        val mime = mimeHint ?: when {
            path.endsWith(".js")   -> "application/javascript"
            path.endsWith(".css")  -> "text/css"
            path.endsWith(".json") -> "application/json"
            else -> "text/plain"
        }
        val body = when (mime) {
            "application/javascript" -> "/* blocked */".toByteArray()
            "text/css"               -> "/* blocked */".toByteArray()
            "application/json"       -> "{}".toByteArray()
            else                     -> ByteArray(0)
        }
        val statusCode = if (body.isEmpty()) 204 else 200
        val reasonPhrase = if (body.isEmpty()) "No Content" else "OK"
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebResourceResponse(mime, "utf-8", statusCode, reasonPhrase, headers, ByteArrayInputStream(body))
        } else {
            @Suppress("DEPRECATION")
            WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
        }
    }

    private fun normalizeHost(h: String) = h.trim().lowercase(Locale.US).removePrefix("www.")
    private fun safeHost(url: String?) = runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")

    // ─── Rule sets ─────────────────────────────────────────────────────────────

    companion object {

        /**
         * SAFE_DOMAINS — tidak pernah diblok dalam kondisi apapun.
         * Ini mencegah Google, YouTube, CloudFlare, dan semua infrastruktur penting dari
         * terblokir secara tidak sengaja.
         */
        private val SAFE_DOMAINS = setOf(
            // Google ecosystem — WAJIB whitelist penuh
            "google.com", "google.co.id", "google.co.uk", "google.de", "google.fr",
            "google.co.jp", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "googletagmanager.com",   // GTM dipakai banyak site untuk fitur penting
            "youtube.com", "youtu.be", "ytimg.com", "yt3.ggpht.com",
            "googlevideo.com",         // stream video YouTube
            "accounts.google.com",
            "music.youtube.com",
            // Auth / Captcha / Security
            "recaptcha.net", "hcaptcha.com", "hcaptcha.net",
            "challenges.cloudflare.com", "turnstile.cloudflare.com",
            "arkoselabs.com", "funcaptcha.com", "geetest.com",
            "auth0.com", "okta.com", "onelogin.com",
            // CDN infra — jangan block, banyak site bergantung
            "cloudflare.com", "cloudflare.net",
            "cloudfront.net",
            "fastly.net", "fastly.com",
            "akamaihd.net", "akamai.net", "akamai.com",
            "cdn.jsdelivr.net", "jsdelivr.net",
            "cdnjs.cloudflare.com",
            "unpkg.com",
            "jquery.com",
            "bootstrapcdn.com",
            "fontawesome.com",
            // Social login (dipakai untuk OAuth)
            "appleid.apple.com",
            "login.microsoftonline.com", "microsoftonline.com",
            "login.live.com",
            // Payment gateway Indonesia
            "midtrans.com", "xendit.co", "doku.com",
            "snap.midtrans.com",
            // Payment global
            "paypal.com", "stripe.com", "braintreegateway.com",
            // Media & streaming
            "spotify.com",
            // Common API / infra
            "amazonaws.com", "s3.amazonaws.com",
            "azureedge.net", "azure.com",
            // WhatsApp / Meta (login only — jangan block seluruhnya)
            "web.whatsapp.com",
            // Analytics exceptions — Google Analytics masih diblock via keyword,
            // tapi domain google.com sendiri tidak boleh di-block
        )

        /** Domain iklan — diblok semua mode */
        private val AD_DOMAINS = setOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com",
            "adnxs.com", "criteo.com", "outbrain.com", "taboola.com",
            "pubmatic.com", "rubiconproject.com", "openx.net", "adroll.com",
            "media.net", "propellerads.com", "exoclick.com", "adsterra.com",
            "popads.net", "popcash.net", "mgid.com", "revcontent.com",
            "zeropark.com", "trafficjunky.com", "go.xlviirdr.com",
            "onclickads.net", "adcash.com",
            "adsymptotic.com", "adcolony.com", "applovin.com",
            "mopub.com", "chartboost.com", "inneractive.com",
            "smartadserver.com", "teads.tv", "adform.net",
            "sharethrough.com", "indexexchange.com", "casalemedia.com",
            "33across.com", "bidswitch.net", "emxdgt.com",
            "contextweb.com", "yldbt.com", "adtelligent.com",
            "adsafeprotected.com", "moatads.com",
            // Gambling/scam ad networks yang sering inject banner
            "1xbet.com", "bet365.com",
            "woclin.com", "shorte.st", "adf.ly", "ouo.io",
        )

        /** Tracker — diblok semua mode */
        private val TRACKER_DOMAINS = setOf(
            "google-analytics.com", "analytics.google.com",
            "hotjar.com", "clarity.ms", "mixpanel.com",
            "amplitude.com", "segment.com", "segment.io",
            "fullstory.com", "logrocket.com", "inspectlet.com",
            "mouseflow.com", "crazyegg.com",
            "scorecardresearch.com", "quantserve.com",
            "facebook.net", "connect.facebook.net", "pixel.facebook.com",
            "analytics.tiktok.com",
            "ads.linkedin.com", "snap.licdn.com",
            "static.ads-twitter.com",
        )

        /** Domain iklan ekstra untuk mode Aggressive */
        private val AGGRESSIVE_DOMAINS = setOf(
            "amazon-adsystem.com", "adsystem.amazon.com",
            "advertising.com", "everesttech.net",
            "rlcdn.com", "w55c.net",
            "ads-twitter.com",
        )

        /**
         * Keyword PATH — hanya path sangat spesifik.
         * TIDAK ADA kata umum seperti "slot", "casino", "bonus", "spin".
         * Kata-kata itu hanya boleh ada di Visual Ad Killer (JS/DOM).
         */
        private val AD_PATH_KEYWORDS = listOf(
            "/api/stats/ads",
            "/pagead/",
            "/ptracking",
            "/youtubei/v1/log_event",
            "google-analytics.com/collect",
            "google-analytics.com/j/collect",
            "google-analytics.com/g/collect",
            "/doubleclick/",
            "/adsystem/",
            "/prebid.js",
            "/vast.xml",
            "/vpaid.js",
            "/adserver/ads",
        )

        /** Path keywords tambahan untuk mode Aggressive */
        private val AGGRESSIVE_PATH_KEYWORDS = listOf(
            "/advertising/banner",
            "/ads/banner",
            "/adserver/",
            "/adbanner/",
            "/popunder/",
        )

        /** Domain yang boleh diblok saat redirect tanpa gesture */
        private val REDIRECT_BLOCK_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "taboola.com", "outbrain.com", "exoclick.com",
            "propellerads.com", "adsterra.com", "popads.net",
        )

        // ─── Cosmetic selectors ──────────────────────────────────────────────

        private val GLOBAL_COSMETIC = setOf(
            "ins.adsbygoogle",
            "[data-ad-unit]", "[data-google-query-id]", "[data-adsbygoogle-status]",
            "iframe[src*='doubleclick']", "iframe[src*='googlesyndication']",
            "iframe[src*='adnxs']", "iframe[src*='amazon-adsystem']",
            "iframe[src*='taboola']", "iframe[src*='outbrain']",
            "div[id^='google_ads']", "[class*='adsbygoogle']",
            ".ad-banner", ".ads-banner-container", ".banner-ad",
            ".advertisement-box", ".advertise-here",
            "div[id^='taboola-']", "div[class*='taboola-']",
            ".OUTBRAIN", "div[id^='ob_holder']",
            // Gambling ad containers with explicit classes (never generic .floating-btn)
            "div[class*='jackpot-banner']", "div[class*='casino-banner']",
            "div[class*='slot-banner']", "div[class*='judi-banner']",
            "a[class*='floating-ad-banner']"
        )

        private val YT_COSMETIC = setOf(
            ".ytp-ad-overlay-container", ".ytp-ad-text-overlay", ".ytp-ad-image-overlay",
            ".ytp-ad-progress", ".ytp-ad-progress-list",
            "ytd-promoted-sparkles-web-renderer", "ytd-display-ad-renderer",
            "ytd-companion-slot-renderer", "ytd-action-companion-ad-renderer",
            "ytd-promoted-video-renderer", "ytd-search-pyv-renderer",
            "ytd-in-feed-ad-layout-renderer", "ytd-ad-slot-renderer",
            "ytd-statement-banner-renderer", "ytd-promoted-sparkles-text-search-renderer",
            "ytd-banner-promo-renderer", "ytm-promoted-sparkles-web-renderer",
            "#masthead-ad", "#player-ads",
        )

        private val DOMAIN_COSMETIC = mapOf(
            "reddit.com"    to listOf(".promotedlink", "[data-testid='promoted-post']", "shreddit-ad-post"),
            "x.com"         to listOf("[data-testid='placementTracking']"),
            "twitter.com"   to listOf("[data-testid='placementTracking']"),
            "facebook.com"  to listOf("[aria-label='Sponsored']", "[data-pagelet*='sponsored']"),
            "instagram.com" to listOf("[aria-label='Sponsored']"),
        )

        // ─── Early script ────────────────────────────────────────────────────

        private val EARLY_CORE_SCRIPT = """
(function(){
  if(window.__AlexEarly) return;
  window.__AlexEarly=true;
  var B=/doubleclick|googlesyndication|adnxs|amazon-adsystem|criteo|taboola|outbrain|hotjar|clarity\.ms|mixpanel|segment\.(io|com)|amplitude|fullstory|scorecardresearch|google-analytics\.com\/(collect|j\/collect|g\/collect)|\/pagead\/|\/ptracking/i;
  function bad(u){try{return B.test(String(u||''));}catch(e){return false;}}
  var sb=navigator.sendBeacon&&navigator.sendBeacon.bind(navigator);
  if(sb) navigator.sendBeacon=function(u,d){return bad(u)?true:sb(u,d);};
  var of=window.fetch;
  if(of) window.fetch=function(r,i){
    var u=typeof r==='string'?r:(r&&r.url)||'';
    return bad(u)?Promise.resolve(new Response('',{status:204})):of.apply(this,arguments);
  };
  var xo=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(m,u){
    if(bad(u)){this.__ab=true;u='data:text/plain,';}
    return xo.apply(this,arguments);
  };
  var ow=window.open;
  window.open=function(u,t,f){return bad(u)?null:(ow?ow.apply(this,arguments):null);};
})();
        """.trimIndent()

        private val YT_EARLY_SCRIPT = """
(function(){
  if(window.__AlexYTEarly) return;
  window.__AlexYTEarly=true;
  function wipe(v){if(v){try{v.adPlacements=[];v.playerAds=[];v.adSlots=[];}catch(e){}}return v;}
  function stripFeed(d){
    if(!d) return d;
    try{
      var tabs=d&&d.contents&&d.contents.twoColumnBrowseResultsRenderer&&d.contents.twoColumnBrowseResultsRenderer.tabs;
      if(tabs) tabs.forEach(function(t){
        var c=t&&t.tabRenderer&&t.tabRenderer.content;
        var items=(c&&c.richGridRenderer&&c.richGridRenderer.contents)||(c&&c.sectionListRenderer&&c.sectionListRenderer.contents);
        if(!items) return;
        for(var i=items.length-1;i>=0;i--){
          var x=items[i];
          if(x.promotedSparklesWebRenderer||x.adSlotRenderer||x.searchPyvRenderer
          ||(x.richItemRenderer&&x.richItemRenderer.content&&(x.richItemRenderer.content.adSlotRenderer||x.richItemRenderer.content.promotedSparklesWebRenderer)))
            items.splice(i,1);
        }
      });
    }catch(e){}
    return d;
  }
  try{Object.defineProperty(window,'ytInitialPlayerResponse',{get:function(){return this.__ay;},set:function(v){this.__ay=wipe(v);},configurable:true});}catch(e){}
  try{Object.defineProperty(window,'ytInitialData',{get:function(){return this.__ad;},set:function(v){this.__ad=stripFeed(v);},configurable:true});}catch(e){}
  // Chain fetch (jangan timpa override EARLY_CORE_SCRIPT)
  var pf=window.fetch;
  var YB=['/api/stats/ads','/pagead/','/ptracking','/youtubei/v1/log_event','doubleclick.net/pagead'];
  window.fetch=function(r,i){
    var u=typeof r==='string'?r:(r&&r.url)||'';
    if(YB.some(function(e){return u.indexOf(e)>-1;})) return Promise.resolve(new Response('{}',{status:200}));
    return pf.apply(this,arguments);
  };
})();
        """.trimIndent()

        // ─── Base cleanup script ──────────────────────────────────────────────

        private val BASE_CLEANUP_SCRIPT = """
(function(){
  if(window.__AlexPage) return;
  window.__AlexPage=true;
  var BADSRC=['doubleclick','googlesyndication','adnxs','amazon-adsystem','rubiconproject','pubmatic','openx','taboola','outbrain','criteo'];
  function badSrc(s){return BADSRC.some(function(x){return(s||'').indexOf(x)>-1;});}
  function clean(root){
    try{
      (root||document).querySelectorAll('iframe[src]').forEach(function(n){if(badSrc(n.src||''))n.remove();});
      (root||document).querySelectorAll('ins.adsbygoogle,[data-ad-unit],[data-google-query-id]').forEach(function(n){n.remove();});
    }catch(e){}
  }
  clean(document);
  new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.nodeType===1)clean(n);});});})
    .observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── YouTube skip script ───────────────────────────────────────────────

        private val YT_PAGE_SCRIPT = """
(function(){
  if(window.__AlexYTSkip) return;
  window.__AlexYTSkip=true;
  function skip(){
    try{
      var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,button[class*="skip" i]');
      if(b){b.click();return;}
      var c=document.querySelector('.ytp-ad-overlay-close-button,.ytp-ad-feedback-dialog-close-button');
      if(c) c.click();
      var v=document.querySelector('video');
      var ad=document.querySelector('.ad-showing,.ytp-ad-player-overlay,.ytp-ad-simple-ad-badge,.ytp-ad-text');
      if(v&&ad&&isFinite(v.duration)&&v.duration>0){v.muted=true;v.currentTime=Math.max(v.duration-0.1,0);}
      document.querySelectorAll('ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,#player-ads,#masthead-ad').forEach(function(n){n.remove();});
    }catch(e){}
  }
  setInterval(skip,300);
  skip();
})();
        """.trimIndent()

        // ─── Visual Ad Killer ─────────────────────────────────────────────────

        fun buildVisualAdKiller(aggressive: Boolean): String = """
(function(){
  if(window.__AlexVAK) return;
  window.__AlexVAK=true;
  var AGG=${aggressive};

  // Frasa judi multi-kata — jauh lebih aman dari kata tunggal
  // Ini HANYA untuk cek teks DOM, BUKAN network URL
  var GAMBLING=['arab777','slot gacor','slot online','casino online',
    'judi online','togel online','betting online','daftar slot',
    'agen slot','main slot','menang slot','bonus slot','spin slot',
    'rtp slot','jackpot slot','scatter hitam','mahjong ways',
    'pragmatic play','pg soft','deposit pulsa slot','maxwin slot',
    'link alternatif slot'];

  // Kata yang MELINDUNGI elemen dari penghapusan
  var SAFE=['login','signin','sign in','sign up','signup','register',
    'auth','oauth','account','checkout','payment','billing',
    'captcha','recaptcha','hcaptcha','verify','password','username',
    'email','phone','otp','invoice','subscribe','newsletter'];

  // Ukuran banner IAB standar (w × h dalam pixel, toleransi ±25px)
  var IAB=[[728,90],[320,50],[320,100],[300,250],[336,280],[300,600],
           [160,600],[970,90],[468,60],[250,250],[200,200],[970,250]];

  function safeText(el){
    try{return[el.id||'',el.className||'',el.getAttribute('alt')||'',
      el.getAttribute('title')||'',el.getAttribute('aria-label')||'',
      el.getAttribute('src')||'',el.getAttribute('href')||'',
      el.textContent?el.textContent.substring(0,300):''].join(' ').toLowerCase();}
    catch(e){return'';}
  }

  function isProtected(el){
    try{var n=el;for(var i=0;n&&i<6;i++,n=n.parentElement){
      var t=safeText(n);
      if(SAFE.some(function(w){return t.includes(w);})) return true;
      if(n.tagName==='FORM'||n.tagName==='INPUT'||n.tagName==='SELECT') return true;
    }return false;}catch(e){return true;}
  }

  function hasGambling(el){
    var t=safeText(el);
    return GAMBLING.some(function(p){return t.includes(p);});
  }

  function isIAB(el){
    try{var r=el.getBoundingClientRect();var w=Math.round(r.width);var h=Math.round(r.height);
    if(w<50||h<20) return false;
    return IAB.some(function(s){return Math.abs(w-s[0])<=25&&Math.abs(h-s[1])<=25;});}
    catch(e){return false;}
  }

  function isFixedOverlay(el){
    try{var s=window.getComputedStyle(el);
    if(s.position!=='fixed'&&s.position!=='sticky') return false;
    var r=el.getBoundingClientRect();
    return r.width>=(window.innerWidth||320)*0.5&&r.height>=50;}
    catch(e){return false;}
  }

  function hide(el,r){
    try{if(!el||isProtected(el)) return;
    el.setAttribute('data-alexshield',r);
    el.style.setProperty('display','none','important');}catch(e){}
  }

  // Step 1: Hapus elemen standard ad selector
  function step1(){
    var SELS=['ins.adsbygoogle','[data-ad-unit]','[data-google-query-id]',
      'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]',
      'iframe[src*="adnxs"]','iframe[src*="amazon-adsystem"]',
      'div[id^="google_ads"]','[class*="adsbygoogle"]'];
    SELS.forEach(function(s){
      try{document.querySelectorAll(s).forEach(function(el){if(!isProtected(el))hide(el,'std');});}
      catch(e){}
    });
  }

  // Step 2: Hapus iframe/img berukuran IAB yang mengandung frasa judi
  function step2(){
    try{['iframe','img'].forEach(function(t){
      document.querySelectorAll(t+'[src]').forEach(function(el){
        if(isIAB(el)&&hasGambling(el)&&!isProtected(el)){
          var c=el.closest('div,section,aside')||el;
          if(!isProtected(c)) hide(c,'iab-gambling');
        }
      });
    });}catch(e){}
  }

  // Step 3: Hapus div/section berukuran IAB yang mengandung frasa judi
  function step3(){
    try{document.querySelectorAll('div,section,aside').forEach(function(el){
      // Skip container besar (lebih dari 30 children = navigasi/konten utama)
      if(el.children.length>30) return;
      if(isIAB(el)&&hasGambling(el)&&!isProtected(el)) hide(el,'gambling-banner');
    });}catch(e){}
  }

  // Step 4 (AGGRESSIVE): fixed/sticky overlay dengan konten judi
  function step4(){
    if(!AGG) return;
    try{document.querySelectorAll('div,section').forEach(function(el){
      if(isFixedOverlay(el)&&hasGambling(el)&&!isProtected(el)) hide(el,'fixed-gambling');
    });}catch(e){}
  }

  function runAll(){step1();step2();step3();step4();}

  if(document.readyState==='loading'){
    document.addEventListener('DOMContentLoaded',runAll,{once:true});
  }else{runAll();}

  var pending=false;
  new MutationObserver(function(ms){
    if(pending) return;pending=true;
    setTimeout(function(){pending=false;runAll();}, 400);
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()
    }
}