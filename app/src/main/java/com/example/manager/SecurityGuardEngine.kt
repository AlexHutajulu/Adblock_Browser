package com.example.manager

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ThreatSeverity {
    SAFE,
    HTTP_INSECURE,
    SUSPICIOUS_PHISHING,
    MALWARE_OR_SCAM
}

data class SecurityThreatAssessment(
    val url: String,
    val host: String,
    val severity: ThreatSeverity,
    val title: String = "",
    val reason: String = "",
    val recommendation: String = ""
)

/**
 * Multi-layer Security Engine protecting users from:
 * 1. Unencrypted HTTP connections (HTTPS-Only automatic upgrade + warning).
 * 2. Phishing, homograph IDN spoofing (xn--), fake banking/login domains, and scam sites.
 * 3. Unauthorized Camera, Microphone, Clipboard hijacking, and reverse-tabnabbing popups.
 */
object SecurityGuardEngine {

    private const val PREFS_NAME = "chrome_security_guard_prefs"
    private const val KEY_HTTPS_ONLY = "https_only_mode"
    private const val KEY_PHISHING_SHIELD = "phishing_protection_enabled"
    private const val KEY_BLOCK_CAM_MIC = "block_camera_mic_strict"
    private const val KEY_BLOCK_CLIPBOARD = "block_clipboard_hijack"
    private const val KEY_BLOCK_HIDDEN_POPUPS = "block_hidden_popups"
    private const val KEY_TRUSTED_HOSTS = "user_trusted_hosts"

    private val bypassedHostsSession = ConcurrentHashMap.newKeySet<String>()
    private val threatsBlockedCounter = AtomicInteger(0)
    private val httpsUpgradesCounter = AtomicInteger(0)
    private val clipboardBlocksCounter = AtomicInteger(0)

    private val TRUSTED_REGISTRABLE_DOMAINS = setOf(
        "google.com", "google.co.id", "youtube.com", "gmail.com", "gstatic.com",
        "googleapis.com", "github.com", "gitlab.com", "microsoft.com", "live.com",
        "office.com", "apple.com", "icloud.com", "amazon.com", "wikipedia.org",
        "reddit.com", "facebook.com", "instagram.com", "whatsapp.com", "x.com",
        "twitter.com", "linkedin.com", "netflix.com", "spotify.com", "discord.com",
        "cloudflare.com", "openai.com", "anthropic.com", "stackoverflow.com",
        // Trusted Indonesian Banking & E-Commerce Official Domains
        "klikbca.com", "bca.co.id", "bankmandiri.co.id", "bri.co.id", "bni.co.id",
        "bsi.co.id", "cimbniaga.co.id", "permata.com", "jenius.com", "jago.com",
        "tokopedia.com", "shopee.co.id", "bukalapak.com", "blibli.com", "traveloka.com",
        "tiket.com", "gojek.com", "grab.com", "dana.id", "ovo.id", "gopay.co.id",
        "midtrans.com", "xendit.co", "doku.com", "kompas.com", "detik.com", "cnnindonesia.com"
    )

    private val KNOWN_PHISHING_AND_MALWARE_DOMAINS = setOf(
        "malware-domain.com", "fake-virus-alert.com", "pckgupdate.com",
        "secure-login-verify.xyz", "account-update-security.top",
        "verify-wallet-connect.xyz", "free-dana-kaget-claim.xyz",
        "claim-saldo-dana-gratis.site", "undian-berhadiah-resmi.xyz",
        "tarif-bank-perubahan.xyz", "konfirmasi-tarif-transaksi.site",
        "formulir-perubahan-tarif.click", "aktifkan-autodebet-resmi.top"
    )

    private val BRAND_IMPERSONATION_TARGETS = listOf(
        "klikbca" to "bca.co.id",
        "mybca" to "bca.co.id",
        "livinmandiri" to "bankmandiri.co.id",
        "brimo" to "bri.co.id",
        "wondrbni" to "bni.co.id",
        "danakaget" to "dana.id",
        "paypalsecure" to "paypal.com",
        "googleaccounts" to "google.com",
        "steamcommunnity" to "steamcommunity.com",
        "discrod" to "discord.com",
        "faceb00k" to "facebook.com"
    )

