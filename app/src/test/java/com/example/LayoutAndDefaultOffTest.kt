package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.manager.AbpRuleEngine
import com.example.manager.BrowserExtension
import com.example.manager.BrowserExtensionManager
import com.example.manager.CosmeticFilterInjector
import com.example.manager.CustomJsToolbarManager
import com.example.manager.CustomToolbarScript
import com.example.manager.EncryptedPreferencesManager
import com.example.manager.ExtensionSourceType
import com.yourbrowser.adblock.HeuristicsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LayoutAndDefaultOffTest {

    @Test
    fun testUnsafeCosmeticSelectors() {
        assertFalse(CosmeticFilterInjector.isSafeCssSelector(".container"))
        assertFalse(CosmeticFilterInjector.isSafeCssSelector("main"))
    }

    @Test
    fun testHeuristicsReasonSafePatterns() {
        // cdn.123bank.co.id must not be blocked by short gambling pattern "123b"
        assertNull(
            HeuristicsEngine.heuristicReason(
                "cdn.123bank.co.id",
                "https://cdn.123bank.co.id/js/app.js",
                false
            )
        )

        // cdn.jsdelivr.net tag.min.js must not be blocked
        assertNull(
            HeuristicsEngine.heuristicReason(
                "cdn.jsdelivr.net",
                "https://cdn.jsdelivr.net/npm/lib/tag.min.js",
                false
            )
        )

        // First-party generic path must not be blocked
        assertNull(
            HeuristicsEngine.heuristicReason(
                "cdn.shop.example",
                "https://cdn.shop.example/a/bn.js",
                true
            )
        )
    }

    @Test
    fun testGlobalCosmeticException() {
        // Rule "#@#.x" must make ".x" not appear in selectorsFor("contoh.com")
        val rules = sequenceOf("##.x", "#@#.x")
        val parsed = AbpRuleEngine.build(rules)
        val selectors = parsed.cosmetic.selectorsFor("contoh.com")
        assertFalse(
            "Selector .x should be exempted globally by #@#.x",
            selectors.contains(".x")
        )
    }

    @Test
    fun testDefaultOffForBuiltInExtensionsAndTemplates() {
        assertTrue(
            "All BUILT_IN_EXTENSIONS must have isEnabled == false",
            BrowserExtensionManager.BUILT_IN_EXTENSIONS.all { !it.isEnabled }
        )

        assertTrue(
            "All TEMPLATE_GALLERY scripts must have isEnabled == false",
            CustomJsToolbarManager.TEMPLATE_GALLERY.all { !it.isEnabled }
        )
    }

    @Test
    fun testMigrationForExtensions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = EncryptedPreferencesManager.getPreferences(context)
        prefs.edit().remove("builtin_extensions_default_off_v2").apply()

        val oldExtensions = listOf(
            BrowserExtension(
                id = "ext_sponsor_skip",
                name = "SponsorBlock & Auto-Skip Video",
                sourceType = ExtensionSourceType.BUILT_IN,
                isEnabled = true
            ),
            BrowserExtension(
                id = "custom_user_extension_1",
                name = "User Custom Script",
                jsCode = "// user custom script",
                sourceType = ExtensionSourceType.CUSTOM_CODE,
                isEnabled = true
            )
        )
        BrowserExtensionManager.saveExtensions(context, oldExtensions)

        val migrated = BrowserExtensionManager.loadExtensions(context)
        val builtIn = migrated.firstOrNull { it.id == "ext_sponsor_skip" }
        val userExt = migrated.firstOrNull { it.id == "custom_user_extension_1" }

        assertTrue("Built-in extension should exist", builtIn != null)
        assertFalse("Built-in extension should be migrated to isEnabled == false", builtIn!!.isEnabled)

        assertTrue("User extension should exist", userExt != null)
        assertTrue("User extension should keep its isEnabled == true state", userExt!!.isEnabled)
    }

    @Test
    fun testMigrationForScripts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = EncryptedPreferencesManager.getPreferences(context)
        prefs.edit().remove("templates_default_off_v2").apply()

        val oldScripts = listOf(
            CustomToolbarScript(
                id = "tpl_smart_dark_mode",
                name = "Dark Mode",
                jsCode = "// code",
                isEnabled = true,
                isBuiltInTemplate = true
            ),
            CustomToolbarScript(
                id = "custom_user_script_1",
                name = "User Custom JS",
                jsCode = "// user code",
                isEnabled = true,
                isBuiltInTemplate = false
            )
        )
        CustomJsToolbarManager.saveScripts(context, oldScripts)

        val migrated = CustomJsToolbarManager.loadScripts(context)
        val builtInTpl = migrated.firstOrNull { it.id == "tpl_smart_dark_mode" }
        val userScript = migrated.firstOrNull { it.id == "custom_user_script_1" }

        assertTrue("Built-in template should exist", builtInTpl != null)
        assertFalse("Built-in template should be migrated to isEnabled == false", builtInTpl!!.isEnabled)

        assertTrue("User script should exist", userScript != null)
        assertTrue("User script should keep its isEnabled == true state", userScript!!.isEnabled)
    }

    @Test
    fun testMigrationForShortcuts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = EncryptedPreferencesManager.getPreferences(context)
        prefs.edit()
            .remove("shortcuts_default_off_v2")
            .putStringSet("enabled_builtin_shortcuts", setOf("shortcut_history", "shortcut_bookmarks"))
            .apply()

        val shortcuts = CustomJsToolbarManager.getEnabledBuiltInShortcuts(context)
        assertTrue(
            "Shortcuts should be migrated to emptySet() on default off migration",
            shortcuts.isEmpty()
        )
    }

    @Test
    fun testB1CosmeticFilterInjectorRejectsWeakAttributeSubstring() {
        assertFalse(
            "div[id*=\"ad\"] must be rejected because 'ad' is < 6 chars and matches 'header'",
            CosmeticFilterInjector.isSafeCssSelector("div[id*=\"ad\"]")
        )
        assertFalse(
            "a[href*=\"ad\"] must be rejected because 'ad' is < 6 chars and matches 'download'",
            CosmeticFilterInjector.isSafeCssSelector("a[href*=\"ad\"]")
        )
        assertFalse(
            "div[class*=\"header-banner\"] must be rejected because it contains weak word 'head'",
            CosmeticFilterInjector.isSafeCssSelector("div[class*=\"header-banner\"]")
        )
        assertTrue(
            "div[id*='adblock-warning'] should remain valid",
            CosmeticFilterInjector.isSafeCssSelector("div[id*='adblock-warning']")
        )
    }

    @Test
    fun testB2AbpRuleEngineFirstPartyUnanchoredRuleDoesNotBlockCdn() {
        val rules = sequenceOf("/ads/banner.js", "||doubleclick.net^")
        val parsed = AbpRuleEngine.build(rules)
        // First-party request to unanchored rule should NOT be blocked (returns null)
        assertNull(
            "1st-party request should not be blocked by unanchored substring rule",
            parsed.shouldBlock(
                url = "https://cdn.toko.id/ads/banner.js",
                sourceHost = "www.toko.id",
                resourceType = AbpRuleEngine.ResourceType.SCRIPT
            )
        )
        // 3rd-party request to anchored rule should still be blocked (returns non-null MatchResult)
        assertTrue(
            "3rd-party request to doubleclick.net should still be blocked",
            parsed.shouldBlock(
                url = "https://ad.doubleclick.net/ads/banner.js",
                sourceHost = "www.toko.id",
                resourceType = AbpRuleEngine.ResourceType.SCRIPT
            ) != null
        )
    }

    @Test
    fun testB3HeuristicsEngineDoesNotBlockGenericPathsOrStylesheets() {
        // Generic paths like /pop.js, /banner.js, /adv/ must not be in PATH_PATTERNS
        assertNull(
            HeuristicsEngine.heuristicReason(
                "cdn.BootstrapSite.com",
                "https://cdn.bootstrapsite.com/js/pop.js",
                true
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                "assets.example.org",
                "https://assets.example.org/banner.js",
                true
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                "static.example.org",
                "https://static.example.org/adv/style.css",
                true
            )
        )
    }
}

