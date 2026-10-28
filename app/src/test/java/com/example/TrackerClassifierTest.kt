package com.example

import com.example.manager.TrackerClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerClassifierTest {

    @Test
    fun detectsKnownTrackerDomainsAndSubdomains() {
        assertTrue(TrackerClassifier.isTrackerDomain("www.google-analytics.com"))
        assertTrue(TrackerClassifier.isTrackerDomain("script.hotjar.com"))
        assertFalse(TrackerClassifier.isTrackerDomain("cdn.jsdelivr.net"))
    }

    @Test
    fun detectsTrackerPathsWithoutFalsePositiveOnShortTr() {
        assertTrue(TrackerClassifier.isTrackerPath("https://example.com/youtubei/v1/log_event?alt=json"))
        assertTrue(TrackerClassifier.isTrackerPath("https://www.google-analytics.com/g/collect?v=2"))
        // Bahasa Turki "/tr/" TIDAK boleh terdeteksi sebagai tracker
        assertFalse(TrackerClassifier.isTrackerPath("https://site.example.com/tr/haberler/ekonomi"))
    }
}
