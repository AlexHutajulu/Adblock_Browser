package com.yourbrowser.adblock

import java.util.Locale

object DomainClassifier {
    private val publicSuffixes = setOf(
        "co.id", "or.id", "ac.id", "go.id", "sch.id", "web.id", "my.id",
        "co.uk", "org.uk", "ac.uk", "com.au", "co.jp", "co.nz", "com.br", "com.sg",
        "com", "net", "org", "io", "app", "dev"
    )
    private val platformDomains = setOf(
        "blogspot.com", "github.io", "wordpress.com", "vercel.app", "netlify.app", "pages.dev"
    )

    fun getBaseDomain(urlHost: String): String {
        val cleanHost = urlHost.trim().lowercase(Locale.US).removePrefix("www.").trimEnd('.')
        if (cleanHost.isEmpty()) return ""
        val parts = cleanHost.split(".")
        if (parts.size <= 1) return cleanHost

        // Cek platform domain (contoh: user.github.io -> anggap github.io sebagai base TAPI user.github.io adalah unik)
        for (platform in platformDomains) {
            if (cleanHost.endsWith(".$platform") || cleanHost == platform) {
                return cleanHost // Perlakukan subdomain platform sebagai domain mandiri
            }
        }

        val tld = parts.last()
        val sld = parts[parts.size - 2]

        return if (publicSuffixes.contains("$sld.$tld") && parts.size >= 3) {
            "${parts[parts.size - 3]}.$sld.$tld"
        } else {
            "$sld.$tld"
        }
    }

    fun isThirdParty(pageHost: String, requestHost: String): Boolean {
        val cleanPage = pageHost.trim().lowercase(Locale.US).removePrefix("www.").trimEnd('.')
        val cleanReq = requestHost.trim().lowercase(Locale.US).removePrefix("www.").trimEnd('.')
        if (cleanPage.isEmpty() || cleanReq.isEmpty()) return false
        val pageBase = getBaseDomain(cleanPage)
        val reqBase = getBaseDomain(cleanReq)
        return pageBase != reqBase
    }
}
