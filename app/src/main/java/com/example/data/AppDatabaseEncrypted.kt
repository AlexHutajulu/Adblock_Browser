package com.example.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.manager.EncryptedPreferencesManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Secure Room Database with SQLCipher encryption.
 *
 * AUDIT FIX: CWE-313 - Cleartext Storage in Database
 * Severity: CRITICAL
 * Provides AES256 encrypted storage for history, bookmarks, and downloads using SQLCipher.
 */
@Database(entities = [HistoryItem::class, BookmarkItem::class, DownloadItem::class], version = 2, exportSchema = false)
abstract class AppDatabaseEncrypted : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabaseEncrypted? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`id` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`domain` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`totalBytes` INTEGER NOT NULL, " +
                        "`downloadedBytes` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`fileUri` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER NOT NULL, " +
                        "`failureReason` TEXT NOT NULL, " +
                        "`isIncognito` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Get or create encrypted database instance
         * Uses SQLCipher SupportOpenHelperFactory for 256-bit AES database encryption.
         */
        fun getDatabase(context: Context): AppDatabaseEncrypted {
            return INSTANCE ?: synchronized(this) {
                try {
                    val passphrase = getDatabasePassphrase(context)
                    
                    val builder = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabaseEncrypted::class.java,
                        "alex_browser_database_encrypted"
                    ).addMigrations(MIGRATION_1_2)

                    // Use official SQLCipher SupportOpenHelperFactory
                    builder.openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray()))

                    if (com.example.BuildConfig.DEBUG && 
                        System.getenv("ALLOW_DESTRUCTIVE_MIGRATION") != null) {
                        builder.fallbackToDestructiveMigration(true)
                        Log.w("AppDatabaseEncrypted", "WARNING: Destructive migration allowed in DEBUG mode")
                    }

                    val instance = builder.build()
                    INSTANCE = instance
                    Log.i("AppDatabaseEncrypted", "Database initialized with SQLCipher encryption")
                    instance
                } catch (e: Exception) {
                    Log.e("AppDatabaseEncrypted", "Failed to initialize encrypted database", e)
                    throw RuntimeException("Critical: Database encryption failed", e)
                }
            }
        }

        /**
         * Generate or retrieve secure passphrase for database encryption.
         * Stored securely via EncryptedPreferencesManager.
         */
        fun getDatabasePassphrase(context: Context): String {
            return try {
                val stored = EncryptedPreferencesManager.getString(context, "db_passphrase", "") ?: ""
                if (stored.isNotEmpty()) {
                    stored
                } else {
                    val newPassphrase = java.util.UUID.randomUUID().toString()
                    EncryptedPreferencesManager.putString(context, "db_passphrase", newPassphrase)
                    newPassphrase
                }
            } catch (e: Exception) {
                Log.e("AppDatabaseEncrypted", "Error getting database passphrase", e)
                throw RuntimeException("Critical: Cannot obtain database encryption key", e)
            }
        }
    }
}
