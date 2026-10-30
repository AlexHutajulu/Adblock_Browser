package com.example.data

import android.content.Context
import android.util.Log
import com.example.manager.EncryptedPreferencesManager

class DatabaseKeyUnavailableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Manages the SQLCipher database passphrase in encrypted preferences.
 *
 * Previously this class was annotated with `@Database` as a duplicate of
 * [AppDatabase] pointing to the same database file (`alex_browser_database_encrypted`).
 * The duplicate Room definition has been removed (audit M3) so only [AppDatabase]
 * owns the schema and database instance.
 */
object AppDatabaseEncrypted {
    internal const val ENCRYPTED_DB_NAME = "alex_browser_database_encrypted"
    internal const val KEY_DB_PASSPHRASE = "db_passphrase"

    fun getDatabasePassphrase(
        context: Context,
        encryptedDbExistsOverride: Boolean? = null
    ): String {
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

        if (stored.isNotEmpty()) {
            return stored
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

        val newPassphrase = java.util.UUID.randomUUID().toString() + "-" + java.util.UUID.randomUUID().toString()
        val committed = prefs.edit().putString(KEY_DB_PASSPHRASE, newPassphrase).commit()
        val verified = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (!committed || verified != newPassphrase) {
            throw DatabaseKeyUnavailableException("Gagal menyimpan passphrase database baru secara persisten")
        }
        return newPassphrase
    }
}
