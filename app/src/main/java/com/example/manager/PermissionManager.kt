package com.example.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

enum class PermissionState {
    ASK, ALLOW, BLOCK
}

enum class SitePermissionType(val key: String) {
    CAMERA("camera"),
    MICROPHONE("microphone"),
    LOCATION("location"),
    NOTIFICATION("notification"),
    PROTECTED_MEDIA("protected_media"),
    MIDI("midi")
}

class PermissionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("alex_browser_site_permissions", Context.MODE_PRIVATE)

    fun getWebsitePermission(url: String, type: SitePermissionType): PermissionState {
        val origin = getOrigin(url) ?: return PermissionState.ASK
        val siteDataStr = prefs.getString(origin, null) ?: return PermissionState.ASK
        
        return try {
            val json = JSONObject(siteDataStr)
            if (json.has(type.key)) {
                val stateStr = json.getString(type.key)
                PermissionState.valueOf(stateStr)
            } else {
                PermissionState.ASK
            }
        } catch (e: Exception) {
            PermissionState.ASK
        }
    }

    fun setWebsitePermission(url: String, type: SitePermissionType, state: PermissionState) {
        val origin = getOrigin(url) ?: return
        val siteDataStr = prefs.getString(origin, "{}") ?: "{}"
        
        try {
            val json = JSONObject(siteDataStr)
            json.put(type.key, state.name)
            prefs.edit().putString(origin, json.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resetWebsitePermission(url: String) {
        val origin = getOrigin(url) ?: return
        prefs.edit().remove(origin).apply()
    }

    fun clearAllPermissions() {
        prefs.edit().clear().apply()
    }

    fun getAllPermissions(): Map<String, Map<SitePermissionType, PermissionState>> {
        val result = mutableMapOf<String, Map<SitePermissionType, PermissionState>>()
        for ((key, valueStr) in prefs.all) {
            if (valueStr is String) {
                try {
                    val json = JSONObject(valueStr)
                    val originMap = mutableMapOf<SitePermissionType, PermissionState>()
                    for (type in SitePermissionType.values()) {
                        if (json.has(type.key)) {
                            originMap[type] = PermissionState.valueOf(json.getString(type.key))
                        }
                    }
                    if (originMap.isNotEmpty()) {
                        result[key] = originMap
                    }
                } catch (e: Exception) {}
            }
        }
        return result
    }

    private fun getOrigin(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            uri.host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }
}
