package com.yourbrowser.adblock

import android.content.Context
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object AdguardDnsClient {
    const val ADGUARD_DOH_URL = "https://dns.adguard-dns.com/dns-query"

    lateinit var client: OkHttpClient

    fun isInitialized(): Boolean = ::client.isInitialized

    @Synchronized
    fun init(context: Context) {
        if (::client.isInitialized) return
        val appCache = Cache(File(context.cacheDir, "adguard_dns_cache"), 10L * 1024 * 1024)
        val bootstrapClient = OkHttpClient.Builder()
            .cache(appCache)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val dns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(ADGUARD_DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("94.140.14.14"),
                InetAddress.getByName("94.140.15.15")
            )
            .build()

        client = OkHttpClient.Builder()
            .dns(dns)
            .cache(appCache)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        AdguardDnsResolver.init(context)
    }
}
