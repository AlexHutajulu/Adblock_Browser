package com.example.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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

    // Sumber filter list publik — semua gratis dan open source
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
     * Load rules — dari cache file jika masih valid, atau download ulang.
     * Thread-safe dengan Mutex agar tidak terjadi duplikasi download/parsing paralel.
     */
    suspend fun loadRules(context: Context): ParsedRules = withContext(Dispatchers.IO) {
        cachedRules?.let { return@withContext it }
        loadMutex.withLock {
            cachedRules?.let { return@withLock it }

            val cacheFile = File(context.cacheDir, "adblock_rules.txt")

            val rawText = if (cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < CACHE_DURATION_MS) {
                Log.d(TAG, "Loading rules from cache (${cacheFile.length() / 1024}KB)")
                cacheFile.readText()
            } else {
                Log.d(TAG, "Downloading fresh filter lists...")
                downloadAllLists(context, cacheFile)
            }

            parseRules(rawText).also { cachedRules = it }
        }
    }

    fun isLoaded(): Boolean = cachedRules != null

    fun getCachedRules(): ParsedRules? = cachedRules

    private fun downloadAllLists(context: Context, cacheFile: File): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val combined = StringBuilder()
        var successCount = 0

        for ((name, url) in FILTER_LIST_URLS) {
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "AlexBrowser/1.0 AdBlocker")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    combined.appendLine("! === $name ===")
                    combined.appendLine(body)
                    successCount++
                    Log.d(TAG, "Downloaded $name: ${body.length / 1024}KB")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download $name: ${e.message}")
            }
        }

        return if (successCount > 0) {
            val result = combined.toString()
            cacheFile.writeText(result) // simpan ke cache
            result
        } else {
            Log.w(TAG, "All downloads failed, using built-in fallback list")
            FALLBACK_DOMAINS.joinToString("\n") { "||$it^" }
        }
    }

    /**
     * Parse Adblock Plus / uBlock Origin format.
     */
    private fun parseRules(raw: String): ParsedRules {
        val blockDomains = HashSet<String>(10000)
        val blockPatterns = mutableListOf<Regex>()
        val whitelistDomains = HashSet<String>(1000)
        val cosmeticSelectors = mutableListOf<String>()

        var parsed = 0
        var skipped = 0

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) {
                skipped++
                continue
            }

            when {
                // Whitelist exception: @@||domain^
                trimmed.startsWith("@@||") -> {
                    val domain = trimmed.removePrefix("@@||")
                        .substringBefore("^").substringBefore("/").lowercase()
                    if (domain.isNotEmpty() && !domain.contains("*")) {
                        whitelistDomains.add(domain)
                    }
                }

                // Cosmetic filter: ##selector (global only, skip domain-specific or scriptlets/extended pseudo-selectors)
                trimmed.startsWith("##") && !trimmed.startsWith("##+js") && !trimmed.startsWith("#@#") -> {
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

                // Domain block: ||example.com^
                trimmed.startsWith("||") && trimmed.contains("^") &&
                !trimmed.contains("*") && !trimmed.drop(2).contains("/") -> {
                    val domain = trimmed.removePrefix("||")
                        .substringBefore("^").lowercase()
                    if (domain.isNotEmpty() && domain.contains(".")) {
                        blockDomains.add(domain)
                    }
                }

                // Path/wildcard pattern
                trimmed.startsWith("||") || (trimmed.startsWith("/") && trimmed.endsWith("/")) -> {
                    try {
                        val regexStr = abpRuleToRegex(trimmed)
                        if (regexStr.isNotEmpty()) {
                            blockPatterns.add(Regex(regexStr, RegexOption.IGNORE_CASE))
                        }
                    } catch (e: Exception) {
                        // Skip
                    }
                }
            }
            parsed++
        }

        Log.d(TAG, "Parsed: $parsed rules | blockDomains=${blockDomains.size} | patterns=${blockPatterns.size} | whitelist=${whitelistDomains.size} | cosmetic=${cosmeticSelectors.size}")

        return ParsedRules(blockDomains, blockPatterns, whitelistDomains, cosmeticSelectors)
    }

    private fun abpRuleToRegex(rule: String): String {
        val cleaned = rule
            .removePrefix("||")
            .replace(".", "\\.")
            .replace("?", "\\?")
            .replace("^", "([/?#]|$)")
            .replace("*", "[^/]*")  // * hanya match dalam satu segment path, bukan .*
        // Tambahkan anchor domain di awal supaya tidak matching substring sembarangan
        return "(?:^|\\.)$cleaned"
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
