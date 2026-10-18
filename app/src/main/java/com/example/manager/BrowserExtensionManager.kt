package com.example.manager

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ExtensionRunAt {
    DOCUMENT_START,
    DOCUMENT_END,
    MANUAL_ONLY
}

enum class ExtensionSourceType {
    BUILT_IN,
    EXTERNAL_FILE,
    EXTERNAL_URL,
    CUSTOM_CODE
}

data class BrowserExtension(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "User",
    val iconName: String = "Extension",
    val jsCode: String = "",
    val cssCode: String = "",
    val matches: List<String> = listOf("*"), // "*" for all sites, or domain list e.g. ["youtube.com", "google.com"]
    val runAt: ExtensionRunAt = ExtensionRunAt.DOCUMENT_END,
    val isEnabled: Boolean = true,
    val isPinnedToToolbar: Boolean = false,
    val sourceType: ExtensionSourceType = ExtensionSourceType.CUSTOM_CODE,
    val sourceUrl: String = ""
)

object BrowserExtensionManager {
    private const val TAG = "BrowserExtensionMgr"
    private const val PREFS_NAME = "chrome_extensions_prefs_v1"
    private const val KEY_EXTENSIONS_JSON = "installed_extensions_json_v1"
    private const val KEY_SEEDED_DEFAULTS = "seeded_default_extensions_v1"

