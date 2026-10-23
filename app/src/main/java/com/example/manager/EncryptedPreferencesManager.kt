package com.example.manager

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure wrapper for SharedPreferences with encryption.
 * All sensitive data (permissions, settings, scripts) are encrypted at rest.
 *
 * AUDIT FIX: Replaces plaintext SharedPreferences
 * Issue: CWE-312 - Cleartext Storage of Sensitive Information
 * Severity: CRITICAL
 */
object EncryptedPreferencesManager {
    private const val TAG = "EncryptedPreferencesManager"
    private const val PREFS_NAME = "encrypted_browser_prefs"
    
    private var encryptedPrefs: EncryptedSharedPreferences? = null
    
    /**
     * Initialize encrypted preferences with AES256-GCM encryption
     * Uses Android Keystore for key derivation
     */
    fun initialize(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
            
            Log.i(TAG, "Encrypted preferences initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted preferences", e)
            throw RuntimeException("Critical: Encryption initialization failed", e)
        }
    }
    
    fun getPreferences(context: Context): EncryptedSharedPreferences {
        if (encryptedPrefs == null) {
            initialize(context)
        }
        return encryptedPrefs ?: throw IllegalStateException("Encrypted preferences not initialized")
    }
    
    fun getString(context: Context, key: String, defaultValue: String = ""): String {
        return try {
            getPreferences(context).getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted string: $key", e)
            defaultValue
        }
    }
    
    fun putString(context: Context, key: String, value: String) {
        try {
            getPreferences(context).edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted string: $key", e)
        }
    }
    
    fun getInt(context: Context, key: String, defaultValue: Int = 0): Int {
        return try {
            getPreferences(context).getInt(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted int: $key", e)
            defaultValue
        }
    }
    
    fun putInt(context: Context, key: String, value: Int) {
        try {
            getPreferences(context).edit().putInt(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted int: $key", e)
        }
    }
    
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return try {
            getPreferences(context).getBoolean(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted boolean: $key", e)
            defaultValue
        }
    }
    
    fun putBoolean(context: Context, key: String, value: Boolean) {
        try {
            getPreferences(context).edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted boolean: $key", e)
        }
    }
    
    fun getLong(context: Context, key: String, defaultValue: Long = 0L): Long {
        return try {
            getPreferences(context).getLong(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted long: $key", e)
            defaultValue
        }
    }
    
    fun putLong(context: Context, key: String, value: Long) {
        try {
            getPreferences(context).edit().putLong(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted long: $key", e)
        }
    }
    
    fun remove(context: Context, key: String) {
        try {
            getPreferences(context).edit().remove(key).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error removing encrypted preference: $key", e)
        }
    }
    
    fun clear(context: Context) {
        try {
            getPreferences(context).edit().clear().apply()
            Log.i(TAG, "All encrypted preferences cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing encrypted preferences", e)
        }
    }
}
