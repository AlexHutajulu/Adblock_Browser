package com.example.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class QuickLink(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean = true,
    val position: Int = 0
)

object QuickLinkStore {
    private const val KEY = "quick_links_v1"
    val DEFAULTS = listOf(
        QuickLink("google", "Google", "https://www.google.com", true, 0),
        QuickLink("youtube", "YouTube", "https://www.youtube.com", true, 1),
        QuickLink("wikipedia", "Wikipedia", "https://www.wikipedia.org", true, 2),
        QuickLink("github", "GitHub", "https://github.com", true, 3)
    )

    fun load(context: Context): List<QuickLink> =
        load(EncryptedPreferencesManager.getPreferences(context))

    fun load(prefs: SharedPreferences): List<QuickLink> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QuickLink(
                    o.getString("id"),
                    o.getString("title"),
                    o.getString("url"),
                    o.optBoolean("enabled", true),
                    o.optInt("position", i)
                )
            }
        } catch (_: Exception) {
            DEFAULTS
        }
    }

    fun save(context: Context, links: List<QuickLink>) =
        save(EncryptedPreferencesManager.getPreferences(context), links)

    fun save(prefs: SharedPreferences, links: List<QuickLink>) {
        val arr = JSONArray()
        links.forEachIndexed { idx, l ->
            arr.put(
                JSONObject()
                    .put("id", l.id)
                    .put("title", l.title)
                    .put("url", l.url)
                    .put("enabled", l.enabled)
                    .put("position", if (l.position != 0 || idx == 0) l.position else idx)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun resetToDefaults(context: Context): List<QuickLink> =
        resetToDefaults(EncryptedPreferencesManager.getPreferences(context))

    fun resetToDefaults(prefs: SharedPreferences): List<QuickLink> {
        save(prefs, DEFAULTS)
        return DEFAULTS
    }

    fun normalizeUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return try {
            val u = URI(withScheme)
            val host = u.host
            if (host.isNullOrEmpty() || !host.contains(".")) null else withScheme
        } catch (_: Exception) {
            null
        }
    }
}
