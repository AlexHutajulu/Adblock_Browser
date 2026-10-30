package com.yourbrowser.adblock

import android.content.Context
import android.os.Build

/**
 * Lightweight façade delegating to [AdGuardDnsFilter] (audit M4: consolidated
 * overlapping AdGuard DoH caches and removed redundant OkHttpClient allocation).
 */
object AdguardDnsResolver {
    @Volatile
    private var initialized = false

    @Volatile
    var allowLiveDohNetworkInTests: Boolean = false

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        initialized = true
    }

    fun isInitialized(): Boolean = initialized

    fun isDomainBlockedByDns(host: String): Boolean {
        if (host.isEmpty()) return false
        AdGuardDnsFilter.getCachedForTest(host)?.let { return it }
        if (!initialized) return false
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true) && !allowLiveDohNetworkInTests) {
            return false
        }
        return AdGuardDnsFilter.checkBlocking(host)
    }

    fun cacheHostDecision(host: String, blocked: Boolean) {
        AdGuardDnsFilter.putCachedForTest(host, blocked)
    }

    fun getCachedDecision(host: String): Boolean? {
        return AdGuardDnsFilter.getCachedForTest(host)
    }

    fun clearCache() {
        AdGuardDnsFilter.clearCache()
    }
}
