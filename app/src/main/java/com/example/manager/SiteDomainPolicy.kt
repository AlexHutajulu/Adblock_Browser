package com.example.manager

import java.util.Locale

/**
 * Penentuan batas situs (registrable domain / eTLD+1) untuk klasifikasi
 * first-party vs third-party di seluruh mesin adblock.
 *
 * Menggunakan tabel ccSLD 2-level dan host bersama (multi-tenant) yang umum.
 */
object SiteDomainPolicy {

    // Akhiran dua-label negara yang pendaftarannya berada pada label ke-3
    // (contoh: bca.co.id -> eTLD+1 = bca.co.id).
    private val TWO_LABEL_PUBLIC_SUFFIXES: Set<String> = setOf(
        // Indonesia
        "co.id", "ac.id", "go.id", "or.id", "sch.id", "web.id", "my.id", "biz.id", "desa.id",
        // UK & Commonwealth
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "org.nz", "govt.nz",
        "co.za", "org.za", "gov.za",
        "co.in", "net.in", "org.in", "ac.in", "gov.in",
        // Asia Timur & Tenggara
        "com.sg", "edu.sg", "gov.sg", "org.sg", "net.sg",
        "com.my", "edu.my", "gov.my", "org.my", "net.my",
        "com.ph", "org.ph", "gov.ph", "edu.ph",
        "co.th", "in.th", "ac.th", "go.th", "or.th",
        "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.kr", "ne.kr", "or.kr", "ac.kr", "go.kr",
        "com.tw", "org.tw", "edu.tw", "gov.tw",
        "com.hk", "org.hk", "edu.hk", "gov.hk",
        "com.cn", "net.cn", "org.cn", "edu.cn", "gov.cn",
        // Amerika Latin & Timur Tengah
        "com.br", "net.br", "org.br", "gov.br", "edu.br",
        "com.mx", "org.mx", "gob.mx", "edu.mx",
        "com.ar", "org.ar", "gob.ar",
        "com.tr", "org.tr", "edu.tr", "gov.tr",
        "co.il", "org.il", "ac.il", "gov.il",
        "com.sa", "com.eg", "co.AE".lowercase(Locale.US)
    )

    // Domain hosting bersama di mana setiap subdomain adalah tenant terpisah.
    // a.github.io dan b.github.io BUKAN satu pihak (harus dianggap third-party).
    private val MULTI_TENANT_PARENTS: Set<String> = setOf(
        "github.io",
        "gitlab.io",
        "pages.dev",
        "workers.dev",
        "vercel.app",
        "netlify.app",
        "web.app",
        "firebaseapp.com",
        "appspot.com",
        "cloudfront.net",
        "azurewebsites.net",
        "herokuapp.com",
        "onrender.com",
        "blogspot.com",
        "wordpress.com"
    )

    fun normalizeHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var h = raw.trim().lowercase(Locale.US)
        val schemeIdx = h.indexOf("://")
        if (schemeIdx >= 0) h = h.substring(schemeIdx + 3)
        val slashIdx = h.indexOf('/')
        if (slashIdx >= 0) h = h.substring(0, slashIdx)
        val portIdx = h.indexOf(':')
        if (portIdx >= 0) h = h.substring(0, portIdx)
        h = h.trim('.')
        if (h.startsWith("www.") && h.length > 4) {
            h = h.substring(4)
        }
        return h
    }

    /**
     * Mengembalikan registrable domain (eTLD+1) untuk [rawHost], atau host itu
     * sendiri bila berupa IP / localhost / domain 1-2 label.
     */
    fun registrableDomain(rawHost: String?): String {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return ""
        if (isIpLiteral(host)) return host

        val labels = host.split('.')
        if (labels.size <= 2) return host

        val lastTwo = "${labels[labels.size - 2]}.${labels[labels.size - 1]}"
        if (MULTI_TENANT_PARENTS.contains(lastTwo)) {
            return if (labels.size >= 3) {
                "${labels[labels.size - 3]}.$lastTwo"
            } else {
                host
            }
        }
        if (TWO_LABEL_PUBLIC_SUFFIXES.contains(lastTwo)) {
            return if (labels.size >= 3) {
                "${labels[labels.size - 3]}.$lastTwo"
            } else {
                host
            }
        }
        return lastTwo
    }

    fun isSameSite(hostA: String?, hostB: String?): Boolean {
        val a = normalizeHost(hostA)
        val b = normalizeHost(hostB)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val regA = registrableDomain(a)
        val regB = registrableDomain(b)
        return regA.isNotEmpty() && regA == regB
    }

    fun isThirdParty(requestHost: String?, sourceHost: String?): Boolean {
        val req = normalizeHost(requestHost)
        val src = normalizeHost(sourceHost)
        if (req.isEmpty() || src.isEmpty()) return false
        return !isSameSite(req, src)
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6
        var dots = 0
        for (ch in host) {
            when {
                ch == '.' -> dots++
                ch !in '0'..'9' -> return false
            }
        }
        return dots == 3
    }
}
