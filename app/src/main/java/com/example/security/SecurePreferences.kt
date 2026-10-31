package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * High-Security Keystore-Backed SharedPreferences Replacement for Android.
 *
 * Security Characteristics:
 * - Uses hardware-backed AES-256-GCM (Android Keystore / StrongBox) instead of deprecated EncryptedSharedPreferences.
 * - Authenticated Additional Data (AAD) binds the ciphertext to the exact preference key to prevent swap attacks.
 * - Blinded key hashing (HMAC-SHA256) so preference/setting names are never leaked in disk files.
 * - Stored in `noBackupFilesDir` to prevent leakage via Android cloud backups or adb backup.
 * - Atomic write with `.tmp` and fsync to prevent file corruption on sudden power loss.
 * - Thread-safe in-memory cache for O(1) reads without UI thread jank.
 */
class SecurePreferences private constructor(
    private val context: Context,
    private val fileName: String = DEFAULT_PREFS_FILE
) : SharedPreferences {

    private val dataMap = ConcurrentHashMap<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val diskExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SecurePrefs-IO").apply { isDaemon = true }
    }
    private val writeLock = Any()

    init {
        loadFromDisk()
    }

    private fun getStorageFile(): File {
        val dir = context.noBackupFilesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    private fun loadFromDisk() {
        val file = getStorageFile()
        if (!file.exists() || file.length() <= 0L) return
        try {
            val content = file.readText(StandardCharsets.UTF_8)
            val root = JSONObject(content)
            val entries = root.optJSONObject("entries") ?: return
            val keys = entries.keys()
            while (keys.hasNext()) {
                val hashedKey = keys.next()
                val entryObj = entries.optJSONObject(hashedKey) ?: continue
                val encKeyBase64 = entryObj.optString("k", "")
                val encValBase64 = entryObj.optString("v", "")
                val type = entryObj.optString("t", "")
                if (encKeyBase64.isEmpty() || encValBase64.isEmpty()) continue

                try {
                    val rawEncKey = Base64.decode(encKeyBase64, Base64.NO_WRAP)
                    val rawEncVal = Base64.decode(encValBase64, Base64.NO_WRAP)
                    val originalKeyBytes = KeystoreCrypto.decryptGcm(
                        envelope = rawEncKey,
                        alias = KeystoreCrypto.ALIAS_PREFS_KEY,
                        aad = null,
                        context = context
                    )
                    val originalKey = String(originalKeyBytes, StandardCharsets.UTF_8)

                    // AAD verification: value ciphertext is cryptographically bound to originalKey
                    val valBytes = KeystoreCrypto.decryptGcm(
                        envelope = rawEncVal,
                        alias = KeystoreCrypto.ALIAS_PREFS_KEY,
                        aad = originalKeyBytes,
                        context = context
                    )

                    val value: Any? = when (type) {
                        "STR" -> String(valBytes, StandardCharsets.UTF_8)
                        "INT" -> String(valBytes, StandardCharsets.UTF_8).toIntOrNull()
                        "LNG" -> String(valBytes, StandardCharsets.UTF_8).toLongOrNull()
                        "FLT" -> String(valBytes, StandardCharsets.UTF_8).toFloatOrNull()
                        "BOL" -> String(valBytes, StandardCharsets.UTF_8).toBoolean()
                        "SET" -> {
                            val arr = JSONArray(String(valBytes, StandardCharsets.UTF_8))
                            val set = HashSet<String>()
                            for (i in 0 until arr.length()) {
                                set.add(arr.optString(i))
                            }
                            set
                        }
                        else -> null
                    }

                    if (value != null) {
                        dataMap[originalKey] = value
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Skipping corrupted or tampered preference entry in $hashedKey: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load secure preferences from disk: ${t.message}", t)
        }
    }

    private fun persistToDiskSync(): Boolean {
        synchronized(writeLock) {
            return try {
                val root = JSONObject()
                root.put("ver", 2)
                val entries = JSONObject()

                val snapshot = HashMap(dataMap)
                for ((key, value) in snapshot) {
                    if (value == null) continue
                    val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                    val hashedKey = KeystoreCrypto.hashPreferenceKey(key, context)
                    val encKey = KeystoreCrypto.encryptGcm(
                        plaintext = keyBytes,
                        alias = KeystoreCrypto.ALIAS_PREFS_KEY,
                        aad = null,
                        context = context
                    )

                    val typeStr: String
                    val valBytes: ByteArray
                    when (value) {
                        is String -> {
                            typeStr = "STR"
                            valBytes = value.toByteArray(StandardCharsets.UTF_8)
                        }
                        is Int -> {
                            typeStr = "INT"
                            valBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
                        }
                        is Long -> {
                            typeStr = "LNG"
                            valBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
                        }
                        is Float -> {
                            typeStr = "FLT"
                            valBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
                        }
                        is Boolean -> {
                            typeStr = "BOL"
                            valBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
                        }
                        is Set<*> -> {
                            typeStr = "SET"
                            val arr = JSONArray()
                            for (item in value) {
                                if (item is String) arr.put(item)
                            }
                            valBytes = arr.toString().toByteArray(StandardCharsets.UTF_8)
                        }
                        else -> continue
                    }

                    // AAD binding
                    val encVal = KeystoreCrypto.encryptGcm(
                        plaintext = valBytes,
                        alias = KeystoreCrypto.ALIAS_PREFS_KEY,
                        aad = keyBytes,
                        context = context
                    )

                    val entryObj = JSONObject().apply {
                        put("k", Base64.encodeToString(encKey, Base64.NO_WRAP))
                        put("v", Base64.encodeToString(encVal, Base64.NO_WRAP))
                        put("t", typeStr)
                    }
                    entries.put(hashedKey, entryObj)
                }

                root.put("entries", entries)
                val targetFile = getStorageFile()
                val dir = targetFile.parentFile ?: context.noBackupFilesDir
                val tmpFile = File(dir, "${targetFile.name}.tmp")
                val jsonBytes = root.toString(2).toByteArray(StandardCharsets.UTF_8)

                FileOutputStream(tmpFile).use { fos ->
                    fos.write(jsonBytes)
                    fos.flush()
                    fos.fd.sync()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    targetFile.writeBytes(jsonBytes)
                    tmpFile.delete()
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist secure preferences: ${t.message}", t)
                false
            }
        }
    }

    override fun contains(key: String): Boolean = dataMap.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun getAll(): Map<String, *> = HashMap(dataMap)

    override fun getString(key: String, defValue: String?): String? =
        dataMap[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (dataMap[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (dataMap[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (dataMap[key] as? Long) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (dataMap[key] as? Float) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (dataMap[key] as? Boolean) ?: defValue

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values?.toSet()
            removals.remove(key)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            pending.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            pending.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            val changedKeys = applyToMemory()
            val success = persistToDiskSync()
            notifyListeners(changedKeys)
            return success
        }

        override fun apply() {
            val changedKeys = applyToMemory()
            notifyListeners(changedKeys)
            diskExecutor.execute {
                persistToDiskSync()
            }
        }

        private fun applyToMemory(): Set<String> {
            val changedKeys = HashSet<String>()
            if (clearAll) {
                changedKeys.addAll(dataMap.keys)
                dataMap.clear()
            }
            for (k in removals) {
                if (dataMap.remove(k) != null) changedKeys.add(k)
            }
            for ((k, v) in pending) {
                if (v == null) {
                    if (dataMap.remove(k) != null) changedKeys.add(k)
                } else {
                    if (dataMap[k] != v) {
                        dataMap[k] = v
                        changedKeys.add(k)
                    }
                }
            }
            return changedKeys
        }

        private fun notifyListeners(changedKeys: Set<String>) {
            if (changedKeys.isEmpty()) return
            for (key in changedKeys) {
                for (listener in listeners) {
                    try {
                        listener.onSharedPreferenceChanged(this@SecurePreferences, key)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    companion object {
        private const val TAG = "SecurePreferences"
        const val DEFAULT_PREFS_FILE = "secure_browser_prefs.v2.json"

        @Volatile
        private var instance: SecurePreferences? = null

        fun getInstance(context: Context, fileName: String = DEFAULT_PREFS_FILE): SecurePreferences {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext ?: context
                    SecurePreferences(appContext, fileName).also { instance = it }
                }
            }
        }
    }
}
