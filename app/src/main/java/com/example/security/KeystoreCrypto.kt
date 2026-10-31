package com.example.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Advanced Hardware-Backed Cryptography Engine for AlexBrowser.
 *
 * Implements:
 * 1. Non-exportable AES-256-GCM authenticated encryption in Android Keystore with StrongBox support.
 * 2. Authenticated Additional Data (AAD) binding to prevent ciphertext swap attacks.
 * 3. Envelope Encryption for SQLCipher Database Encryption Keys (DEK wrapped by Keystore KEK).
 * 4. HMAC-SHA256 integrity tags for stored extensions and Custom JS toolbar scripts.
 * 5. Deterministic fallback for environments without hardware Keystore (e.g. Robolectric / unit tests).
 */
object KeystoreCrypto {
    private const val TAG = "KeystoreCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    // Keystore Key Aliases
    const val ALIAS_PREFS_KEY = "alexbrowser_prefs_aes_v2"
    const val ALIAS_PREFS_HMAC = "alexbrowser_prefs_hmac_v2"
    const val ALIAS_DB_KEK = "alexbrowser_db_kek_v1"
    const val ALIAS_INTEGRITY_HMAC = "alexbrowser_integrity_hmac_v1"

    private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_IV_LENGTH_BYTES = 12
    const val ENVELOPE_VERSION_BYTE: Byte = 0x01

    const val DB_KEY_FILE_NAME = "db_key.v2.bin"
    private val DB_KEY_AAD = "alexbrowser_sqlcipher_dek_envelope_v1".toByteArray(Charsets.UTF_8)

    // In-memory fallback key for environments where AndroidKeyStore is absent (e.g. Robolectric JVM tests)
    @Volatile
    private var softwareFallbackAesKey: SecretKey? = null
    @Volatile
    private var softwareFallbackHmacKey: SecretKey? = null

    fun isKeystoreAvailable(): Boolean {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun getOrCreateSoftwareAesKey(): SecretKey {
        return softwareFallbackAesKey ?: synchronized(this) {
            softwareFallbackAesKey ?: run {
                val keyBytes = ByteArray(32)
                SecureRandom().nextBytes(keyBytes)
                SecretKeySpec(keyBytes, "AES").also { softwareFallbackAesKey = it }
            }
        }
    }

    private fun getOrCreateSoftwareHmacKey(): SecretKey {
        return softwareFallbackHmacKey ?: synchronized(this) {
            softwareFallbackHmacKey ?: run {
                val keyBytes = ByteArray(32)
                SecureRandom().nextBytes(keyBytes)
                SecretKeySpec(keyBytes, HMAC_SHA256).also { softwareFallbackHmacKey = it }
            }
        }
    }

    private fun getOrCreateAesKey(alias: String, context: Context? = null): SecretKey {
        if (!isKeystoreAvailable()) {
            return getOrCreateSoftwareAesKey()
        }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val existing = keyStore.getKey(alias, null) as? SecretKey
            if (existing != null) return existing
        }

        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context != null) {
            try {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        // Attempt StrongBox first if supported (API 28+), fall back to standard TEE AndroidKeyStore
        if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateAesKey(alias, useStrongBox = true)
            } catch (t: Throwable) {
                Log.w(TAG, "StrongBox keygen failed for alias=$alias, falling back to TEE: ${t.message}")
            }
        }

