package com.example.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.manager.EncryptedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [HistoryItem::class, BookmarkItem::class, DownloadItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val LEGACY_DB_NAME = "alex_browser_database"
        private const val ENCRYPTED_DB_NAME = "alex_browser_database_encrypted"
        private const val KEY_SQLCIPHER_MIGRATED = "db_migrated_to_sqlcipher_v1"

        @Volatile
        private var INSTANCE: AppDatabase? = null

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

        private fun isUnitTestEnvironment(): Boolean {
            val fp = Build.FINGERPRINT?.lowercase() ?: ""
            return fp.contains("robolectric")
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext ?: context
                    val instance = buildDatabaseWithOptionalEncryption(appContext)
                    INSTANCE = instance
                    instance
                }
            }
        }

        private fun buildDatabaseWithOptionalEncryption(appContext: Context): AppDatabase {
            if (!isUnitTestEnvironment()) {
                try {
                    System.loadLibrary("sqlcipher")
                    val passphrase = AppDatabaseEncrypted.getDatabasePassphrase(appContext)
                    val encryptedBuilder = Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        ENCRYPTED_DB_NAME
                    )
                        .addMigrations(MIGRATION_1_2)
                        .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray()))

                    if (com.example.BuildConfig.DEBUG && System.getenv("ALLOW_DESTRUCTIVE_MIGRATION") != null) {
                        encryptedBuilder.fallbackToDestructiveMigration(true)
                    }

                    val encryptedDb = encryptedBuilder.build()
                    migrateLegacyPlaintextDatabaseAsync(appContext, encryptedDb)
                    return encryptedDb
                } catch (t: Throwable) {
                    Log.w(TAG, "SQLCipher native runtime unavailable, falling back to app-private Room database: ${t.message}")
                }
            }

            val builder = Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                LEGACY_DB_NAME
            ).addMigrations(MIGRATION_1_2)

            if (com.example.BuildConfig.DEBUG && System.getenv("ALLOW_DESTRUCTIVE_MIGRATION") != null) {
                builder.fallbackToDestructiveMigration(true)
            }
            return builder.build()
        }

        private fun migrateLegacyPlaintextDatabaseAsync(appContext: Context, encryptedDb: AppDatabase) {
            if (EncryptedPreferencesManager.getBoolean(appContext, KEY_SQLCIPHER_MIGRATED, false)) return
            val legacyFile = appContext.getDatabasePath(LEGACY_DB_NAME)
            if (!legacyFile.exists()) {
                EncryptedPreferencesManager.putBoolean(appContext, KEY_SQLCIPHER_MIGRATED, true)
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                var legacyDb: AppDatabase? = null
                try {
                    legacyDb = Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        LEGACY_DB_NAME
                    ).addMigrations(MIGRATION_1_2).build()

                    val legacyHistory = legacyDb.historyDao().getAllHistory().first()
                    legacyHistory.forEach { encryptedDb.historyDao().insertHistory(it) }

                    val legacyBookmarks = legacyDb.bookmarkDao().getAllBookmarks().first()
                    legacyBookmarks.forEach { encryptedDb.bookmarkDao().insertBookmark(it) }

                    val legacyDownloads = legacyDb.downloadDao().getNonIncognitoDownloads().first()
                    legacyDownloads.forEach { encryptedDb.downloadDao().insertOrUpdate(it) }

                    legacyDb.close()
                    legacyDb = null
                    appContext.deleteDatabase(LEGACY_DB_NAME)
                    EncryptedPreferencesManager.putBoolean(appContext, KEY_SQLCIPHER_MIGRATED, true)
                    Log.i(TAG, "Successfully migrated legacy plaintext database into SQLCipher encrypted database")
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy database migration warning: ${e.message}")
                } finally {
                    try { legacyDb?.close() } catch (_: Exception) {}
                }
            }
        }
    }
}