    val BUILT_IN_EXTENSIONS: List<BrowserExtension> = listOf(
        BrowserExtension(
            id = "ext_dark_reader_pro",
            name = "Dark Reader Pro",
            version = "2.4.0",
            description = "Mode gelap pintar untuk seluruh situs web tanpa merusak warna gambar, video, atau elemen kanvas.",
            author = "Chrome Web Store",
            iconName = "DarkMode",
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = false,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            cssCode = """
                html {
                    background-color: #121212 !important;
                    filter: invert(0.92) hue-rotate(180deg) !important;
                }
                img, video, picture, canvas, iframe, svg, [style*="background-image"] {
                    filter: invert(1) hue-rotate(180deg) !important;
                }
            """.trimIndent(),
            jsCode = """
                (function() {
                    var st = document.getElementById('__ext_css_ext_dark_reader_pro');
                    if (st && window.__extManualTrigger) {
                        st.remove();
                        return 'Dark Reader Pro: Nonaktif di halaman ini';
                    }
                    return 'Dark Reader Pro: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_sponsor_skip",
            name = "SponsorBlock & Auto-Skip Video",
            version = "3.1.2",
            description = "Otomatis melewati iklan video, menekan tombol Skip Ad, dan mempercepat segmen iklan di YouTube & situs streaming.",
            author = "SponsorBlock Community",
            iconName = "FastForward",
            matches = listOf("youtube.com", "youtu.be", "*"),
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    if (window.__extSponsorSkipActive) return 'SponsorBlock sudah berjalan aktif';
                    window.__extSponsorSkipActive = true;
                    function checkSkip() {
                        try {
                            var skipSelectors = [
                                '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern',
                                '.ytp-skip-ad-button', '.videoAdUiSkipButton',
                                'button[class*="skip-ad" i]', 'div[class*="skip-button" i]'
                            ];
                            for (var i = 0; i < skipSelectors.length; i++) {
                                var btn = document.querySelector(skipSelectors[i]);
                                if (btn) { btn.click(); }
                            }
                            var closeOverlay = document.querySelector('.ytp-ad-overlay-close-button');
                            if (closeOverlay) closeOverlay.click();
                            var adShowing = document.querySelector('.ad-showing, .ytp-ad-player-overlay');
                            var vid = document.querySelector('video');
                            if (adShowing && vid && isFinite(vid.duration) && vid.duration > 0) {
                                vid.muted = true;
                                vid.currentTime = Math.max(vid.duration - 0.1, 0);
                            }
                        } catch (e) {}
                    }
                    setInterval(checkSkip, 450);
                    checkSkip();
                    return 'SponsorBlock & Auto-Skip: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_unblock_copy",
            name = "Allow Copy & Right-Click Unlocker",
            version = "1.8.0",
            description = "Membuka kunci seleksi teks, salin (copy-paste), dan menu klik kanan pada situs artikel atau tugas yang membatasinya.",
            author = "Web Freedom Tools",
            iconName = "LockOpen",
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            cssCode = """
                *, *::before, *::after {
                    -webkit-user-select: text !important;
                    user-select: text !important;
                }
            """.trimIndent(),
            jsCode = """
                (function() {
                    ['copy', 'cut', 'paste', 'contextmenu', 'selectstart', 'dragstart'].forEach(function(ev) {
                        window.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                        document.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                    });
                    if (document.body) {
                        document.body.oncopy = null;
                        document.body.oncontextmenu = null;
                        document.body.onselectstart = null;
                    }
                    return 'Allow Copy & Right-Click: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_video_controller",
            name = "Video Speed & PIP Controller",
            version = "2.1.0",
            description = "Menambahkan panel mengambang mini pada video HTML5 untuk mengatur kecepatan (0.5x - 3x) dan lompat 10 detik.",
            author = "Media Tools",
            iconName = "Speed",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var existing = document.getElementById('__ext_vid_ctrl_panel');
                    if (existing) { existing.remove(); return 'Panel Video Controller ditutup'; }
                    var v = document.querySelector('video');
                    if (!v) return 'Tidak ada video HTML5 di halaman ini';
                    var bar = document.createElement('div');
                    bar.id = '__ext_vid_ctrl_panel';
                    bar.style.cssText = 'position:fixed;bottom:76px;right:14px;z-index:2147483647;background:rgba(20,22,26,0.92);color:#fff;padding:6px 10px;border-radius:999px;display:flex;align-items:center;gap:8px;font-family:sans-serif;font-size:12px;box-shadow:0 4px 16px rgba(0,0,0,0.45);border:1px solid rgba(255,255,255,0.18);';
                    function mkBtn(txt, fn) {
                        var b = document.createElement('button');
                        b.textContent = txt;
                        b.style.cssText = 'background:#2b2f36;color:#8ab4f8;border:none;border-radius:99px;padding:4px 9px;font-size:12px;font-weight:bold;cursor:pointer;';
                        b.onclick = fn;
                        return b;
                    }
                    var lbl = document.createElement('span');
                    lbl.textContent = (v.playbackRate || 1).toFixed(2) + 'x';
                    lbl.style.cssText = 'font-weight:bold;min-width:36px;text-align:center;color:#fff;';
                    bar.appendChild(mkBtn('-0.25x', function() { v.playbackRate = Math.max(0.25, (v.playbackRate || 1) - 0.25); lbl.textContent = v.playbackRate.toFixed(2) + 'x'; }));
                    bar.appendChild(lbl);
                    bar.appendChild(mkBtn('+0.25x', function() { v.playbackRate = Math.min(4.0, (v.playbackRate || 1) + 0.25); lbl.textContent = v.playbackRate.toFixed(2) + 'x'; }));
                    bar.appendChild(mkBtn('2x', function() { v.playbackRate = v.playbackRate === 2 ? 1 : 2; lbl.textContent = v.playbackRate.toFixed(2) + 'x'; }));
                    bar.appendChild(mkBtn('✕', function() { bar.remove(); }));
                    document.body.appendChild(bar);
                    return 'Panel Video Speed ditampilkan';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_cookie_autodismiss",
            name = "I Don't Care About Cookies",
            version = "1.5.4",
            description = "Menyembunyikan spanduk persetujuan cookie (GDPR/CCPA) dan pop-up langganan newsletter yang menutupi layar.",
            author = "Privacy Community",
            iconName = "Cookie",
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            cssCode = """
                #onetrust-consent-sdk, .onetrust-pc-dark-filter,
                #CybotCookiebotDialog, #cookie-law-info-bar,
                div[id*="cookie-banner" i], div[class*="cookie-banner" i],
                div[id*="cookie-consent" i], div[class*="cookie-consent" i],
                div[class*="gdpr-banner" i] {
                    display: none !important;
                }
            """.trimIndent(),
            jsCode = """
                (function() {
                    if (document.body) {
                        document.body.style.setProperty('overflow', 'auto', 'important');
                    }
                    return 'Cookie Banner Blocker: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_volume_booster",
            name = "Volume Booster 200% (Audio Gain)",
            version = "1.3.0",
            description = "Meningkatkan volume suara video dan audio hingga 200% menggunakan Web Audio API GainNode.",
            author = "Audio Labs",
            iconName = "VolumeUp",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var media = document.querySelectorAll('video, audio');
                    if (!media.length) return 'Tidak ada video/audio di halaman ini';
                    window.__extAudioBoostLevel = window.__extAudioBoostLevel === 2.0 ? 1.0 : 2.0;
                    var gainVal = window.__extAudioBoostLevel;
                    media.forEach(function(el) {
                        try {
                            if (!el.__extGainNode) {
                                var AudioCtx = window.AudioContext || window.webkitAudioContext;
                                var ctx = new AudioCtx();
                                var src = ctx.createMediaElementSource(el);
                                var gain = ctx.createGain();
                                src.connect(gain);
                                gain.connect(ctx.destination);
                                el.__extGainNode = gain;
                            }
                            el.__extGainNode.gain.value = gainVal;
                        } catch (e) {}
                    });
                    return 'Volume Booster: ' + Math.round(gainVal * 100) + '%';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_media_downloader",
            name = "Image & Media Extractor",
            version = "1.9.1",
            description = "Menampilkan galeri seluruh gambar beresolusi tinggi dan tautan media di halaman saat ini untuk diunduh cepat.",
            author = "Media Tools",
            iconName = "Collections",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var old = document.getElementById('__ext_img_extractor_modal');
                    if (old) { old.remove(); return 'Galeri Media ditutup'; }
                    var urls = [];
                    document.querySelectorAll('img').forEach(function(img) {
                        var s = img.currentSrc || img.src;
                        if (s && s.startsWith('http') && (img.naturalWidth > 64 || img.width > 64) && urls.indexOf(s) === -1) {
                            urls.push(s);
                        }
                    });
                    if (!urls.length) return 'Tidak ditemukan gambar utama di halaman ini';
                    var wrap = document.createElement('div');
                    wrap.id = '__ext_img_extractor_modal';
                    wrap.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:rgba(15,17,21,0.96);color:#fff;overflow-y:auto;padding:18px;font-family:sans-serif;';
                    var top = document.createElement('div');
                    top.style.cssText = 'display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;';
                    top.innerHTML = '<b style="font-size:16px">Ekstraktor Gambar (' + urls.length + ')</b>';
                    var cls = document.createElement('button');
                    cls.textContent = 'Tutup ✕';
                    cls.style.cssText = 'padding:7px 14px;background:#8ab4f8;color:#111;border:none;border-radius:99px;font-weight:bold;';
                    cls.onclick = function() { wrap.remove(); };
                    top.appendChild(cls);
                    wrap.appendChild(top);
                    var grid = document.createElement('div');
                    grid.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:10px;';
                    urls.forEach(function(u) {
                        var card = document.createElement('a');
                        card.href = u;
                        card.target = '_blank';
                        card.style.cssText = 'display:block;background:#20232a;padding:6px;border-radius:10px;text-align:center;color:#8ab4f8;text-decoration:none;font-size:11px;';
                        card.innerHTML = '<img src="' + u + '" style="width:100%;height:100px;object-fit:contain;border-radius:6px;margin-bottom:4px;"/>Buka Ukuran Penuh';
                        grid.appendChild(card);
                    });
                    wrap.appendChild(grid);
                    document.body.appendChild(wrap);
                    return 'Ditemukan ' + urls.length + ' gambar';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_clean_reader",
            name = "Clean Reader View",
            version = "1.4.0",
            description = "Mengubah artikel berita yang penuh sidebar menjadi tampilan bacaan bersih yang nyaman di mata.",
            author = "Reader Labs",
            iconName = "MenuBook",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var ex = document.getElementById('__ext_clean_reader_overlay');
                    if (ex) { ex.remove(); return 'Keluar dari Clean Reader View'; }
                    var target = document.querySelector('article, main, [role="main"]') || document.body;
                    var modal = document.createElement('div');
                    modal.id = '__ext_clean_reader_overlay';
                    modal.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#181a1f;color:#e8eaed;overflow-y:auto;padding:24px 18px 80px;font-family:Georgia,serif;line-height:1.75;font-size:18px;';
                    var inner = document.createElement('div');
                    inner.style.cssText = 'max-width:680px;margin:0 auto;';
                    var closeBtn = document.createElement('button');
                    closeBtn.textContent = '✕ Tutup Mode Baca';
                    closeBtn.style.cssText = 'position:fixed;top:14px;right:14px;padding:8px 14px;background:#8ab4f8;color:#111;border:none;border-radius:99px;font-family:sans-serif;font-weight:bold;font-size:13px;';
                    closeBtn.onclick = function() { modal.remove(); };
                    inner.innerHTML = '<h1 style="font-family:sans-serif;font-size:24px;line-height:1.35;margin-bottom:16px;">' + document.title + '</h1>' + target.innerHTML;
                    inner.querySelectorAll('script,iframe,nav,aside,footer,form').forEach(function(n) { n.remove(); });
                    modal.appendChild(closeBtn);
                    modal.appendChild(inner);
                    document.body.appendChild(modal);
                    return 'Clean Reader View Aktif';
                })();
            """.trimIndent()
        )
    )

    fun loadExtensions(context: Context): List<BrowserExtension> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seeded = prefs.getBoolean(KEY_SEEDED_DEFAULTS, false)
        val raw = prefs.getString(KEY_EXTENSIONS_JSON, null)
        if (!seeded || raw.isNullOrBlank()) {
            saveExtensions(context, BUILT_IN_EXTENSIONS)
            prefs.edit().putBoolean(KEY_SEEDED_DEFAULTS, true).apply()
            return BUILT_IN_EXTENSIONS
        }
        val parsed = parseExtensionsJson(raw)
        if (parsed.isEmpty()) return BUILT_IN_EXTENSIONS

        // Ensure any new built-in extensions are present
        val existingIds = parsed.map { it.id }.toSet()
        val missingBuiltIns = BUILT_IN_EXTENSIONS.filter { it.id !in existingIds }
        return if (missingBuiltIns.isNotEmpty()) {
            val merged = parsed + missingBuiltIns
            saveExtensions(context, merged)
            merged
        } else {
            parsed
        }
    }

    fun saveExtensions(context: Context, extensions: List<BrowserExtension>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_EXTENSIONS_JSON, serializeExtensionsJson(extensions)).apply()
    }

