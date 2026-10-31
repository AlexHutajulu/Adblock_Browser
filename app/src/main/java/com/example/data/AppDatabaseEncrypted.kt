package com.example.data

import android.content.Context
import android.util.Log
import com.example.manager.EncryptedPreferencesManager
import com.example.security.KeystoreCrypto
import java.io.File

class DatabaseKeyUnavailableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Manages the SQLCipher database encryption key using Keystore-backed envelope encryption.
 *
 * Security Architecture:
 * - DEK (Database Encryption Key) is wrapped with a non-exportable Keystore KEK (Key Encryption Key)
 *   and saved to noBackupFilesDir/db_key.v2.bin.
 * - Legacy passphrase in EncryptedPreferences is seamlessly wrapped without rekeying the database.
 * - Memory zeroization is supported for DEK bytes.
 * - Protection against key overwrite when database already exists on disk is strictly enforced.
 */
object AppDatabaseEncrypted {
    internal const val ENCRYPTED_DB_NAME = "alex_browser_database_encrypted"
    internal const val KEY_DB_PASSPHRASE = "db_passphrase"
    internal const val KEY_ENVELOPE_FILE = KeystoreCrypto.DB_KEY_FILE_NAME

    fun getDatabasePassphraseBytes(
        context: Context,
        encryptedDbExistsOverride: Boolean? = null
    ): ByteArray {
        val appContext = context.applicationContext ?: context
        val encryptedFile = appContext.getDatabasePath(ENCRYPTED_DB_NAME)
        val encryptedDbExists = encryptedDbExistsOverride
            ?: (encryptedFile.exists() && encryptedFile.length() > 0L)

        val prefs = try {
            EncryptedPreferencesManager.getPreferences(appContext)
        } catch (e: Exception) {
            throw DatabaseKeyUnavailableException("Tidak dapat mengakses penyimpanan kunci terenkripsi", e)
        }

        val stored = try {
            prefs.getString(KEY_DB_PASSPHRASE, null)?.trim().orEmpty()
        } catch (e: Exception) {
            throw DatabaseKeyUnavailableException("Gagal membaca passphrase database dari penyimpanan kunci", e)
        }

        // Try envelope unwrapping if Keystore is available
        val unwrappedDek = if (KeystoreCrypto.isKeystoreAvailable()) {
            KeystoreCrypto.unwrapDatabaseKey(appContext)
        } else {
            null
        }

        if (unwrappedDek != null && unwrappedDek.isNotEmpty()) {
            // Keep prefs in sync if missing
            if (stored.isEmpty()) {
                val hexString = unwrappedDek.toString(Charsets.UTF_8)
                try {
                    prefs.edit().putString(KEY_DB_PASSPHRASE, hexString).commit()
                } catch (_: Exception) {}
            }
            return unwrappedDek
        }

        if (stored.isNotEmpty()) {
            val passBytes = stored.toByteArray(Charsets.UTF_8)
            // Migrate to envelope encryption
            if (KeystoreCrypto.isKeystoreAvailable()) {
                val envelopeFile = File(appContext.noBackupFilesDir, KEY_ENVELOPE_FILE)
                if (!envelopeFile.exists()) {
                    KeystoreCrypto.wrapDatabaseKey(appContext, passBytes)
                }
            }
            return passBytes
        }

        // CRITICAL: If the encrypted database file already exists on disk, NEVER generate a new passphrase!
        if (encryptedDbExists) {
            Log.e(
                "AppDatabaseEncrypted",
                "Passphrase missing while encrypted database file already exists; refusing to overwrite key"
            )
            throw DatabaseKeyUnavailableException(
                "Passphrase untuk database terenkripsi yang sudah ada tidak ditemukan; kunci tidak ditimpa demi mencegah kerusakan data."
            )
        }

        val randomBytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(randomBytes)
        val newPassphrase = randomBytes.joinToString("") { "%02x".format(it) }
        val passBytes = newPassphrase.toByteArray(Charsets.UTF_8)

        // Envelope wrap via Keystore KEK if available
        if (KeystoreCrypto.isKeystoreAvailable()) {
            KeystoreCrypto.wrapDatabaseKey(appContext, passBytes)
        }

        val committed = prefs.edit().putString(KEY_DB_PASSPHRASE, newPassphrase).commit()
        val verified = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (!committed || verified != newPassphrase) {
            throw DatabaseKeyUnavailableException("Gagal menyimpan passphrase database baru secara persisten")
        }
        return passBytes
    }

    fun getDatabasePassphrase(
        context: Context,
        encryptedDbExistsOverride: Boolean? = null
    ): String {
        return getDatabasePassphraseBytes(context, encryptedDbExistsOverride).toString(Charsets.UTF_8)
    }
}