    private val HIGH_RISK_TLDS = setOf(
        ".xyz", ".top", ".click", ".loan", ".work", ".gq", ".tk", ".ml", ".cf", ".ga", ".zip", ".mov"
    )

    private val DECEPTIVE_AUTH_KEYWORDS = listOf(
        "login-", "-login", "signin-", "verify-", "verification-", "update-account",
        "secure-banking", "perubahan-tarif", "saldo-gratis", "dana-kaget", "klaim-hadiah",
        "wallet-restore", "seed-phrase"
    )

    fun getThreatsBlockedCount(): Int = threatsBlockedCounter.get()
    fun getHttpsUpgradesCount(): Int = httpsUpgradesCounter.get()
    fun getClipboardBlocksCount(): Int = clipboardBlocksCounter.get()
    fun incrementClipboardBlocked() { clipboardBlocksCounter.incrementAndGet() }

    fun isHttpsOnlyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_HTTPS_ONLY, true)

    fun setHttpsOnlyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HTTPS_ONLY, enabled).apply()
    }

    fun isPhishingProtectionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PHISHING_SHIELD, true)

    fun setPhishingProtectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PHISHING_SHIELD, enabled).apply()
    }

    fun isBlockCameraMicStrict(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK_CAM_MIC, false)

    fun setBlockCameraMicStrict(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLOCK_CAM_MIC, enabled).apply()
    }

    fun isBlockClipboardHijack(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK_CLIPBOARD, true)

    fun setBlockClipboardHijack(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLOCK_CLIPBOARD, enabled).apply()
    }

    fun isBlockHiddenPopups(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK_HIDDEN_POPUPS, true)

    fun setBlockHiddenPopups(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLOCK_HIDDEN_POPUPS, enabled).apply()
    }

    fun allowHostTemporarily(context: Context, host: String) {
        val clean = normalizeHost(host)
        if (clean.isNotEmpty()) {
            bypassedHostsSession.add(clean)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(KEY_TRUSTED_HOSTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.add(clean)
            prefs.edit().putStringSet(KEY_TRUSTED_HOSTS, current).apply()
        }
    }

    fun isHostUserTrusted(context: Context, host: String): Boolean {
        val clean = normalizeHost(host)
        if (clean.isEmpty()) return false
        if (bypassedHostsSession.contains(clean)) return true
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_TRUSTED_HOSTS, emptySet()) ?: emptySet()
        return saved.contains(clean)
    }

    /**
     * Automatically upgrades HTTP URLs to HTTPS when HTTPS-Only mode is active,
     * excluding localhost and local network addresses.
     */
    fun upgradeUrlIfHttpsOnly(url: String, httpsOnlyEnabled: Boolean): String {
        val trimmed = url.trim()
        if (!httpsOnlyEnabled || !trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        val host = extractHost(trimmed)
        if (isLocalOrPrivateAddress(host)) {
            return trimmed
        }
        httpsUpgradesCounter.incrementAndGet()
        return "https://" + trimmed.substring(7)
    }

    /**
     * Evaluates a target URL for phishing, brand spoofing, homograph attacks, malware, or insecure HTTP.
     */
    fun inspectUrlThreat(
        url: String,
        httpsOnlyEnabled: Boolean,
        phishingProtectionEnabled: Boolean,
        isUserTrustedHost: Boolean = false
    ): SecurityThreatAssessment {
        if (url.isBlank() || url == "about:blank") {
            return SecurityThreatAssessment(url, "", ThreatSeverity.SAFE)
        }

        val host = extractHost(url)
        if (host.isEmpty() || isUserTrustedHost || isOfficialTrustedDomain(host)) {
            return SecurityThreatAssessment(url, host, ThreatSeverity.SAFE)
        }

        if (phishingProtectionEnabled) {
            // 1. Known phishing & malware domain list
            if (KNOWN_PHISHING_AND_MALWARE_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                threatsBlockedCounter.incrementAndGet()
                return SecurityThreatAssessment(
                    url = url,
                    host = host,
                    severity = ThreatSeverity.MALWARE_OR_SCAM,
                    title = "Situs Berbahaya Diblokir",
                    reason = "Domain '$host' terdaftar dalam basis data situs berbahaya yang menyebarkan phishing, scam, atau malware.",
                    recommendation = "Jangan pernah memasukkan kata sandi, PIN, kode OTP, atau mengunduh aplikasi (.apk) dari situs ini."
                )
            }

            // 2. IDN Homograph / Punycode Spoofing (xn--)
            if (host.contains("xn--")) {
                threatsBlockedCounter.incrementAndGet()
                return SecurityThreatAssessment(
                    url = url,
                    host = host,
                    severity = ThreatSeverity.SUSPICIOUS_PHISHING,
                    title = "Terdeteksi Penyamaran Domain (IDN Homograph)",
                    reason = "Situs '$host' menggunakan karakter khusus (Punycode xn--) yang sering dipakai untuk meniru tampilan alamat situs resmi.",
                    recommendation = "Kembali ke halaman aman kecuali Anda benar-benar mengenali domain ini."
                )
            }

            // 3. Brand Impersonation / Typosquatting Check
            val compactHost = host.replace("-", "").replace(".", "")
            for ((spoofKeyword, officialDomain) in BRAND_IMPERSONATION_TARGETS) {
                if (compactHost.contains(spoofKeyword) && !host.endsWith(officialDomain)) {
                    threatsBlockedCounter.incrementAndGet()
                    return SecurityThreatAssessment(
                        url = url,
                        host = host,
                        severity = ThreatSeverity.SUSPICIOUS_PHISHING,
                        title = "Peringatan Situs Tiruan (Phishing)",
                        reason = "Situs '$host' terdeteksi meniru layanan resmi ($officialDomain) untuk mencuri kredensial akun atau perbankan Anda.",
                        recommendation = "Pastikan Anda hanya membuka domain resmi '$officialDomain'."
                    )
                }
            }

            // 4. High-risk TLD combined with deceptive auth/banking/giveaway keywords
            val hasHighRiskTld = HIGH_RISK_TLDS.any { host.endsWith(it) }
            val lowerUrl = url.lowercase(Locale.US)
            val matchedKeyword = DECEPTIVE_AUTH_KEYWORDS.firstOrNull { lowerUrl.contains(it) }
            if (hasHighRiskTld && matchedKeyword != null) {
                threatsBlockedCounter.incrementAndGet()
                return SecurityThreatAssessment(
                    url = url,
                    host = host,
                    severity = ThreatSeverity.SUSPICIOUS_PHISHING,
                    title = "Situs Mencurigakan Terdeteksi",
                    reason = "Situs '$host' menggunakan ekstensi domain berisiko tinggi dengan pola jebakan '$matchedKeyword'.",
                    recommendation = "Hindari mengisi formulir login, nomor kartu, atau kode OTP di halaman ini."
                )
            }
        }

        // 5. Strict HTTPS-Only warning if connection is still plain HTTP on non-local host
        if (httpsOnlyEnabled && url.startsWith("http://", ignoreCase = true) && !isLocalOrPrivateAddress(host)) {
            return SecurityThreatAssessment(
                url = url,
                host = host,
                severity = ThreatSeverity.HTTP_INSECURE,
                title = "Koneksi Tidak Aman (HTTP)",
                reason = "Situs '$host' tidak mendukung enkripsi HTTPS. Informasi yang Anda kirim atau lihat dapat disadap oleh pihak ketiga di jaringan.",
                recommendation = "Disarankan kembali ke halaman aman."
            )
        }

        return SecurityThreatAssessment(url, host, ThreatSeverity.SAFE)
    }

    /**
     * Builds the client-side DOM & API hardening script injected on page load:
     * - Protects clipboard from background reading or silent hijacking without a fresh user click.
     * - Prevents reverse-tabnabbing (`window.opener = null`).
     * - Blocks unprompted vibration spam (`navigator.vibrate`) used by fake "virus alert" scam pages.
     */
    fun buildClientSecurityScript(
        blockClipboardHijack: Boolean,
        blockHiddenPopups: Boolean
    ): String = """
        (function() {
            if (window.__ChromeSecurityGuardInjected) return;
            window.__ChromeSecurityGuardInjected = true;

            // 1. Neutralize reverse-tabnabbing
            try {
                if (window.opener) window.opener = null;
            } catch (e) {}

            // 2. Block fake virus-alert vibration spam
            try {
                if (navigator.vibrate) {
                    navigator.vibrate = function() { return false; };
                }
            } catch (e) {}

            // 3. Track genuine user activation for clipboard & popup protection
            var lastUserInteraction = 0;
            ['pointerdown', 'mousedown', 'touchend', 'keydown', 'click'].forEach(function(ev) {
                window.addEventListener(ev, function(e) {
                    if (e && e.isTrusted) lastUserInteraction = Date.now();
                }, true);
            });

            function hasRecentUserGesture() {
                return (Date.now() - lastUserInteraction) < 1500;
            }

            // 4. Clipboard Hijack & Silent Read Protection
            if ($blockClipboardHijack) {
                try {
                    if (navigator.clipboard) {
                        navigator.clipboard.readText = function() {
                            return Promise.reject(new DOMException('Clipboard read blocked by Chrome Security Shield', 'NotAllowedError'));
                        };
                        navigator.clipboard.read = function() {
                            return Promise.reject(new DOMException('Clipboard read blocked by Chrome Security Shield', 'NotAllowedError'));
                        };
                        var origWriteText = navigator.clipboard.writeText ? navigator.clipboard.writeText.bind(navigator.clipboard) : null;
                        if (origWriteText) {
                            navigator.clipboard.writeText = function(text) {
                                if (!hasRecentUserGesture()) {
                                    console.warn('[SecurityShield] Blocked silent clipboard write');
                                    return Promise.resolve();
                                }
                                return origWriteText(text);
                            };
                        }
                    }
                    var origExecCommand = document.execCommand ? document.execCommand.bind(document) : null;
                    if (origExecCommand) {
                        document.execCommand = function(cmd, showUI, val) {
                            var c = String(cmd || '').toLowerCase();
                            if ((c === 'copy' || c === 'cut' || c === 'paste') && !hasRecentUserGesture()) {
                                console.warn('[SecurityShield] Blocked background execCommand: ' + c);
                                return false;
                            }
                            return origExecCommand(cmd, showUI, val);
                        };
                    }
                } catch (e) {}
            }

            // 5. Block stealth background window.open & target="_blank" opener leaks
            if ($blockHiddenPopups) {
                try {
                    var origOpen = window.open;
                    window.open = function(url, target, features) {
                        if (!hasRecentUserGesture()) {
                            console.warn('[SecurityShield] Blocked background popup: ' + url);
                            return null;
                        }
                        var win = origOpen ? origOpen.apply(this, arguments) : null;
                        try { if (win) win.opener = null; } catch (e) {}
                        return win;
                    };
                } catch (e) {}
            }
        })();
    """.trimIndent()

    fun injectClientSecurityScript(
        webView: WebView?,
        blockClipboardHijack: Boolean,
        blockHiddenPopups: Boolean
    ) {
        if (webView == null) return
        try {
            webView.evaluateJavascript(
                buildClientSecurityScript(blockClipboardHijack, blockHiddenPopups),
                null
            )
        } catch (_: Exception) {}
    }

    private fun isOfficialTrustedDomain(host: String): Boolean {
        val clean = normalizeHost(host)
        return TRUSTED_REGISTRABLE_DOMAINS.any { clean == it || clean.endsWith(".$it") }
    }

    internal fun isLocalOrPrivateAddress(host: String): Boolean {
        val clean = normalizeHost(host).removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        if (clean == "localhost" ||
            clean.endsWith(".localhost") ||
            clean == "127.0.0.1" ||
            clean.startsWith("127.") ||
            clean == "0.0.0.0" ||
            clean == "::1" ||
            clean.startsWith("192.168.") ||
            clean.startsWith("10.") ||
            clean.startsWith("169.254.") ||
            clean.endsWith(".local") ||
            clean.endsWith(".internal")
        ) {
            return true
        }
        // RFC 1918 172.16.0.0/12
        if (clean.startsWith("172.")) {
            val parts = clean.split(".")
            val second = parts.getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    private val ALLOWED_EXTERNAL_SCHEMES = setOf(
        "intent", "market", "whatsapp", "tel", "mailto", "sms", "geo", "tg"
    )

    private val DANGEROUS_INTERNAL_SCHEMES = setOf(
        "javascript", "vbscript", "data", "file", "content", "blob", "filesystem", "jar", "chrome"
    )

    fun isAllowedExternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return false
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.US)
        return scheme in ALLOWED_EXTERNAL_SCHEMES
    }

    fun isDangerousInternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return false
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.US)
        if (scheme == "about") {
            return !trimmed.equals("about:blank", ignoreCase = true)
        }
        return scheme in DANGEROUS_INTERNAL_SCHEMES
    }

    /**
     * Builds a sanitized external Intent from an untrusted URL or `intent://` URI.
     * Prevents OWASP MASVS Intent Redirection, arbitrary action execution, internal component hijacking,
     * self-package targeting, and ContentProvider URI permission leakage.
     */
    fun buildSafeExternalIntent(context: Context, rawUrl: String): android.content.Intent? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        if (!isAllowedExternalScheme(trimmed)) return null

        return try {
            val parsed = if (trimmed.startsWith("intent://", ignoreCase = true)) {
                android.content.Intent.parseUri(trimmed, android.content.Intent.URI_INTENT_SCHEME)
            } else {
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(trimmed))
            }

            // Force ACTION_VIEW only — block arbitrary actions like ACTION_DELETE, ACTION_CALL, ACTION_SEND, or custom actions
            parsed.action = android.content.Intent.ACTION_VIEW
            parsed.addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            parsed.component = null
            parsed.selector = null
            parsed.clipData = null

            // Strip all URI permission flags and dangerous activity launch flags
            val forbiddenFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            parsed.flags = parsed.flags and forbiddenFlags.inv()

            // Block targeting our own application package (prevents internal loop / confused deputy)
            val targetPkg = parsed.`package`
            if (!targetPkg.isNullOrBlank() && targetPkg.equals(context.packageName, ignoreCase = true)) {
                return null
            }

            // Validate embedded data URI scheme inside parsed intent://
            val dataUri = parsed.data
            if (dataUri != null) {
                val dataScheme = dataUri.scheme?.lowercase(Locale.US).orEmpty()
                if (dataScheme in DANGEROUS_INTERNAL_SCHEMES || dataScheme == "about") {
                    return null
                }
            }

            parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Safely extracts and validates `browser_fallback_url` from an `intent://` string or Intent.
     * Only allows external HTTP(S) URLs and rejects loopback/private network addresses or dangerous schemes.
     */
    fun extractSafeFallbackUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("intent://", ignoreCase = true) || trimmed.length > 2048) return null
        return try {
            val parsed = android.content.Intent.parseUri(trimmed, android.content.Intent.URI_INTENT_SCHEME)
            val fallback = parsed.getStringExtra("browser_fallback_url")?.trim() ?: return null
            if (fallback.isEmpty() || fallback.length > 2048) return null
            if (!fallback.startsWith("https://", ignoreCase = true) &&
                !fallback.startsWith("http://", ignoreCase = true)
            ) {
                return null
            }
            val host = extractHost(fallback)
            if (host.isEmpty() || isLocalOrPrivateAddress(host)) return null
            fallback
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates URIs returned to WebChromeClient.onShowFileChooser (`mFilePickerLauncher`)
     * to prevent malicious chooser targets from returning `file://` URIs or internal app
     * ContentProvider URIs (`${packageName}.fileprovider`, `${packageName}.renderconfig`).
     */
    fun isSafeFileChooserResultUri(
        context: Context,
        uri: Uri?,
        allowedCameraUri: Uri? = null
    ): Boolean {
        if (uri == null) return false
        if (allowedCameraUri != null && uri == allowedCameraUri) return true

        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "content") return false

        val authority = uri.authority?.lowercase(Locale.US)?.trim().orEmpty()
        if (authority.isEmpty()) return false

        val pkgLower = context.packageName.lowercase(Locale.US)
        if (authority == "$pkgLower.fileprovider" ||
            authority == "$pkgLower.renderconfig" ||
            authority == pkgLower ||
            authority.startsWith("$pkgLower.")
        ) {
            return false
        }

        val rawPath = uri.path?.lowercase(Locale.US).orEmpty()
        if (rawPath.contains("..") ||
            rawPath.contains("/data/data/$pkgLower") ||
            rawPath.contains("/$pkgLower/shared_prefs") ||
            rawPath.contains("/$pkgLower/databases")
        ) {
            return false
        }

        return true
    }

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            normalizeHost(Uri.parse(url).host.orEmpty())
        } catch (_: Exception) {
            ""
        }
    }

    private fun normalizeHost(host: String): String =
        host.trim().lowercase(Locale.US).removePrefix("www.")
}

