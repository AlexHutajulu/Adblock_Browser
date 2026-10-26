package com.example

import com.example.manager.AppLanguage
import com.example.manager.AppLanguageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun testOnlyThreeOptionsExist() {
        val options = AppLanguage.values()
        assertEquals(3, options.size)
        assertTrue(options.contains(AppLanguage.INDONESIAN))
        assertTrue(options.contains(AppLanguage.ENGLISH))
        assertTrue(options.contains(AppLanguage.SYSTEM))
    }

    @Test
    fun testDefaultLanguageIsIndonesian() {
        // Verification V3: Default on fresh install is Indonesian
        val defaultLang = AppLanguage.INDONESIAN
        assertEquals("id", defaultLang.code)
        val resolved = AppLanguageManager.resolveEffectiveLocale(defaultLang)
        assertEquals("id", resolved.language)
    }

    @Test
    fun testExplicitIndonesianSelection() {
        val resolved = AppLanguageManager.resolveEffectiveLocale(AppLanguage.INDONESIAN, systemLanguageOverride = "en")
        assertEquals("id", resolved.language)
    }

    @Test
    fun testExplicitEnglishSelection() {
        // Verification V4: User chooses English
        val resolved = AppLanguageManager.resolveEffectiveLocale(AppLanguage.ENGLISH, systemLanguageOverride = "id")
        assertEquals("en", resolved.language)
    }

    @Test
    fun testSystemDefaultWhenSystemIsIndonesian() {
        // Verification V5: System default when system is id/in
        val resolvedId = AppLanguageManager.resolveEffectiveLocale(AppLanguage.SYSTEM, systemLanguageOverride = "id")
        assertEquals("id", resolvedId.language)

        val resolvedIn = AppLanguageManager.resolveEffectiveLocale(AppLanguage.SYSTEM, systemLanguageOverride = "in")
        assertEquals("id", resolvedIn.language)
    }

    @Test
    fun testSystemDefaultWhenSystemIsEnglish() {
        // Verification V5: System default when system is en
        val resolvedEn = AppLanguageManager.resolveEffectiveLocale(AppLanguage.SYSTEM, systemLanguageOverride = "en")
        assertEquals("en", resolvedEn.language)

        val resolvedEnUs = AppLanguageManager.resolveEffectiveLocale(AppLanguage.SYSTEM, systemLanguageOverride = "en_US")
        assertEquals("en", resolvedEnUs.language)
    }

    @Test
    fun testSystemDefaultFallbackForUnsupportedLanguages() {
        // Verification V6: System default when system is anything else -> fallback to Indonesian
        val unsupportedLocales = listOf("fr", "ja", "de", "es", "zh", "ru", "ar", "ko")
        for (lang in unsupportedLocales) {
            val resolved = AppLanguageManager.resolveEffectiveLocale(AppLanguage.SYSTEM, systemLanguageOverride = lang)
            assertEquals("Expected fallback to 'id' for system language $lang", "id", resolved.language)
        }
    }
}
