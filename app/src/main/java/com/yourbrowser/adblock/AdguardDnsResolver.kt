package com.yourbrowser.adblock

import android.content.Context
import android.os.Build
import android.util.LruCache
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

object AdguardDnsResolver {
    private lateinit var dohDns: DnsOverHttps
    @Volatile
    private var isInitialized = false

    @Volatile
    var allowLiveDohNetworkInTests: Boolean = false

    // Cache hasil DNS per-host: true = diblokir AdGuard DNS, false = aman
    private val dnsBlockCache = LruCache<String, Boolean>(2048)

    fun init(context: Context) {
        if (isInitialized) return
        val appCache = Cache(File(context.applicationContext.cacheDir, "adguard_doh_cache"), 5L * 1024 * 1024)
        val bootstrapClient = OkHttpClient.Builder()
            .cache(appCache)
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()

        dohDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.adguard-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("94.140.14.14"),
                InetAddress.getByName("94.140.15.15")
            )
            .build()
        isInitialized = true
    }

    fun isInitialized(): Boolean = isInitialized

    fun isDomainBlockedByDns(host: String): Boolean {
        if (host.isEmpty()) return false
        val normalizedHost = host.lowercase(Locale.US)

        // 1. Cek LruCache (O(1), tanpa beban jaringan)
        dnsBlockCache.get(normalizedHost)?.let { return it }

        if (!isInitialized) return false
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true) && !allowLiveDohNetworkInTests) {
            return false
        }

        // 2. Resolve via AdGuard DoH dengan timeout ketat
        return try {
            val addresses = dohDns.lookup(normalizedHost)
            val isSinkholed = addresses.isEmpty() || addresses.any { addr ->
                val ip = addr.hostAddress ?: ""
                ip == "0.0.0.0" || ip == "127.0.0.1" || ip == "::" || ip == "::1"
            }
            dnsBlockCache.put(normalizedHost, isSinkholed)
            isSinkholed
        } catch (e: UnknownHostException) {
            // Jangan blokir permanen jika jaringan user sedang offline, cukup lewati
            false
        } catch (e: Exception) {
            false
        }
    }

    fun cacheHostDecision(host: String, blocked: Boolean) {
        val normalizedHost = host.lowercase(Locale.US)
        if (normalizedHost.isNotEmpty()) {
            dnsBlockCache.put(normalizedHost, blocked)
        }
    }

    fun getCachedDecision(host: String): Boolean? {
        if (host.isEmpty()) return null
        return dnsBlockCache.get(host.lowercase(Locale.US))
    }

    fun clearCache() {
        dnsBlockCache.evictAll()
    }
}
