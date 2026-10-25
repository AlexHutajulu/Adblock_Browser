package com.example.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Mengelola download dan parsing filter list dari sumber publik.
 * Format yang didukung: Adblock Plus / uBlock Origin (.txt)
 *
 * Filter lists yang dipakai:
 * - EasyList: rules iklan utama (banner, popup, overlay)
 * - EasyPrivacy: tracker dan analytics
 * - Peter Lowe's Ad and tracking server list: domain blocklist tambahan
 */
object FilterListManager {

    private const val TAG = "FilterListManager"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 jam
    internal const val MAX_SINGLE_LIST_BYTES = 8 * 1024 * 1024 // 8 MB per list
    internal const val MAX_TOTAL_CACHE_BYTES = 20 * 1024 * 1024 // 20 MB total cache
    private const val MAX_REDIRECTS = 3
    internal const val MAX_RULE_LINE_LENGTH = 512
    internal const val MAX_BLOCK_DOMAINS = 60_000
    internal const val MAX_WHITELIST_DOMAINS = 5_000
    internal const val MAX_BLOCK_PATTERNS = 1_500
    internal const val MAX_COSMETIC_SELECTORS = 2_000

    // Sumber filter list publik — semua gratis dan open source (wajib HTTPS)
    private val FILTER_LIST_URLS = mapOf(
        "easylist"    to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "peterlowe"   to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0"
    )

    data class ParsedRules(
        val blockDomains: HashSet<String>,       // domain exact/subdomain block
        val blockPatterns: List<Regex>,           // path/wildcard regex rules
        val whitelistDomains: HashSet<String>,    // exception @@ rules
        val cosmeticSelectors: List<String>       // ## CSS selectors
    )

    @Volatile
    private var cachedRules: ParsedRules? = null
    private val loadMutex = Mutex()

