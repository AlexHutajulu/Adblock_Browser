package com.example.manager

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class JsAutoRunMode {
    MANUAL_ONLY,
    ALL_SITES,
    SPECIFIC_DOMAINS
}

enum class ShortcutBarPosition {
    TOP,
    BOTTOM
}

enum class ScreenOrientationMode {
    AUTO_SENSOR,
    LANDSCAPE,
    PORTRAIT
}

enum class BuiltInShortcut(val id: String, val label: String) {
    ROTATE_SCREEN("rotate_screen", "Putar Layar"),
    CUSTOM_JS_STUDIO("custom_js_studio", "Studio JS"),
    DESKTOP_MODE("desktop_mode", "Mode Desktop"),
    READER_MODE("reader_mode", "Mode Baca"),
    TRANSLATE_PAGE("translate_page", "Terjemahkan"),
    FIND_IN_PAGE("find_in_page", "Cari Teks"),
    SAVE_PDF("save_pdf", "Simpan PDF"),
    INCOGNITO_TAB("incognito_tab", "Samaran"),
    SCROLL_TOP_BOTTOM("scroll_jump", "Atas/Bawah"),
    SECURITY_SHIELD("security_shield", "Perisai Aman")
}

data class CustomToolbarScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val iconName: String = "Code",
    val description: String = "",
    val jsCode: String,
    val autoRunMode: JsAutoRunMode = JsAutoRunMode.MANUAL_ONLY,
    val targetDomains: Set<String> = emptySet(),
    val isEnabled: Boolean = true,
    val isBuiltInTemplate: Boolean = false
)

/**
 * Manages Custom JavaScript Toolbar buttons, built-in JS templates, safe IIFE execution,
 * per-site auto-run matching, and JSON import/export.
 */
object CustomJsToolbarManager {

    private const val TAG = "CustomJsToolbarManager"
    private const val PREFS_NAME = "custom_js_toolbar_prefs"
    private const val KEY_SCRIPTS_JSON = "custom_js_scripts_v1"
    private const val KEY_SEEDED_DEFAULT = "seeded_default_templates_v1"
    private const val KEY_SHORTCUT_BAR_VISIBLE = "shortcut_bar_visible"
    private const val KEY_SHORTCUT_BAR_POSITION = "shortcut_bar_position"
    private const val KEY_ORIENTATION_MODE = "screen_orientation_mode"
    private const val KEY_ENABLED_SHORTCUTS = "enabled_builtin_shortcuts"

    val AVAILABLE_ICONS = listOf(
        "Code",
        "DarkMode",
        "Speed",
        "LockOpen",
        "AutoAwesome",
        "Visibility",
        "PlayArrow",
        "CleaningServices",
        "Collections",
        "MenuBook",
        "Bolt",
        "Terminal"
    )

