package com.yourbrowser.adblock

data class BlockedRequest(
    val url: String,
    val host: String,
    val type: String, // "Ad" / "Tracker" / "Popup"
    val mainPageUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)