        return try {
            generateAesKey(alias, useStrongBox = false)
        } catch (t: Throwable) {
            Log.e(TAG, "TEE keygen failed for alias=$alias: ${t.message}", t)
            getOrCreateSoftwareAesKey()
        }
    }

    private fun generateAesKey(alias: String, useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun getOrCreateHmacKey(alias: String, context: Context? = null): SecretKey {
        if (!isKeystoreAvailable()) {
            return getOrCreateSoftwareHmacKey()
        }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val existing = keyStore.getKey(alias, null) as? SecretKey
            if (existing != null) return existing
        }

        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context != null) {
            try {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateHmacKey(alias, useStrongBox = true)
            } catch (t: Throwable) {
                Log.w(TAG, "StrongBox HMAC keygen failed for alias=$alias: ${t.message}")
            }
        }

        return try {
            generateHmacKey(alias, useStrongBox = false)
        } catch (t: Throwable) {
            Log.e(TAG, "TEE HMAC keygen failed for alias=$alias: ${t.message}", t)
            getOrCreateSoftwareHmacKey()
        }
    }

    private fun generateHmacKey(alias: String, useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts [plaintext] using AES-256-GCM with a fresh 96-bit random IV and optional [aad] binding.
     * Returns binary envelope: `[version (1 byte) | IV (12 bytes) | ciphertext + 16-byte GCM tag]`.
     */
    fun encryptGcm(
        plaintext: ByteArray,
        alias: String = ALIAS_PREFS_KEY,
        aad: ByteArray? = null,
        context: Context? = null
    ): ByteArray {
        val key = getOrCreateAesKey(alias, context)
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher failed to generate IV")
        if (iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("Invalid IV length: ${iv.size}")
        }

        val envelope = ByteArray(1 + GCM_IV_LENGTH_BYTES + ciphertext.size)
        envelope[0] = ENVELOPE_VERSION_BYTE
        System.arraycopy(iv, 0, envelope, 1, GCM_IV_LENGTH_BYTES)
        System.arraycopy(ciphertext, 0, envelope, 1 + GCM_IV_LENGTH_BYTES, ciphertext.size)
        return envelope
    }

    /**
     * Decrypts binary envelope `[version (1 byte) | IV (12 bytes) | ciphertext + 16-byte GCM tag]`
     * and authenticates [aad] if provided. Throws GeneralSecurityException if ciphertext or AAD has been tampered with.
     */
    fun decryptGcm(
        envelope: ByteArray,
        alias: String = ALIAS_PREFS_KEY,
        aad: ByteArray? = null,
        context: Context? = null
    ): ByteArray {
        if (envelope.size < 1 + GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            throw IllegalArgumentException("Envelope too short: ${envelope.size}")
        }
        val version = envelope[0]
        if (version != ENVELOPE_VERSION_BYTE) {
            throw IllegalArgumentException("Unsupported envelope version: $version")
        }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(envelope, 1, iv, 0, GCM_IV_LENGTH_BYTES)
        val ciphertextLength = envelope.size - (1 + GCM_IV_LENGTH_BYTES)
        val ciphertext = ByteArray(ciphertextLength)
        System.arraycopy(envelope, 1 + GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertextLength)

        val key = getOrCreateAesKey(alias, context)
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    /**
     * Alias for [encryptGcm] for semantic clarity.
     */
    fun encryptWithKeystore(
        plaintext: ByteArray,
        alias: String = ALIAS_PREFS_KEY,
        aad: ByteArray? = null,
        context: Context? = null
    ): ByteArray = encryptGcm(plaintext, alias, aad, context)

    /**
     * Alias for [decryptGcm] for semantic clarity.
     */
    fun decryptWithKeystore(
        envelope: ByteArray,
        alias: String = ALIAS_PREFS_KEY,
        aad: ByteArray? = null,
        context: Context? = null
    ): ByteArray = decryptGcm(envelope, alias, aad, context)

    /**
     * Computes HMAC-SHA256 tag of [data] using Android Keystore key.
     */
    fun computeHmacSha256(
        data: ByteArray,
        alias: String = ALIAS_INTEGRITY_HMAC,
        context: Context? = null
    ): String? {
        return try {
            val key = getOrCreateHmacKey(alias, context)
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(key)
            val tag = mac.doFinal(data)
            Base64.encodeToString(tag, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "HMAC computation failed for alias=$alias: ${t.message}", t)
            null
        }
    }

    /**
     * Verifies HMAC-SHA256 tag in constant time.
     */
    fun verifyHmacSha256(
        data: ByteArray,
        expectedMacBase64: String,
        alias: String = ALIAS_INTEGRITY_HMAC,
        context: Context? = null
    ): Boolean {
        val computed = computeHmacSha256(data, alias, context) ?: return false
        val computedBytes = Base64.decode(computed, Base64.NO_WRAP)
        val expectedBytes = try {
            Base64.decode(expectedMacBase64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        return MessageDigest.isEqual(computedBytes, expectedBytes)
    }

    /**
     * Obfuscates preference keys by deriving a keyed hash (HMAC-SHA256).
     * Prevents preference names from being visible in disk files.
     */
    fun hashPreferenceKey(key: String, context: Context? = null): String {
        val mac = computeHmacSha256(key.toByteArray(Charsets.UTF_8), ALIAS_PREFS_HMAC, context)
        return if (!mac.isNullOrBlank()) {
            mac.replace('/', '_').replace('+', '-').trimEnd('=')
        } else {
            "k_" + MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(32)
        }
    }

    /**
     * Wraps the SQLCipher Database Encryption Key (DEK) with the Keystore KEK and saves it to noBackupFilesDir atomically.
     */
    fun wrapDatabaseKey(context: Context, dekBytes: ByteArray): Boolean {
        return try {
            val envelope = encryptGcm(
                plaintext = dekBytes,
                alias = ALIAS_DB_KEK,
                aad = DB_KEY_AAD,
                context = context
            )
            val dir = context.noBackupFilesDir
            if (!dir.exists()) dir.mkdirs()
            val targetFile = File(dir, DB_KEY_FILE_NAME)
            val tmpFile = File(dir, "$DB_KEY_FILE_NAME.tmp")
            FileOutputStream(tmpFile).use { fos ->
                fos.write(envelope)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.writeBytes(envelope)
                tmpFile.delete()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to wrap database key: ${t.message}", t)
            false
        }
    }

    /**
     * Unwraps the SQLCipher Database Encryption Key (DEK) from noBackupFilesDir using the Keystore KEK.
     */
    fun unwrapDatabaseKey(context: Context): ByteArray? {
        return try {
            val targetFile = File(context.noBackupFilesDir, DB_KEY_FILE_NAME)
            if (!targetFile.exists() || targetFile.length() <= 0L) return null
            val envelope = targetFile.readBytes()
            decryptGcm(
                envelope = envelope,
                alias = ALIAS_DB_KEK,
                aad = DB_KEY_AAD,
                context = context
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to unwrap database key: ${t.message}", t)
            null
        }
    }

    /**
     * Computes HMAC-SHA256 integrity tag for extensions or Custom JS.
     */
    fun computeIntegrityMac(data: ByteArray, context: Context? = null): String? {
        return computeHmacSha256(data, ALIAS_INTEGRITY_HMAC, context)
    }

    /**
     * Verifies HMAC-SHA256 integrity tag in constant time.
     */
    fun verifyIntegrityMac(data: ByteArray, expectedMac: String, context: Context? = null): Boolean {
        return verifyHmacSha256(data, expectedMac, ALIAS_INTEGRITY_HMAC, context)
    }
}