    val TEMPLATE_GALLERY: List<CustomToolbarScript> = listOf(
        CustomToolbarScript(
            id = "tpl_smart_dark_mode",
            name = "Dark Mode",
            iconName = "DarkMode",
            description = "Mengaktifkan atau mematikan Dark Mode cerdas pada halaman web tanpa merusak warna gambar & video.",
            jsCode = """
                (function() {
                    var id = '__chrome_custom_dark_mode__';
                    var existing = document.getElementById(id);
                    if (existing) {
                        existing.remove();
                        return 'Dark Mode Dimatikan';
                    }
                    var style = document.createElement('style');
                    style.id = id;
                    style.textContent = 'html { filter: invert(0.92) hue-rotate(180deg) !important; background: #121212 !important; } ' +
                        'img, video, picture, canvas, iframe, [style*="background-image"] { filter: invert(1) hue-rotate(180deg) !important; }';
                    (document.head || document.documentElement).appendChild(style);
                    return 'Dark Mode Diaktifkan';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = true,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_video_speed",
            name = "Video Speed",
            iconName = "Speed",
            description = "Mengubah kecepatan pemutaran semua video di halaman (1x -> 1.5x -> 2x -> 2.5x -> 1x).",
            jsCode = """
                (function() {
                    var videos = document.querySelectorAll('video');
                    if (!videos.length) return 'Tidak ada video ditemukan di halaman ini';
                    var rates = [1.0, 1.25, 1.5, 2.0, 2.5];
                    var current = videos[0].playbackRate || 1.0;
                    var nextIdx = (rates.indexOf(current) + 1) % rates.length;
                    var nextRate = rates[nextIdx] || 1.5;
                    videos.forEach(function(v) { v.playbackRate = nextRate; });
                    var badgeId = '__chrome_speed_toast__';
                    var old = document.getElementById(badgeId);
                    if (old) old.remove();
                    var div = document.createElement('div');
                    div.id = badgeId;
                    div.textContent = 'Kecepatan Video: ' + nextRate + 'x';
                    div.style.cssText = 'position:fixed;top:18px;right:18px;z-index:2147483647;background:rgba(11,87,208,0.92);color:#fff;padding:8px 14px;border-radius:20px;font:600 13px sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.3);pointer-events:none;';
                    document.body.appendChild(div);
                    setTimeout(function() { if (div.parentNode) div.remove(); }, 1800);
                    return 'Kecepatan Video: ' + nextRate + 'x';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = true,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_unlock_copy",
            name = "Buka Copy",
            iconName = "LockOpen",
            description = "Membuka kunci seleksi teks, salin-tempel (Copy/Paste), dan klik kanan yang diblokir situs.",
            jsCode = """
                (function() {
                    var styleId = '__chrome_unlock_copy__';
                    if (!document.getElementById(styleId)) {
                        var s = document.createElement('style');
                        s.id = styleId;
                        s.textContent = '*, *::before, *::after { -webkit-user-select: text !important; user-select: text !important; -webkit-touch-callout: default !important; }';
                        (document.head || document.documentElement).appendChild(s);
                    }
                    var events = ['copy', 'cut', 'paste', 'selectstart', 'contextmenu', 'dragstart', 'mousedown'];
                    events.forEach(function(ev) {
                        window.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                        document.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                    });
                    document.oncopy = document.oncut = document.onpaste = document.onselectstart = document.oncontextmenu = null;
                    if (document.body) {
                        document.body.oncopy = document.body.onselectstart = document.body.oncontextmenu = null;
                    }
                    return 'Proteksi Copy-Paste & Klik Kanan dibuka';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = true,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_remove_overlays",
            name = "Hapus Popup",
            iconName = "CleaningServices",
            description = "Menghilangkan banner mengambang (sticky/fixed overlay) yang menutupi artikel dan membuka kunci scroll.",
            jsCode = """
                (function() {
                    var removed = 0;
                    document.querySelectorAll('body *').forEach(function(el) {
                        var tag = (el.tagName || '').toLowerCase();
                        if (tag === 'video' || tag === 'nav' || tag === 'header' || el.querySelector('video')) return;
                        var st = window.getComputedStyle(el);
                        if ((st.position === 'fixed' || st.position === 'sticky') && parseInt(st.zIndex || '0', 10) > 20) {
                            el.style.setProperty('display', 'none', 'important');
                            removed++;
                        }
                    });
                    document.documentElement.style.setProperty('overflow', 'auto', 'important');
                    if (document.body) document.body.style.setProperty('overflow', 'auto', 'important');
                    return 'Dibersihkan ' + removed + ' elemen penghalang & scroll dibuka';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = true,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_auto_scroll",
            name = "Auto Scroll",
            iconName = "PlayArrow",
            description = "Menggulir halaman otomatis secara halus untuk membaca artikel atau komik. Klik lagi untuk berhenti.",
            jsCode = """
                (function() {
                    if (window.__chromeAutoScrollTimer) {
                        clearInterval(window.__chromeAutoScrollTimer);
                        window.__chromeAutoScrollTimer = null;
                        return 'Auto-Scroll Dihentikan';
                    }
                    window.__chromeAutoScrollTimer = setInterval(function() {
                        window.scrollBy({ top: 2, left: 0, behavior: 'auto' });
                        if ((window.innerHeight + window.scrollY) >= document.body.scrollHeight - 2) {
                            clearInterval(window.__chromeAutoScrollTimer);
                            window.__chromeAutoScrollTimer = null;
                        }
                    }, 24);
                    return 'Auto-Scroll Dimulai (Klik lagi untuk berhenti)';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = false,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_reader_view",
            name = "Mode Baca",
            iconName = "MenuBook",
            description = "Mengubah artikel menjadi tampilan fokus baca dengan tipografi nyaman dan bebas distraksi.",
            jsCode = """
                (function() {
                    var overlayId = '__chrome_reader_mode_overlay__';
                    var existing = document.getElementById(overlayId);
                    if (existing) {
                        existing.remove();
                        document.body.style.overflow = '';
                        return 'Keluar dari Mode Baca';
                    }
                    var article = document.querySelector('article, [role="main"], main, .post-content, .article-body, .entry-content') || document.body;
                    var title = document.title || 'Mode Baca';
                    var paragraphs = Array.from(article.querySelectorAll('h1, h2, h3, p, blockquote, li'))
                        .filter(function(el) { return (el.innerText || '').trim().length > 25; })
                        .map(function(el) {
                            var tag = el.tagName.toLowerCase();
                            var text = el.innerText.trim().replace(/</g, '&lt;').replace(/>/g, '&gt;');
                            if (tag === 'h1' || tag === 'h2' || tag === 'h3') return '<h2 style="margin:24px 0 10px;line-height:1.35;color:#e3e3e3;">' + text + '</h2>';
                            return '<p style="margin:0 0 16px;line-height:1.75;font-size:18px;color:#d4d4d4;">' + text + '</p>';
                        }).join('');
                    if (!paragraphs) {
                        paragraphs = '<p style="line-height:1.75;font-size:18px;color:#d4d4d4;white-space:pre-wrap;">' + (article.innerText || '').replace(/</g, '&lt;') + '</p>';
                    }
                    var modal = document.createElement('div');
                    modal.id = overlayId;
                    modal.style.cssText = 'position:fixed;inset:0;z-index:2147483646;background:#18191b;color:#e3e3e3;overflow-y:auto;padding:24px 20px 64px;font-family:system-ui,-apple-system,sans-serif;';
                    modal.innerHTML = '<div style="max-width:680px;margin:0 auto;">' +
                        '<div style="display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #333;padding-bottom:14px;margin-bottom:20px;">' +
                        '<span style="font-size:13px;color:#a8c7fa;font-weight:600;">MODE BACA CHROME</span>' +
                        '<button onclick="document.getElementById(\'' + overlayId + '\').remove();document.body.style.overflow=\'\';" style="background:#2d2e31;color:#fff;border:none;padding:8px 16px;border-radius:18px;font-weight:600;cursor:pointer;">Tutup</button>' +
                        '</div>' +
                        '<h1 style="font-size:24px;line-height:1.3;margin-bottom:20px;color:#fff;">' + title.replace(/</g, '&lt;') + '</h1>' +
                        paragraphs +
                        '</div>';
                    document.body.appendChild(modal);
                    document.body.style.overflow = 'hidden';
                    return 'Mode Baca Aktif';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = false,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_image_extractor",
            name = "Galeri Gambar",
            iconName = "Collections",
            description = "Mengumpulkan seluruh gambar dari halaman aktif ke dalam tampilan galeri bersih agar mudah diunduh.",
            jsCode = """
                (function() {
                    var id = '__chrome_img_extractor__';
                    var existing = document.getElementById(id);
                    if (existing) { existing.remove(); return 'Galeri Gambar Ditutup'; }
                    var urls = [];
                    document.querySelectorAll('img').forEach(function(img) {
                        var src = img.currentSrc || img.src || img.getAttribute('data-src');
                        if (src && src.startsWith('http') && urls.indexOf(src) === -1 && (img.naturalWidth > 80 || img.width > 80)) {
                            urls.push(src);
                        }
                    });
                    if (!urls.length) return 'Tidak ada gambar berukuran cukup besar ditemukan';
                    var box = document.createElement('div');
                    box.id = id;
                    box.style.cssText = 'position:fixed;inset:0;z-index:2147483646;background:rgba(18,18,18,0.97);color:#fff;overflow-y:auto;padding:20px;font-family:sans-serif;';
                    var itemsHtml = urls.map(function(u) {
                        return '<a href="' + u + '" target="_blank" style="display:block;background:#262626;border-radius:12px;overflow:hidden;padding:8px;text-decoration:none;color:#a8c7fa;font-size:11px;">' +
                            '<img src="' + u + '" style="width:100%;height:150px;object-fit:contain;display:block;margin-bottom:6px;background:#111;border-radius:8px;"/>' +
                            '<div style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">Buka Ukuran Penuh</div></a>';
                    }).join('');
                    box.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">' +
                        '<strong style="font-size:16px;">Ditemukan ' + urls.length + ' Gambar</strong>' +
                        '<button onclick="document.getElementById(\'' + id + '\').remove()" style="background:#0b57d0;color:#fff;border:none;padding:8px 16px;border-radius:18px;font-weight:600;">Tutup</button>' +
                        '</div><div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:12px;">' + itemsHtml + '</div>';
                    document.body.appendChild(box);
                    return 'Menampilkan ' + urls.length + ' gambar';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = false,
            isBuiltInTemplate = true
        ),
        CustomToolbarScript(
            id = "tpl_reveal_passwords",
            name = "Lihat Sandi",
            iconName = "Visibility",
            description = "Mengubah kolom input password (***) menjadi teks biasa agar Anda bisa mengecek ketikan sandi.",
            jsCode = """
                (function() {
                    var inputs = document.querySelectorAll('input[type="password"], input[data-chrome-pw-revealed="1"]');
                    if (!inputs.length) return 'Tidak ada kolom password di halaman ini';
                    var revealed = 0;
                    inputs.forEach(function(inp) {
                        if (inp.type === 'password') {
                            inp.type = 'text';
                            inp.setAttribute('data-chrome-pw-revealed', '1');
                            revealed++;
                        } else {
                            inp.type = 'password';
                        }
                    });
                    return revealed > 0 ? 'Password ditampilkan (' + revealed + ' kolom)' : 'Password disembunyikan kembali';
                })();
            """.trimIndent(),
            autoRunMode = JsAutoRunMode.MANUAL_ONLY,
            isEnabled = false,
            isBuiltInTemplate = true
        )
    )

    fun loadScripts(context: Context): List<CustomToolbarScript> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSeeded = prefs.getBoolean(KEY_SEEDED_DEFAULT, false)
        val rawJson = prefs.getString(KEY_SCRIPTS_JSON, null)

        if (!isSeeded || rawJson.isNullOrBlank()) {
            saveScripts(context, TEMPLATE_GALLERY)
            prefs.edit().putBoolean(KEY_SEEDED_DEFAULT, true).apply()
            return TEMPLATE_GALLERY
        }

        return parseScriptsFromJson(rawJson).ifEmpty { TEMPLATE_GALLERY }
    }

    fun saveScripts(context: Context, scripts: List<CustomToolbarScript>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = serializeScriptsToJson(scripts)
        prefs.edit()
            .putString(KEY_SCRIPTS_JSON, json)
            .putBoolean(KEY_SEEDED_DEFAULT, true)
            .apply()
    }

    fun serializeScriptsToJson(scripts: List<CustomToolbarScript>): String {
        val array = JSONArray()
        scripts.forEach { script ->
            val obj = JSONObject().apply {
                put("id", script.id)
                put("name", script.name)
                put("iconName", script.iconName)
                put("description", script.description)
                put("jsCode", script.jsCode)
                put("autoRunMode", script.autoRunMode.name)
                put("targetDomains", JSONArray(script.targetDomains.toList()))
                put("isEnabled", script.isEnabled)
                put("isBuiltInTemplate", script.isBuiltInTemplate)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun parseScriptsFromJson(jsonStr: String): List<CustomToolbarScript> {
        return try {
            val array = JSONArray(jsonStr.trim())
            val list = mutableListOf<CustomToolbarScript>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val jsCode = obj.optString("jsCode", "").trim()
                if (name.isEmpty() || jsCode.isEmpty()) continue

                val domainsArr = obj.optJSONArray("targetDomains")
                val domains = mutableSetOf<String>()
                if (domainsArr != null) {
                    for (j in 0 until domainsArr.length()) {
                        val d = domainsArr.optString(j, "").trim().lowercase().removePrefix("www.")
                        if (d.isNotEmpty()) domains.add(d)
                    }
                }
                val modeStr = obj.optString("autoRunMode", JsAutoRunMode.MANUAL_ONLY.name)
                val mode = try {
                    JsAutoRunMode.valueOf(modeStr)
                } catch (_: Exception) {
                    JsAutoRunMode.MANUAL_ONLY
                }

                list.add(
                    CustomToolbarScript(
                        id = obj.optString("id", UUID.randomUUID().toString()).ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        iconName = obj.optString("iconName", "Code").ifBlank { "Code" },
                        description = obj.optString("description", ""),
                        jsCode = jsCode,
                        autoRunMode = mode,
                        targetDomains = domains,
                        isEnabled = obj.optBoolean("isEnabled", true),
                        isBuiltInTemplate = obj.optBoolean("isBuiltInTemplate", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom JS JSON: ${e.message}")
            emptyList()
        }
    }

    /**
     * Executes a CustomToolbarScript safely inside an isolated IIFE using Base64 decoding
     * so quotes, newlines, or special characters in user scripts never cause syntax injection errors.
     */
    fun executeScriptInWebView(
        webView: WebView?,
        script: CustomToolbarScript,
        onResult: ((String) -> Unit)? = null
    ) {
        if (webView == null) {
            onResult?.invoke("Buka halaman web terlebih dahulu untuk menjalankan '${script.name}'")
            return
        }
        executeRawJsInWebView(webView, script.jsCode, script.name, onResult)
    }

    fun executeRawJsInWebView(
        webView: WebView?,
        rawJsCode: String,
        scriptLabel: String = "Script",
        onResult: ((String) -> Unit)? = null
    ) {
        if (webView == null || rawJsCode.isBlank()) {
            onResult?.invoke("Kode JavaScript kosong atau halaman belum siap")
            return
        }

        val encoded = Base64.encodeToString(rawJsCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val safeWrapper = """
            (function() {
                try {
                    var decoded = decodeURIComponent(escape(window.atob('$encoded')));
                    var fn = new Function('return (function(){ ' + decoded + ' })();');
                    var res = fn();
                    return res !== undefined && res !== null ? String(res) : 'OK';
                } catch (err) {
                    console.error('[CustomJS:$scriptLabel]', err);
                    return 'Error: ' + (err && err.message ? err.message : String(err));
                }
            })();
        """.trimIndent()

        try {
            webView.evaluateJavascript(safeWrapper) { rawEvalResult ->
                val cleaned = rawEvalResult
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\n", " ")
                    ?: "OK"
                onResult?.invoke(cleaned)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to evaluate custom JS '$scriptLabel': ${e.message}")
            onResult?.invoke("Gagal menjalankan script: ${e.message}")
        }
    }

    /**
     * Automatically runs matching scripts when a page finishes loading.
     */
    fun runAutoScriptsForUrl(
        webView: WebView?,
        url: String?,
        scripts: List<CustomToolbarScript>
    ): Int {
        if (webView == null || url.isNullOrBlank() || url == "about:blank") return 0
        val host = try {
            Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty()) return 0

        var executedCount = 0
        scripts.forEach { script ->
            val shouldRun = when (script.autoRunMode) {
                JsAutoRunMode.MANUAL_ONLY -> false
                JsAutoRunMode.ALL_SITES -> true
                JsAutoRunMode.SPECIFIC_DOMAINS -> {
                    script.targetDomains.any { target ->
                        val cleanTarget = target.trim().lowercase().removePrefix("www.")
                        cleanTarget.isNotEmpty() && (host == cleanTarget || host.endsWith(".$cleanTarget"))
                    }
                }
            }
            if (shouldRun) {
                executeScriptInWebView(webView, script, onResult = null)
                executedCount++
            }
        }
        return executedCount
    }

    fun getShortcutBarVisible(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHORTCUT_BAR_VISIBLE, true)
    }

    fun setShortcutBarVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHORTCUT_BAR_VISIBLE, visible).apply()
    }

    fun getShortcutBarPosition(context: Context): ShortcutBarPosition {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("shortcut_bar_position_v2", ShortcutBarPosition.BOTTOM.name)
        return try {
            ShortcutBarPosition.valueOf(raw ?: ShortcutBarPosition.BOTTOM.name)
        } catch (_: Exception) {
            ShortcutBarPosition.BOTTOM
        }
    }

    fun setShortcutBarPosition(context: Context, position: ShortcutBarPosition) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("shortcut_bar_position_v2", position.name).apply()
    }

    fun getScreenOrientationMode(context: Context): ScreenOrientationMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ORIENTATION_MODE, ScreenOrientationMode.AUTO_SENSOR.name)
        return try {
            ScreenOrientationMode.valueOf(raw ?: ScreenOrientationMode.AUTO_SENSOR.name)
        } catch (_: Exception) {
            ScreenOrientationMode.AUTO_SENSOR
        }
    }

    fun setScreenOrientationMode(context: Context, mode: ScreenOrientationMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORIENTATION_MODE, mode.name).apply()
    }

    fun getEnabledBuiltInShortcuts(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultSet = BuiltInShortcut.entries.map { it.id }.toSet()
        return prefs.getStringSet(KEY_ENABLED_SHORTCUTS, defaultSet) ?: defaultSet
    }

    fun setEnabledBuiltInShortcuts(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED_SHORTCUTS, ids).apply()
    }
}
