package com.yourbrowser.adblock

import android.content.Context

/**
 * Lightweight façade delegating to [AdGuardDnsFilter] (audit M4: removed
 * duplicate OkHttpClient / DoH bootstrap during cold start).
 */
object AdguardDnsClient {
    const val ADGUARD_DOH_URL = "https://dns.adguard-dns.com/dns-query"

    @Volatile
    private var initialized = false

    fun isInitialized(): Boolean = initialized

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        initialized = true
        AdguardDnsResolver.init(context)
    }
}
