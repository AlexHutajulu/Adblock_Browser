package com.example.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
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

class PermissionManager(private val context: Context) {
    private val TAG = "PermissionManager"
    // AUDIT FIX: CWE-312 - Use encrypted preferences instead of plaintext
    private fun getPrefs() = EncryptedPreferencesManager.getPreferences(context)

    fun getWebsitePermission(url: String, type: SitePermissionType): PermissionState {
        val origin = getOrigin(url) ?: return PermissionState.ASK
        val siteDataStr = EncryptedPreferencesManager.getString(context, origin, null)
        if (siteDataStr == null) return PermissionState.ASK
        
        return try {
            val json = JSONObject(siteDataStr)
            if (json.has(type.key)) {
                val stateStr = json.getString(type.key)
                PermissionState.valueOf(stateStr)
            } else {
                PermissionState.ASK
            }
        } catch (e: Exception) {
            SecurityLogger.logValidationError("PermissionManager", "Failed to parse permission state for $origin")
            PermissionState.ASK
        }
    }

    fun setWebsitePermission(url: String, type: SitePermissionType, state: PermissionState) {
        val origin = getOrigin(url) ?: return
        val siteDataStr = EncryptedPreferencesManager.getString(context, origin, "{}")
        
        try {
            val json = JSONObject(siteDataStr)
            json.put(type.key, state.name)
            EncryptedPreferencesManager.putString(context, origin, json.toString())
            Log.d(TAG, "Permission set for $origin: ${type.key} = ${state.name}")
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to set website permission for $origin",
                exception = e
            )
        }
    }

    fun resetWebsitePermission(url: String) {
        val origin = getOrigin(url) ?: return
        try {
            EncryptedPreferencesManager.remove(context, origin)
            Log.d(TAG, "Permissions reset for $origin")
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to reset website permission for $origin",
                exception = e
            )
        }
    }

    fun clearAllPermissions() {
        try {
            EncryptedPreferencesManager.clear(context)
            Log.i(TAG, "All permissions cleared")
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to clear all permissions",
                exception = e
            )
        }
    }

    fun getAllPermissions(): Map<String, Map<SitePermissionType, PermissionState>> {
        val result = mutableMapOf<String, Map<SitePermissionType, PermissionState>>()
        return try {
            val prefs = getPrefs()
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
                    } catch (e: Exception) {
                        SecurityLogger.logValidationError("PermissionManager", "Failed to parse permission entry for $key")
                    }
                }
            }
            result
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to get all permissions",
                exception = e
            )
            emptyMap()
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