    fun serializeExtensionsJson(extensions: List<BrowserExtension>): String {
        val arr = JSONArray()
        extensions.forEach { ext ->
            val obj = JSONObject().apply {
                put("id", ext.id)
                put("name", ext.name)
                put("version", ext.version)
                put("description", ext.description)
                put("author", ext.author)
                put("iconName", ext.iconName)
                put("jsCode", ext.jsCode)
                put("cssCode", ext.cssCode)
                put("matches", JSONArray(ext.matches))
                put("runAt", ext.runAt.name)
                put("isEnabled", ext.isEnabled)
                put("isPinnedToToolbar", ext.isPinnedToToolbar)
                put("sourceType", ext.sourceType.name)
                put("sourceUrl", ext.sourceUrl)
            }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    fun parseExtensionsJson(jsonStr: String): List<BrowserExtension> {
        return try {
            val trimmed = jsonStr.trim()
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                when {
                    root.has("extensions") -> root.getJSONArray("extensions")
                    root.has("manifest_version") || root.has("content_scripts") -> {
                        // Single Chrome WebExtension manifest.json!
                        return listOfNotNull(parseWebExtensionManifestJson(trimmed))
                    }
                    root.has("name") && (root.has("jsCode") || root.has("cssCode") || root.has("code")) -> {
                        JSONArray().put(root)
                    }
                    else -> JSONArray()
                }
            }
            val result = mutableListOf<BrowserExtension>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val jsCode = obj.optString("jsCode", obj.optString("code", "")).trim()
                val cssCode = obj.optString("cssCode", "").trim()
                if (name.isEmpty() || (jsCode.isEmpty() && cssCode.isEmpty())) continue

                val matchesArr = obj.optJSONArray("matches")
                val matchesList = mutableListOf<String>()
                if (matchesArr != null) {
                    for (j in 0 until matchesArr.length()) {
                        val m = matchesArr.optString(j, "").trim()
                        if (m.isNotEmpty()) matchesList.add(m)
                    }
                }
                if (matchesList.isEmpty()) matchesList.add("*")

                val runAtStr = obj.optString("runAt", ExtensionRunAt.DOCUMENT_END.name)
                val runAt = try {
                    ExtensionRunAt.valueOf(runAtStr)
                } catch (_: Exception) {
                    ExtensionRunAt.DOCUMENT_END
                }

                val srcStr = obj.optString("sourceType", ExtensionSourceType.CUSTOM_CODE.name)
                val sourceType = try {
                    ExtensionSourceType.valueOf(srcStr)
                } catch (_: Exception) {
                    ExtensionSourceType.CUSTOM_CODE
                }

                result.add(
                    BrowserExtension(
                        id = obj.optString("id", UUID.randomUUID().toString()).ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        version = obj.optString("version", "1.0.0").ifBlank { "1.0.0" },
                        description = obj.optString("description", "Ekstensi kustom"),
                        author = obj.optString("author", "External"),
                        iconName = obj.optString("iconName", "Extension"),
                        jsCode = jsCode,
                        cssCode = cssCode,
                        matches = matchesList,
                        runAt = runAt,
                        isEnabled = obj.optBoolean("isEnabled", true),
                        isPinnedToToolbar = obj.optBoolean("isPinnedToToolbar", true),
                        sourceType = sourceType,
                        sourceUrl = obj.optString("sourceUrl", "")
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse extensions JSON: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses a Tampermonkey / Greasemonkey `.user.js` or standard `.js` file into a BrowserExtension.
     */
    fun parseUserScriptOrJs(rawContent: String, fallbackFileName: String = "Custom Extension"): BrowserExtension? {
        val code = rawContent.trim()
        if (code.isBlank()) return null

        var name = fallbackFileName
            .removeSuffix(".user.js")
            .removeSuffix(".js")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Custom Script Extension" }
        var version = "1.0.0"
        var description = "Diimpor dari file skrip eksternal (.user.js / .js)"
        var author = "Userscript Author"
        val matches = mutableListOf<String>()
        var runAt = ExtensionRunAt.DOCUMENT_END

        // Parse ==UserScript== header block if present
        val headerRegex = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""")
        val headerMatch = headerRegex.find(code)
        if (headerMatch != null) {
            val headerBlock = headerMatch.groupValues[1]
            headerBlock.lines().forEach { line ->
                val metaMatch = Regex("""^//\s*@(\S+)\s+(.+)$""").find(line.trim())
                if (metaMatch != null) {
                    val key = metaMatch.groupValues[1].lowercase(Locale.US)
                    val value = metaMatch.groupValues[2].trim()
                    when (key) {
                        "name" -> if (value.isNotBlank()) name = value
                        "version" -> if (value.isNotBlank()) version = value
                        "description" -> if (value.isNotBlank()) description = value
                        "author", "namespace" -> if (value.isNotBlank() && author == "Userscript Author") author = value
                        "match", "include" -> {
                            val normalizedPattern = normalizeMatchPattern(value)
                            if (normalizedPattern.isNotBlank() && normalizedPattern !in matches) {
                                matches.add(normalizedPattern)
                            }
                        }
                        "run-at" -> {
                            runAt = when {
                                value.contains("document-start", ignoreCase = true) -> ExtensionRunAt.DOCUMENT_START
                                value.contains("context-menu", ignoreCase = true) -> ExtensionRunAt.MANUAL_ONLY
                                else -> ExtensionRunAt.DOCUMENT_END
                            }
                        }
                    }
                }
            }
        }

        if (matches.isEmpty()) matches.add("*")

        // Provide GM_* compatibility shim so external Tampermonkey scripts work smoothly
        val gmShim = """
            if (typeof window.GM_addStyle === 'undefined') {
                window.GM_addStyle = function(css) {
                    var s = document.createElement('style');
                    s.textContent = css;
                    (document.head || document.documentElement).appendChild(s);
                    return s;
                };
            }
            if (typeof window.GM_setValue === 'undefined') {
                window.GM_setValue = function(k, v) { try { localStorage.setItem('__gm_' + k, JSON.stringify(v)); } catch(e){} };
            }
            if (typeof window.GM_getValue === 'undefined') {
                window.GM_getValue = function(k, defVal) {
                    try {
                        var v = localStorage.getItem('__gm_' + k);
                        return v !== null ? JSON.parse(v) : defVal;
                    } catch(e) { return defVal; }
                };
            }
            if (typeof window.GM_xmlhttpRequest === 'undefined') {
                window.GM_xmlhttpRequest = function(details) {
                    fetch(details.url, { method: details.method || 'GET', headers: details.headers || {}, body: details.data })
                        .then(function(r) { return r.text().then(function(t) { if (details.onload) details.onload({ status: r.status, responseText: t }); }); })
                        .catch(function(e) { if (details.onerror) details.onerror(e); });
                };
            }
        """.trimIndent()

        return BrowserExtension(
            id = "ext_user_" + UUID.randomUUID().toString().take(8),
            name = name.take(45),
            version = version.take(16),
            description = description.take(180),
            author = author.take(40),
            iconName = "Terminal",
            jsCode = "$gmShim\n$code",
            cssCode = "",
            matches = matches,
            runAt = runAt,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.EXTERNAL_FILE
        )
    }

    /**
     * Parses a `.css` UserStyle file into a BrowserExtension.
     */
    fun parseCssExtension(rawCss: String, fallbackFileName: String = "Custom Style"): BrowserExtension? {
        val css = rawCss.trim()
        if (css.isBlank()) return null
        val name = fallbackFileName
            .removeSuffix(".user.css")
            .removeSuffix(".css")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Custom CSS Style" }

        return BrowserExtension(
            id = "ext_css_" + UUID.randomUUID().toString().take(8),
            name = name.take(45),
            version = "1.0.0",
            description = "UserStyle CSS kustom yang diimpor dari luar",
            author = "UserStyle",
            iconName = "Palette",
            jsCode = "",
            cssCode = css,
            matches = listOf("*"),
            runAt = ExtensionRunAt.DOCUMENT_START,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.EXTERNAL_FILE
        )
    }

    /**
     * Parses a Chrome/WebExtension `manifest.json` along with optional map of file contents from a ZIP archive.
     */
    fun parseWebExtensionManifestJson(
        manifestJsonStr: String,
        bundledFiles: Map<String, String> = emptyMap()
    ): BrowserExtension? {
        return try {
            val manifest = JSONObject(manifestJsonStr)
            val name = manifest.optString("name", "WebExtension").trim()
            val version = manifest.optString("version", "1.0.0").trim()
            val description = manifest.optString("description", "Diimpor dari paket WebExtension (manifest.json)")
            val author = manifest.optString("author", "WebExtension Developer")

            val jsBuilder = StringBuilder()
            val cssBuilder = StringBuilder()
            val matches = mutableListOf<String>()
            var runAt = ExtensionRunAt.DOCUMENT_END

            // Extract content_scripts if present
            val contentScripts = manifest.optJSONArray("content_scripts")
            if (contentScripts != null) {
                for (i in 0 until contentScripts.length()) {
                    val cs = contentScripts.optJSONObject(i) ?: continue
                    val mArr = cs.optJSONArray("matches")
                    if (mArr != null) {
                        for (j in 0 until mArr.length()) {
                            val pat = normalizeMatchPattern(mArr.optString(j, ""))
                            if (pat.isNotBlank() && pat !in matches) matches.add(pat)
                        }
                    }
                    val runAtStr = cs.optString("run_at", "document_end")
                    if (runAtStr.contains("document_start", ignoreCase = true)) {
                        runAt = ExtensionRunAt.DOCUMENT_START
                    }
                    val jsArr = cs.optJSONArray("js")
                    if (jsArr != null) {
                        for (j in 0 until jsArr.length()) {
                            val fileKey = jsArr.optString(j, "").removePrefix("./").removePrefix("/")
                            val content = findBundledFile(bundledFiles, fileKey)
                            if (!content.isNullOrBlank()) {
                                jsBuilder.append("\n/* --- ").append(fileKey).append(" --- */\n").append(content)
                            }
                        }
                    }
                    val cssArr = cs.optJSONArray("css")
                    if (cssArr != null) {
                        for (j in 0 until cssArr.length()) {
                            val fileKey = cssArr.optString(j, "").removePrefix("./").removePrefix("/")
                            val content = findBundledFile(bundledFiles, fileKey)
                            if (!content.isNullOrBlank()) {
                                cssBuilder.append("\n/* --- ").append(fileKey).append(" --- */\n").append(content)
                            }
                        }
                    }
                }
            }

            // If no content_scripts matched, gather any .js / .css files in the ZIP package
            if (jsBuilder.isEmpty() && cssBuilder.isEmpty() && bundledFiles.isNotEmpty()) {
                bundledFiles.forEach { (path, content) ->
                    when {
                        path.endsWith(".js", ignoreCase = true) -> jsBuilder.append("\n").append(content)
                        path.endsWith(".css", ignoreCase = true) -> cssBuilder.append("\n").append(content)
                    }
                }
            }

            // Or inline jsCode / cssCode in custom manifest
            if (jsBuilder.isEmpty() && manifest.has("jsCode")) {
                jsBuilder.append(manifest.optString("jsCode"))
            }
            if (cssBuilder.isEmpty() && manifest.has("cssCode")) {
                cssBuilder.append(manifest.optString("cssCode"))
            }

            val finalJs = jsBuilder.toString().trim()
            val finalCss = cssBuilder.toString().trim()
            if (finalJs.isEmpty() && finalCss.isEmpty()) return null

            if (matches.isEmpty()) matches.add("*")

            BrowserExtension(
                id = "ext_crx_" + UUID.randomUUID().toString().take(8),
                name = name.take(45),
                version = version.take(16),
                description = description.take(180),
                author = author.take(40),
                iconName = "Extension",
                jsCode = finalJs,
                cssCode = finalCss,
                matches = matches,
                runAt = runAt,
                isEnabled = true,
                isPinnedToToolbar = true,
                sourceType = ExtensionSourceType.EXTERNAL_FILE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WebExtension manifest.json: ${e.message}")
            null
        }
    }

    private fun findBundledFile(bundledFiles: Map<String, String>, targetPath: String): String? {
        val cleanTarget = targetPath.trim().removePrefix("./").removePrefix("/")
        bundledFiles[cleanTarget]?.let { return it }
        return bundledFiles.entries.firstOrNull { (k, _) ->
            k.equals(cleanTarget, ignoreCase = true) || k.endsWith("/$cleanTarget", ignoreCase = true)
        }?.value
    }

    /**
     * Imports an external file selected via Android Document Picker (`.js`, `.user.js`, `.css`, `.json`, `.zip`).
     */
    suspend fun importExtensionFromUri(context: Context, uri: Uri, displayName: String): List<BrowserExtension> =
        withContext(Dispatchers.IO) {
            val lowerName = displayName.lowercase(Locale.US)
            try {
                if (lowerName.endsWith(".zip") || lowerName.endsWith(".crx") || lowerName.endsWith(".xpi")) {
                    val filesMap = mutableMapOf<String, String>()
                    context.contentResolver.openInputStream(uri)?.use { rawStream ->
                        ZipInputStream(rawStream).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val entryName = entry.name.removePrefix("./").removePrefix("/")
                                    if (entryName.endsWith(".json", true) ||
                                        entryName.endsWith(".js", true) ||
                                        entryName.endsWith(".css", true)
                                    ) {
                                        val baos = ByteArrayOutputStream()
                                        val buffer = ByteArray(4096)
                                        var count: Int
                                        var totalRead = 0
                                        while (zis.read(buffer).also { count = it } != -1 && totalRead < 1_500_000) {
                                            baos.write(buffer, 0, count)
                                            totalRead += count
                                        }
                                        filesMap[entryName] = baos.toString("UTF-8")
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                    val manifestContent = findBundledFile(filesMap, "manifest.json")
                    if (manifestContent != null) {
                        val ext = parseWebExtensionManifestJson(manifestContent, filesMap)
                        if (ext != null) return@withContext listOf(ext)
                    }
                    // Fallback: combine any .js / .css inside the zip
                    val combinedJs = filesMap.filterKeys { it.endsWith(".js", true) }.values.joinToString("\n")
                    val combinedCss = filesMap.filterKeys { it.endsWith(".css", true) }.values.joinToString("\n")
                    if (combinedJs.isNotBlank() || combinedCss.isNotBlank()) {
                        return@withContext listOf(
                            BrowserExtension(
                                id = "ext_zip_" + UUID.randomUUID().toString().take(8),
                                name = displayName.substringBeforeLast(".").ifBlank { "ZIP Extension" },
                                description = "Diimpor dari paket arsip ekstensi ($displayName)",
                                jsCode = combinedJs,
                                cssCode = combinedCss,
                                sourceType = ExtensionSourceType.EXTERNAL_FILE,
                                isEnabled = true,
                                isPinnedToToolbar = true
                            )
                        )
                    }
                    emptyList()
                } else {
                    val textContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: return@withContext emptyList()

                    parseExtensionFromRawText(textContent, displayName, ExtensionSourceType.EXTERNAL_FILE, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing extension from URI: ${e.message}")
                emptyList()
            }
        }

    /**
     * Downloads and parses an extension from an external HTTP/HTTPS URL (`.user.js`, `.js`, `.css`, `.json`).
     */
    suspend fun importExtensionFromUrl(urlStr: String): Result<List<BrowserExtension>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = urlStr.trim()
                if (!cleanUrl.startsWith("http://", true) && !cleanUrl.startsWith("https://", true)) {
                    return@withContext Result.failure(IllegalArgumentException("URL harus diawali https:// atau http://"))
                }
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile Safari/537.36")
                }
                val status = conn.responseCode
                if (status !in 200..299) {
                    return@withContext Result.failure(IllegalStateException("Gagal mengunduh ekstensi (HTTP $status)"))
                }
                val rawText = conn.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }
                val fileName = Uri.parse(cleanUrl).lastPathSegment ?: "Remote_Extension.js"
                val parsed = parseExtensionFromRawText(
                    rawText = rawText,
                    fileName = fileName,
                    sourceType = ExtensionSourceType.EXTERNAL_URL,
                    sourceUrl = cleanUrl
                )
                if (parsed.isEmpty()) {
                    Result.failure(IllegalArgumentException("Konten URL tidak berisi kode ekstensi JS/CSS/JSON yang valid"))
                } else {
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun parseExtensionFromRawText(
        rawText: String,
        fileName: String,
        sourceType: ExtensionSourceType,
        sourceUrl: String
    ): List<BrowserExtension> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()
        val lowerName = fileName.lowercase(Locale.US)

        // 1. Check if JSON (Extension bundle or manifest.json)
        if (lowerName.endsWith(".json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val fromJson = parseExtensionsJson(trimmed)
            if (fromJson.isNotEmpty()) {
                return fromJson.map { it.copy(sourceType = sourceType, sourceUrl = sourceUrl.ifBlank { it.sourceUrl }) }
            }
        }

        // 2. Check if CSS UserStyle
        if (lowerName.endsWith(".css")) {
            val cssExt = parseCssExtension(trimmed, fileName)
            if (cssExt != null) {
                return listOf(cssExt.copy(sourceType = sourceType, sourceUrl = sourceUrl))
            }
        }

        // 3. Parse as Userscript (.user.js) or standard JavaScript (.js)
        val jsExt = parseUserScriptOrJs(trimmed, fileName)
        return if (jsExt != null) {
            listOf(jsExt.copy(sourceType = sourceType, sourceUrl = sourceUrl))
        } else {
            emptyList()
        }
    }

    private fun normalizeMatchPattern(rawPattern: String): String {
        val p = rawPattern.trim()
        if (p == "*" || p == "<all_urls>" || p == "*://*/*" || p == "http://*/*" || p == "https://*/*") {
            return "*"
        }
        return try {
            val withoutScheme = p.removePrefix("https://").removePrefix("http://").removePrefix("*://")
            val hostPart = withoutScheme.substringBefore("/").removePrefix("*.").removePrefix("www.").trim()
            if (hostPart.isBlank() || hostPart == "*") "*" else hostPart.lowercase(Locale.US)
        } catch (_: Exception) {
            "*"
        }
    }

    fun doesExtensionMatchUrl(extension: BrowserExtension, url: String?): Boolean {
        if (url.isNullOrBlank() || url == "about:blank") return false
        if (extension.matches.isEmpty() || extension.matches.contains("*")) return true
        val host = try {
            Uri.parse(url).host?.lowercase(Locale.US)?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty()) return false
        return extension.matches.any { pattern ->
            val clean = pattern.lowercase(Locale.US).trim().removePrefix("*.").removePrefix("www.")
            clean == "*" || host == clean || host.endsWith(".$clean") || url.lowercase(Locale.US).contains(clean)
        }
    }

    /**
     * Injects all enabled extensions matching `url` and `stage` (`DOCUMENT_START` or `DOCUMENT_END`).
     */
    fun injectMatchingExtensions(
        webView: WebView?,
        url: String?,
        extensions: List<BrowserExtension>,
        stage: ExtensionRunAt
    ) {
        if (webView == null || url.isNullOrBlank() || url == "about:blank") return
        extensions.forEach { ext ->
            if (ext.isEnabled && ext.runAt == stage && doesExtensionMatchUrl(ext, url)) {
                executeExtensionInWebView(webView, ext, isManualTrigger = false, onResult = null)
            }
        }
    }

    /**
     * Executes a single BrowserExtension (injecting its CSS and/or JS) into the active WebView.
     */
    fun executeExtensionInWebView(
        webView: WebView?,
        extension: BrowserExtension,
        isManualTrigger: Boolean = true,
        onResult: ((String) -> Unit)? = null
    ) {
        if (webView == null) {
            onResult?.invoke("Buka halaman web terlebih dahulu untuk menjalankan ekstensi")
            return
        }

        try {
            // 1. Inject CSS if present
            if (extension.cssCode.isNotBlank()) {
                val encodedCss = Base64.encodeToString(
                    extension.cssCode.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val styleId = "__ext_css_${extension.id.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
                val cssInjector = """
                    (function() {
                        try {
                            var id = '$styleId';
                            var existing = document.getElementById(id);
                            if (existing && $isManualTrigger && ${extension.jsCode.isBlank()}) {
                                existing.remove();
                                return 'Gaya CSS ' + ${JSONObject.quote(extension.name)} + ' dinonaktifkan';
                            }
                            if (!existing) {
                                var st = document.createElement('style');
                                st.id = id;
                                st.textContent = decodeURIComponent(escape(window.atob('$encodedCss')));
                                (document.head || document.documentElement).appendChild(st);
                            }
                            return 'Gaya CSS ' + ${JSONObject.quote(extension.name)} + ' diterapkan';
                        } catch (e) {
                            return 'CSS Error: ' + e.message;
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(cssInjector) { res ->
                    if (extension.jsCode.isBlank()) {
                        val cleaned = res?.trim()?.removeSurrounding("\"") ?: "Aktif"
                        onResult?.invoke(cleaned)
                    }
                }
            }

            // 2. Inject JS if present
            if (extension.jsCode.isNotBlank()) {
                val encodedJs = Base64.encodeToString(
                    extension.jsCode.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val jsWrapper = """
                    (function() {
                        try {
                            window.__extManualTrigger = $isManualTrigger;
                            var src = decodeURIComponent(escape(window.atob('$encodedJs')));
                            var fn = new Function(src);
                            var out = fn();
                            return out !== undefined && out !== null ? String(out) : 'Ekstensi dijalankan';
                        } catch (err) {
                            console.error('Extension error [${extension.name}]:', err);
                            return 'Error: ' + (err && err.message ? err.message : String(err));
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsWrapper) { rawResult ->
                    val cleaned = rawResult
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\n", " ")
                        ?: "Ekstensi dijalankan"
                    onResult?.invoke(cleaned)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute extension ${extension.name}: ${e.message}")
            onResult?.invoke("Gagal menjalankan ekstensi: ${e.message}")
        }
    }
}
