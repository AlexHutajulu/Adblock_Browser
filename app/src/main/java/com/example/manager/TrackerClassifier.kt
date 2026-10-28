package com.example.manager

import java.util.Locale

/**
 * Klasifikasi domain dan path pelacak yang dipakai bersama oleh
 * AdBlockWebViewClient dan ServiceWorker controller agar keputusan
 * "tracker vs ad" konsisten di kedua jalur.
 */
object TrackerClassifier {

    val TRACKER_DOMAINS: Set<String> = setOf(
        "google-analytics.com",
        "ssl.google-analytics.com",
        "analytics.google.com",
        "stats.g.doubleclick.net",
        "hotjar.com",
        "clarity.ms",
        "mixpanel.com",
        "amplitude.com",
        "segment.com",
        "segment.io",
        "fullstory.com",
        "logrocket.com",
        "inspectlet.com",
        "mouseflow.com",
        "crazyegg.com",
        "luckyorange.com",
        " luckyorange.net".trim(),
        "freshmarketer.com",
        "bugsnag.com",
        "getsentry.com",
        "sentry-cdn.com",
        "scorecardresearch.com",
        "quantserve.com",
        "pixel.facebook.com",
        "an.facebook.com",
        "analytics.tiktok.com",
        "analytics-sg.tiktok.com",
        "ads.linkedin.com",
        "snap.licdn.com",
        "static.ads-twitter.com",
        "ads-api.twitter.com",
        "trk.pinterest.com",
        "log.pinterest.com",
        "events.reddit.com",
        "events.redditmedia.com",
        "mc.yandex.ru",
        "metrika.yandex.ru",
        "appmetrica.yandex.ru",
        "stats.wp.com"
    )

    // Path pelacak yang spesifik (tidak boleh terlalu pendek seperti "/tr/").
    val TRACKER_PATH_PATTERNS: List<String> = listOf(
        "/ptracking",
        "/youtubei/v1/log_event",
        "google-analytics.com/collect",
        "google-analytics.com/j/collect",
        "google-analytics.com/g/collect",
        "/g/collect?",
        "/j/collect?",
        "/pixel.gif",
        "/beacon.js",
        "/analytics.js",
        "/fbevents.js"
    )

    fun isTrackerDomain(host: String): Boolean {
        val h = SiteDomainPolicy.normalizeHost(host)
        if (h.isEmpty()) return false
        return TRACKER_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    fun isTrackerPath(lowerUrl: String): Boolean {
        val u = lowerUrl.lowercase(Locale.US)
        return TRACKER_PATH_PATTERNS.any { u.contains(it) }
    }

    fun isTrackerRequest(host: String, lowerUrl: String): Boolean {
        return isTrackerDomain(host) || isTrackerPath(lowerUrl)
    }
}
