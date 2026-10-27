package com.example.manager

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 3 Supported Language Options (Strictly 3 options per specification):
 * 1. Bahasa Indonesia (Default)
 * 2. English
 * 3. Default Sistem (System Default)
 */
enum class AppLanguage(val code: String, val displayNameRes: String) {
    INDONESIAN("id", "Bahasa Indonesia"),
    ENGLISH("en", "English"),
    SYSTEM("system", "Default Sistem (System Default)")
}

object AppLanguageManager {
    const val KEY_APP_LANGUAGE = "app_language_preference"

    /**
     * Applies per-app language via AndroidX AppCompatDelegate.
     */
    fun apply(lang: AppLanguage) {
        val locales = when (lang) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.INDONESIAN -> LocaleListCompat.forLanguageTags("id")
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
        }
        try {
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (_: Throwable) {
            // Safe fallback in unit test environments without a running AppCompat host
        }
    }

    /**
     * Resolves the effective Locale to apply:
     * - INDONESIAN -> Locale("id")
     * - ENGLISH -> Locale.ENGLISH ("en")
     * - SYSTEM:
     *     - System is id / in -> Locale("id")
     *     - System is en -> Locale.ENGLISH
     *     - System is any other language -> Locale("id") (strict fallback to Indonesian)
     */
    fun resolveEffectiveLocale(
        language: AppLanguage,
        systemLanguageOverride: String? = null
    ): Locale {
        return when (language) {
            AppLanguage.INDONESIAN -> Locale.forLanguageTag("id")
            AppLanguage.ENGLISH -> Locale.forLanguageTag("en")
            AppLanguage.SYSTEM -> {
                val sysLang = (systemLanguageOverride ?: getSystemLanguageCode()).lowercase()
                if (sysLang == "id" || sysLang == "in") {
                    Locale.forLanguageTag("id")
                } else if (sysLang.startsWith("en")) {
                    Locale.forLanguageTag("en")
                } else {
                    // Fallback to application default (Bahasa Indonesia) for any other language
                    Locale.forLanguageTag("id")
                }
            }
        }
    }

    /**
     * Reads the real device system language code without app-level overrides.
     */
    fun getSystemLanguageCode(): String {
        return try {
            val sysConfig = Resources.getSystem().configuration
            val locales = sysConfig.locales
            if (!locales.isEmpty) {
                locales[0].language
            } else {
                @Suppress("DEPRECATION")
                sysConfig.locale?.language ?: Locale.getDefault().language
            }
        } catch (_: Exception) {
            "id"
        }
    }

    /**
     * Creates a localized context configured with the resolved locale.
     */
    fun createLocalizedContext(baseContext: Context, language: AppLanguage): Context {
        val locale = resolveEffectiveLocale(language)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }
        return baseContext.createConfigurationContext(config)
    }

    /**
     * Applies the resolved locale to the context, application resources, and AppCompatDelegate.
     */
    fun applyLocale(context: Context, language: AppLanguage) {
        apply(language)
        val locale = resolveEffectiveLocale(language)
        Locale.setDefault(locale)
        
        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        val appCtx = context.applicationContext
        if (appCtx != null && appCtx != context) {
            val appRes = appCtx.resources
            val appConfig = Configuration(appRes.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appConfig.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                appConfig.setLocale(locale)
            }
            @Suppress("DEPRECATION")
            appRes.updateConfiguration(appConfig, appRes.displayMetrics)
        }
    }
}