    /**
     * Memvalidasi bahwa URL filter list menggunakan skema HTTPS dan tidak mengarah
     * ke jaringan internal/privat/loopback (anti-SSRF & anti-downgrade).
     */
    internal fun isSafeFilterListUrl(
        urlStr: String,
        resolveDns: Boolean = true
    ): Boolean {
        val trimmed = urlStr.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return false
        val uri = try {
            Uri.parse(trimmed)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "https") return false

        val host = uri.host?.lowercase(Locale.US)?.trim()?.removeSurrounding("[", "]")?.trimEnd('.') ?: return false
        if (host.isEmpty() ||
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host == "0.0.0.0" ||
            host == "::1" ||
            SecurityGuardEngine.isLocalOrPrivateAddress(host)
        ) {
            return false
        }

        if (!resolveDns) return true

        return try {
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) return false
            addresses.all { addr -> !isRestrictedAddress(addr) }
        } catch (_: Exception) {
            false
        }
    }

    internal fun isRestrictedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        if (addr is Inet4Address) {
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10 (CGNAT), 127.0.0.0/8, 169.254.0.0/16, 198.18.0.0/15, 240.0.0.0/4
            if (b0 == 10) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 127 || b0 == 0 || b0 >= 224) return true
            if (b0 == 100 && (b1 and 0xC0) == 64) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
        } else if (addr is Inet6Address) {
            val b = addr.address
            val firstByte = b[0].toInt() and 0xFF
            // Unique Local Address fc00::/7
            if ((firstByte and 0xFE) == 0xFC) return true
            // Discard Prefix 100::/64 (RFC 6666)
            if (firstByte == 0x01 && (b[1].toInt() and 0xFF) == 0x00) return true

            // IPv4-mapped IPv6 Address (::ffff:0:0/96 or ::ffff:a.b.c.d)
            val isIpv4Mapped = (0..9).all { b[it] == 0.toByte() } &&
                b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
            val isIpv4Compatible = (0..11).all { b[it] == 0.toByte() }
            if (isIpv4Mapped || isIpv4Compatible) {
                val b0 = b[12].toInt() and 0xFF
                val b1 = b[13].toInt() and 0xFF
                if (b0 == 10) return true
                if (b0 == 172 && b1 in 16..31) return true
                if (b0 == 192 && b1 == 168) return true
                if (b0 == 127 || b0 == 0 || b0 >= 224) return true
                if (b0 == 100 && (b1 and 0xC0) == 64) return true
                if (b0 == 169 && b1 == 254) return true
                if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
            }
        }
        return false
    }

    internal fun readBoundedUtf8Stream(input: InputStream, maxBytes: Int): String? {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                return null
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Load rules — dari cache file jika masih valid, atau download ulang.
     * Thread-safe dengan Mutex agar tidak terjadi duplikasi download/parsing paralel.
     */
    suspend fun loadRules(context: Context): ParsedRules = withContext(Dispatchers.IO) {
        cachedRules?.let { return@withContext it }
        loadMutex.withLock {
            cachedRules?.let { return@withLock it }

            val cacheFile = File(context.cacheDir, "adblock_rules.txt")

            val rawText = if (cacheFile.exists() &&
                cacheFile.length() in 1..MAX_TOTAL_CACHE_BYTES.toLong() &&
                System.currentTimeMillis() - cacheFile.lastModified() < CACHE_DURATION_MS
            ) {
                Log.d(TAG, "Loading rules from cache (${cacheFile.length() / 1024}KB)")
                try {
                    cacheFile.inputStream().use { readBoundedUtf8Stream(it, MAX_TOTAL_CACHE_BYTES) }
                } catch (_: Exception) {
                    null
                } ?: run {
                    cacheFile.delete()
                    downloadAllLists(context, cacheFile)
                }
            } else {
                if (cacheFile.exists() && cacheFile.length() > MAX_TOTAL_CACHE_BYTES) {
                    cacheFile.delete()
                }
                Log.d(TAG, "Downloading fresh filter lists...")
                downloadAllLists(context, cacheFile)
            }

            parseRules(rawText).also { cachedRules = it }
        }
    }

    fun isLoaded(): Boolean = cachedRules != null

    fun getCachedRules(): ParsedRules? = cachedRules

    private fun fetchFilterListSecurely(client: OkHttpClient, initialUrl: String): String? {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects <= MAX_REDIRECTS) {
            if (!isSafeFilterListUrl(currentUrl, resolveDns = true)) {
                Log.w(TAG, "Blocked unsafe filter list URL: $currentUrl")
                return null
            }

            val request = Request.Builder()
                .url(currentUrl)
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .header("User-Agent", "AlexBrowser/1.0 AdBlocker")
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: return null
                    val resolved = request.url.resolve(location)?.toString() ?: return null
                    currentUrl = resolved
                    redirects++
                    return@use
                }

                if (!response.isSuccessful) {
                    return null
                }

                val contentLength = response.body?.contentLength() ?: -1L
                if (contentLength > MAX_SINGLE_LIST_BYTES) {
                    Log.w(TAG, "Filter list exceeded size cap ($contentLength bytes): $currentUrl")
                    return null
                }

                val stream = response.body?.byteStream() ?: return null
                return readBoundedUtf8Stream(stream, MAX_SINGLE_LIST_BYTES)
            }
        }
        return null
    }

    private fun downloadAllLists(context: Context, cacheFile: File): String {
        val safeDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = okhttp3.Dns.SYSTEM.lookup(hostname)
                val safeAddresses = resolved.filter { addr -> !isRestrictedAddress(addr) }
                if (safeAddresses.isEmpty() || safeAddresses.size != resolved.size) {
                    throw java.net.UnknownHostException("Blocked restricted or private IP resolution for $hostname")
                }
                return safeAddresses
            }
        }
        val client = OkHttpClient.Builder()
            .dns(safeDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        val combined = StringBuilder()
        var successCount = 0

        for ((name, url) in FILTER_LIST_URLS) {
            try {
                val body = fetchFilterListSecurely(client, url)
                if (!body.isNullOrBlank()) {
                    if (combined.length + body.length <= MAX_TOTAL_CACHE_BYTES) {
                        combined.appendLine("! === $name ===")
                        combined.appendLine(body)
                        successCount++
                        Log.d(TAG, "Downloaded $name: ${body.length / 1024}KB")
                    } else {
                        Log.w(TAG, "Skipping $name: total filter cache limit reached")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download $name: ${e.message}")
            }
        }

        return if (successCount > 0) {
            val result = combined.toString()
            try {
                val tmpFile = File(context.cacheDir, "adblock_rules.txt.tmp")
                tmpFile.writeText(result, Charsets.UTF_8)
                if (!tmpFile.renameTo(cacheFile)) {
                    cacheFile.writeText(result, Charsets.UTF_8)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write filter cache atomically: ${e.message}")
            }
            result
        } else {
            Log.w(TAG, "All downloads failed, using built-in fallback list")
            FALLBACK_DOMAINS.joinToString("\n") { "||$it^" }
        }
    }

    /**
     * Parse Adblock Plus / uBlock Origin format dengan pembatasan memori & proteksi ReDoS.
     */
    internal fun parseRules(raw: String): ParsedRules {
        val blockDomains = HashSet<String>(10000)
        val blockPatterns = mutableListOf<Regex>()
        val whitelistDomains = HashSet<String>(1000)
        val cosmeticSelectors = mutableListOf<String>()

        var parsed = 0
        var skipped = 0

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() ||
                trimmed.length > MAX_RULE_LINE_LENGTH ||
                trimmed.startsWith("!") ||
                trimmed.startsWith("[")
            ) {
                skipped++
                continue
            }

            when {
                // Whitelist exception: @@||domain^
                trimmed.startsWith("@@||") -> {
                    if (whitelistDomains.size < MAX_WHITELIST_DOMAINS) {
                        val domain = trimmed.removePrefix("@@||")
                            .substringBefore("$")
                            .substringBefore("^")
                            .substringBefore("/")
                            .lowercase(Locale.US)
                        if (domain.isNotEmpty() && !domain.contains("*")) {
                            whitelistDomains.add(domain)
                        }
                    }
                }

                // Cosmetic filter: ##selector (global only, skip domain-specific or scriptlets/extended pseudo-selectors)
                trimmed.startsWith("##") && !trimmed.startsWith("##+js") && !trimmed.startsWith("#@#") -> {
                    if (cosmeticSelectors.size < MAX_COSMETIC_SELECTORS) {
                        val selector = trimmed.removePrefix("##").trim()
                        if (selector.isNotEmpty() &&
                            !selector.contains(":-abp-") &&
                            !selector.contains(":has-text") &&
                            !selector.contains(":xpath") &&
                            !selector.contains(":matches-css") &&
                            !selector.contains(":upward") &&
                            !selector.contains(":remove") &&
                            !selector.contains("{")
                        ) {
                            cosmeticSelectors.add(selector)
                        }
                    }
                }

                // Domain block: ||example.com^
                trimmed.startsWith("||") && trimmed.contains("^") &&
                !trimmed.contains("*") && !trimmed.drop(2).substringBefore("^").contains("/") -> {
                    if (blockDomains.size < MAX_BLOCK_DOMAINS) {
                        val domain = trimmed.removePrefix("||")
                            .substringBefore("^")
                            .substringBefore("$")
                            .lowercase(Locale.US)
                        if (domain.isNotEmpty() && domain.contains(".")) {
                            blockDomains.add(domain)
                        }
                    }
                }

                // Path/wildcard pattern (hanya aturan ||... dan menolak raw /regex/ demi mencegah ReDoS)
                trimmed.startsWith("||") -> {
                    if (blockPatterns.size < MAX_BLOCK_PATTERNS) {
                        try {
                            val regexStr = abpRuleToRegex(trimmed)
                            if (regexStr.isNotEmpty()) {
                                blockPatterns.add(Regex(regexStr, RegexOption.IGNORE_CASE))
                            }
                        } catch (_: Exception) {
                            // Skip invalid regex
                        }
                    }
                }
            }
            parsed++
        }

        Log.d(TAG, "Parsed: $parsed rules | blockDomains=${blockDomains.size} | patterns=${blockPatterns.size} | whitelist=${whitelistDomains.size} | cosmetic=${cosmeticSelectors.size}")

        return ParsedRules(blockDomains, blockPatterns, whitelistDomains, cosmeticSelectors)
    }

    internal fun abpRuleToRegex(rule: String): String {
        val withoutOptions = rule.substringBefore("$").trim()
        if (!withoutOptions.startsWith("||")) return ""
        val core = withoutOptions.removePrefix("||").trim()
        if (core.isEmpty() || core.length > 256) return ""
        // Batasi jumlah wildcard '*' agar tidak memicu catastrophic backtracking (ReDoS)
        if (core.count { it == '*' } > 6) return ""

        val sb = StringBuilder("(?:^|://|\\.)")
        for (ch in core) {
            when (ch) {
                '^' -> sb.append("([/?#]|$)")
                '*' -> sb.append("[^/]*")
                '.', '+', '?', '(', ')', '[', ']', '{', '}', '$', '|', '\\' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val FALLBACK_DOMAINS = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "adnxs.com", "criteo.com", "outbrain.com",
        "taboola.com", "pubmatic.com", "rubiconproject.com", "openx.net",
        "adroll.com", "media.net", "propellerads.com", "exoclick.com",
        "google-analytics.com", "analytics.google.com", "hotjar.com",
        "mixpanel.com", "amplitude.com", "segment.com", "statcounter.com",
        "facebook.net", "connect.facebook.net"
    )
}

