package com.example.manager

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object DownloadHelper {

    suspend fun checkAcceptRanges(urlStr: String, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!AppDownloadManager.isSafeDownloadUrl(urlStr, resolveDns = true)) {
                return@withContext false
            }
            val url = URL(urlStr)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = 5000
                readTimeout = 5000
            }

            val acceptRanges = connection.getHeaderField("Accept-Ranges")
            val supportRange = acceptRanges?.equals("bytes", ignoreCase = true) == true
            connection.disconnect()
            supportRange
        } catch (e: Exception) {
            false
        }
    }

    fun downloadFile(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        downloadAcceleration: String
    ) {
        if (!AppDownloadManager.isSafeDownloadUrl(url, resolveDns = false) || url.startsWith("data:", ignoreCase = true)) {
            android.widget.Toast.makeText(context, "Download blocked: Unsafe URL", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val rawFilename = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val filename = AppDownloadManager.sanitizeFileName(rawFilename)

        // Safety extensions validation
        val suspiciousExtensions = listOf(".apk", ".exe", ".sh", ".bat", ".vbs", ".msi", ".jar")
        var finalMimeType = mimeType
        if (suspiciousExtensions.any { filename.lowercase().endsWith(it) }) {
            android.widget.Toast.makeText(context, "⚠ Peringatan: File ini mungkin berbahaya!", android.widget.Toast.LENGTH_LONG).show()
            if (finalMimeType.isBlank() || finalMimeType == "application/octet-stream") {
                if (filename.lowercase().endsWith(".apk")) finalMimeType = "application/vnd.android.package-archive"
            }
        }

        if (downloadAcceleration == "Fast") {
            // Check if server supports Accept-Ranges
            CoroutineScope(Dispatchers.Main).launch {
                val supportsRange = checkAcceptRanges(url, userAgent)
                if (supportsRange) {
                    android.widget.Toast.makeText(context, "Menggunakan Fast Download (4 Koneksi)", android.widget.Toast.LENGTH_SHORT).show()
                    enqueueStandardDownload(context, url, userAgent, filename, finalMimeType)
                } else {
                    android.widget.Toast.makeText(context, "Server tidak mendukung Range. Fallback ke Normal Mode.", android.widget.Toast.LENGTH_SHORT).show()
                    enqueueStandardDownload(context, url, userAgent, filename, finalMimeType)
                }
            }
        } else {
            enqueueStandardDownload(context, url, userAgent, filename, finalMimeType)
        }
    }

    private fun enqueueStandardDownload(
        context: Context,
        url: String,
        userAgent: String,
        filename: String,
        mimeType: String
    ) {
        if (!AppDownloadManager.isSafeDownloadUrl(url, resolveDns = false) || url.startsWith("data:", ignoreCase = true)) {
            return
        }
        val safeFilename = AppDownloadManager.sanitizeFileName(filename)
        val request = DownloadManager.Request(Uri.parse(url))
        val cookies = CookieManager.getInstance().getCookie(url)
        request.setMimeType(mimeType)
        if (!cookies.isNullOrBlank()) {
            request.addRequestHeader("cookie", cookies)
        }
        request.addRequestHeader("User-Agent", userAgent)
        
        request.setDescription("Downloading file...")
        request.setTitle(safeFilename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFilename)

        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Download failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
