package com.example.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.URI
import java.security.MessageDigest

object FaviconStore {
    private const val MAX_MEMORY_BYTES = 8 * 1024 * 1024   // batas memori 8 MB
    private const val SIZE_PX = 64
    private var dir: File? = null

    private val cache = object : LruCache<String, Bitmap>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun init(context: Context) {
        dir = File(context.filesDir, "favicons").apply { mkdirs() }
    }

    fun hostOf(url: String): String =
        try { URI(url).host?.lowercase()?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }

    private fun fileFor(host: String): File? {
        val d = dir ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(d, "$hash.png")
    }

    fun put(url: String?, icon: Bitmap?, isPrivate: Boolean = false) {
        if (url.isNullOrBlank() || icon == null) return
        val host = hostOf(url); if (host.isEmpty()) return
        val scaled = Bitmap.createScaledBitmap(icon, SIZE_PX, SIZE_PX, true)
        cache.put(host, scaled)
        // SECURITY FIX: Never persist incognito / private tab favicons to disk.
        if (!isPrivate) {
            fileFor(host)?.let { f -> f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    fun clearPrivateSessionFavicons(hosts: Set<String>) {
        if (hosts.isEmpty()) return
        for (host in hosts) {
            val h = host.lowercase().removePrefix("www.")
            if (h.isNotEmpty()) {
                cache.remove(h)
            }
        }
    }

    fun get(host: String): Bitmap? {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return null
        cache.get(h)?.let { return it }
        val f = fileFor(h) ?: return null
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        cache.put(h, bmp)
        return bmp
    }
}

/**
 * Pemetaan host ke logo brand resmi di app/src/main/res/drawable-nodpi/brand_*.png.
 * Saat file belum ditambahkan oleh pemilik proyek, otomatis fallback ke FaviconStore lalu ikon globe.
 */
object BrandIcons {
    // Nama resource drawable-nodpi yang disiapkan untuk pemilik proyek:
    // - app/src/main/res/drawable-nodpi/brand_google.png
    // - app/src/main/res/drawable-nodpi/brand_youtube.png
    // - app/src/main/res/drawable-nodpi/brand_wikipedia.png
    // - app/src/main/res/drawable-nodpi/brand_github.png
    private val BRAND_DRAWABLE_NAMES = mapOf(
        "google.com" to "brand_google",
        "youtube.com" to "brand_youtube",
        "wikipedia.org" to "brand_wikipedia",
        "github.com" to "brand_github"
    )

    // Tahan referensi langsung R.drawable.brand_* sampai pemilik menambahkan file PNG resmi
    private val MAP: Map<String, Int> = emptyMap(
        // "google.com" to R.drawable.brand_google,
        // "youtube.com" to R.drawable.brand_youtube,
        // "wikipedia.org" to R.drawable.brand_wikipedia,
        // "github.com" to R.drawable.brand_github
    )

    fun resIdFor(host: String, context: Context? = null): Int? {
        val h = host.lowercase().removePrefix("www.")
        MAP.entries.firstOrNull { h == it.key || h.endsWith("." + it.key) }?.value?.let { return it }
        if (context != null) {
            val drawableName = BRAND_DRAWABLE_NAMES.entries
                .firstOrNull { h == it.key || h.endsWith("." + it.key) }?.value
            if (drawableName != null) {
                val id = context.resources.getIdentifier(drawableName, "drawable", context.packageName)
                if (id != 0) return id
            }
        }
        return null
    }
}

@Composable
fun SiteIcon(host: String, sizeDp: Int = 20) {
    val context = LocalContext.current
    val cleanHost = remember(host) {
        if (host.contains("://")) FaviconStore.hostOf(host) else host.lowercase().removePrefix("www.")
    }
    val res = remember(cleanHost) { BrandIcons.resIdFor(cleanHost, context) }
    val bmp = remember(cleanHost) { FaviconStore.get(cleanHost) }
    when {
        res != null -> Image(painterResource(res), null, Modifier.size(sizeDp.dp))
        bmp != null -> Image(bmp.asImageBitmap(), null, Modifier.size(sizeDp.dp))
        else -> Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(sizeDp.dp)
        )
    }
}
