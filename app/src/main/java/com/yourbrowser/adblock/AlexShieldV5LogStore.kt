package com.yourbrowser.adblock

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

data class AlexShieldBlockedLog(
    val tabId: String,
    val pageUrl: String,
    val pageHost: String,
    val blockedUrl: String,
    val blockedHost: String,
    val reason: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

object AlexShieldV5LogStore {
    private const val MAX_LOGS = 200
    private val logQueue = ConcurrentLinkedDeque<String>()
    private val sizeCounter = AtomicInteger(0)

    private val structuredQueue = ConcurrentLinkedDeque<AlexShieldBlockedLog>()
    private val structuredSizeCounter = AtomicInteger(0)

    private val _logs = MutableStateFlow<List<AlexShieldBlockedLog>>(emptyList())
    val logs: StateFlow<List<AlexShieldBlockedLog>> = _logs.asStateFlow()

    fun add(reason: String, url: String, isPrivate: Boolean) {
        if (isPrivate) return // Patuhi aturan T2: Jangan pernah log tab private!

        val sanitizedUrl = url.substringBefore('?').take(120) // Potong URL panjang agar hemat RAM
        val entry = "[$reason] $sanitizedUrl"

        logQueue.addFirst(entry)
        if (sizeCounter.incrementAndGet() > MAX_LOGS) {
            logQueue.pollLast()
            sizeCounter.decrementAndGet()
        }
    }

    fun getLogsSnapshot(): List<String> {
        return logQueue.toList()
    }

    fun clear() {
        logQueue.clear()
        sizeCounter.set(0)
        structuredQueue.clear()
        structuredSizeCounter.set(0)
    }

    fun add(
        tabId: String,
        pageUrl: String,
        blockedUrl: String,
        reason: String,
        isPrivateTab: Boolean = false
    ) {
        // Solusi T2: JANGAN PERNAH menyimpan riwayat URL yang diblokir dari tab Private/Incognito
        if (isPrivateTab) return
        if (blockedUrl.isBlank()) return

        // Simpan ke queue string ringan tanpa alokasi list baru
        add(reason = reason, url = blockedUrl, isPrivate = false)

        val sanitizedBlockedUrl = blockedUrl.substringBefore('?').take(120)
        val sanitizedPageUrl = pageUrl.substringBefore('?').take(120)
        val pHost = extractCleanHost(sanitizedPageUrl)
        val bHost = extractCleanHost(sanitizedBlockedUrl).ifEmpty { sanitizedBlockedUrl.take(80) }
        val cat = if (
            reason.contains("tracker", ignoreCase = true) ||
            reason.contains("beacon", ignoreCase = true) ||
            sanitizedBlockedUrl.contains("analytics", ignoreCase = true) ||
            sanitizedBlockedUrl.contains("pixel", ignoreCase = true)
        ) {
            "Tracker"
        } else {
            "Ad"
        }

        val entry = AlexShieldBlockedLog(
            tabId = tabId,
            pageUrl = sanitizedPageUrl,
            pageHost = pHost,
            blockedUrl = sanitizedBlockedUrl,
            blockedHost = bHost,
            reason = reason,
            category = cat
        )

        structuredQueue.addFirst(entry)
        if (structuredSizeCounter.incrementAndGet() > MAX_LOGS) {
            structuredQueue.pollLast()
            structuredSizeCounter.decrementAndGet()
        }
    }

    fun addLogEntry(pageUrl: String, blockedHost: String, type: String, isPrivateTab: Boolean = false) {
        if (isPrivateTab) return
        if (pageUrl.isBlank() || blockedHost.isBlank()) return
        val reason = if (type.equals("Tracker", ignoreCase = true)) "tracker-domain" else "ad-domain"
        add(
            tabId = "legacy",
            pageUrl = pageUrl,
            blockedUrl = "https://$blockedHost",
            reason = reason,
            isPrivateTab = false
        )
    }

    fun forTab(tabId: String): List<AlexShieldBlockedLog> {
        return structuredQueue.filter { it.tabId == tabId }
    }

    fun getForTab(tabId: String): List<AlexShieldBlockedLog> = forTab(tabId)

    fun getBlockedLogsForPage(pageUrl: String): List<BlockedRequest> {
        val targetHost = extractCleanHost(pageUrl)
        if (targetHost.isEmpty()) return emptyList()
        return structuredQueue
            .filter { entry ->
                entry.pageHost.isNotEmpty() && (
                    entry.pageHost == targetHost ||
                        entry.pageHost.endsWith(".$targetHost") ||
                        targetHost.endsWith(".${entry.pageHost}")
                    )
            }
            .map { entry ->
                BlockedRequest(
                    url = entry.blockedUrl,
                    host = entry.blockedHost,
                    type = entry.category,
                    mainPageUrl = entry.pageUrl,
                    timestamp = entry.timestamp
                )
            }
    }

    fun clearTab(tabId: String) {
        val iterator = structuredQueue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().tabId == tabId) {
                iterator.remove()
                structuredSizeCounter.updateAndGet { current -> if (current > 0) current - 1 else 0 }
            }
        }
    }

    fun clearAll() {
        clear()
    }

    private fun extractCleanHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching {
            Uri.parse(url).host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        }.getOrDefault("")
    }
}
