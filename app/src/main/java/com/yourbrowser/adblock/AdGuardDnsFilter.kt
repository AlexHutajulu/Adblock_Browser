package com.yourbrowser.adblock

import android.util.LruCache
import com.example.manager.SiteDomainPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lapisan pemeriksaan DNS AdGuard menggunakan DNS-over-HTTPS (RFC 8484) via OkHttp.
 *
 * Endpoint AdGuard Ad-blocking DNS:
 *   https://dns.adguard-dns.com/dns-query
 *   Bootstrap IP: 94.140.14.14, 94.140.15.15
 *
 * AdGuard DNS mengembalikan 0.0.0.0 atau :: untuk domain iklan/pelacak yang
 * diblokir. Kelas ini:
 *   1. TIDAK pernah mem-block thread shouldInterceptRequest secara sinkron bila
 *      belum ada di cache (menghindari freeze WebView).
 *   2. Jika domain belum ada di cache, resolusi dijalankan di background pool
 *      dan hasilnya disimpan di LruCache; request berikutnya ke host yang sama
 *      langsung terblokir dalam O(1).
 *   3. Menyediakan [checkBlocking] untuk pengujian/warmup sinkron dengan batas
 *      waktu ketat.
 */
object AdGuardDnsFilter {

    private const val DOH_URL = "https://dns.adguard-dns.com/dns-query"
    private const val CACHE_SIZE = 4096

    private val enabled = AtomicBoolean(true)

    // Nilai cache: true = diblokir oleh AdGuard DNS (sinkhole 0.0.0.0 / ::), false = diizinkan.
    private val verdictCache = LruCache<String, Boolean>(CACHE_SIZE)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val lookupExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "AlexBrowser-AdGuardDoH").apply { isDaemon = true }
    }

    private val dohResolver: DnsOverHttps by lazy {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(2000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("94.140.14.14"),
                InetAddress.getByName("94.140.15.15")
            )
            .includeIPv6(false)
            .build()
    }

    fun setEnabled(value: Boolean) {
        if (enabled.getAndSet(value) != value && !value) {
            clearCache()
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    fun clearCache() {
        synchronized(verdictCache) {
            verdictCache.evictAll()
        }
        inFlight.clear()
    }

    internal fun putCachedForTest(rawHost: String, blocked: Boolean) {
        val host = SiteDomainPolicy.normalizeHost(rawHost)
        if (host.isNotEmpty()) {
            synchronized(verdictCache) {
                verdictCache.put(host, blocked)
            }
        }
    }

    internal fun getCachedForTest(rawHost: String): Boolean? {
        val host = SiteDomainPolicy.normalizeHost(rawHost)
        if (host.isEmpty()) return null
        return synchronized(verdictCache) { verdictCache.get(host) }
    }

    /**
     * Dipanggil dari jalur panas shouldInterceptRequest:
     * - Bila host sudah ada di cache -> kembalikan hasil langsung (non-blocking).
     * - Bila belum ada di cache -> jadwalkan query DoH di background dan
     *   kembalikan `false` (fail-open pada request pertama agar WebView tidak
     *   tersendat).
     */
    fun isBlockedCached(rawHost: String): Boolean {
        if (!enabled.get()) return false
        val host = SiteDomainPolicy.normalizeHost(rawHost)
        if (host.isEmpty() || isLocalOrLiteral(host)) return false

        val cached = synchronized(verdictCache) { verdictCache.get(host) }
        if (cached != null) return cached

        prefetch(host)
        return false
    }

    fun prefetch(rawHost: String) {
        if (!enabled.get()) return
        val host = SiteDomainPolicy.normalizeHost(rawHost)
        if (host.isEmpty() || isLocalOrLiteral(host)) return
        synchronized(verdictCache) {
            if (verdictCache.get(host) != null) return
        }
        if (!inFlight.add(host)) return

        lookupExecutor.execute {
            try {
                val blocked = queryAdGuardDoH(host)
                if (blocked != null) {
                    synchronized(verdictCache) {
                        verdictCache.put(host, blocked)
                    }
                }
            } finally {
                inFlight.remove(host)
            }
        }
    }

    /**
     * Query sinkron (untuk pengujian atau pre-check); mengembalikan `true` jika
     * AdGuard DNS memetakan host ke alamat sinkhole (0.0.0.0 / :: / 127.0.0.1).
     */
    fun checkBlocking(rawHost: String): Boolean {
        if (!enabled.get()) return false
        val host = SiteDomainPolicy.normalizeHost(rawHost)
        if (host.isEmpty() || isLocalOrLiteral(host)) return false

        synchronized(verdictCache) {
            verdictCache.get(host)?.let { return it }
        }
        val blocked = queryAdGuardDoH(host) ?: return false
        synchronized(verdictCache) {
            verdictCache.put(host, blocked)
        }
        return blocked
    }

    private fun queryAdGuardDoH(host: String): Boolean? {
        return try {
            val addresses = dohResolver.lookup(host)
            if (addresses.isEmpty()) {
                false
            } else {
                addresses.any { addr -> isSinkholeAddress(addr) }
            }
        } catch (_: UnknownHostException) {
            // NXDOMAIN pada AdGuard DNS untuk domain iklan atau domain tidak ada;
            // simpan false agar tidak memicu false-positive pada domain typo.
            false
        } catch (_: Exception) {
            // Timeout / offline -> jangan simpan ke cache supaya dicoba lagi nanti.
            null
        }
    }

    internal fun isSinkholeAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress) return true
        val txt = addr.hostAddress ?: return false
        return txt == "0.0.0.0" || txt == "::" || txt == "0:0:0:0:0:0:0:0" || txt == "127.0.0.1"
    }

    private fun isLocalOrLiteral(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return true
        if (host.contains(':')) return true
        return host.all { it in '0'..'9' || it == '.' }
    }
}
