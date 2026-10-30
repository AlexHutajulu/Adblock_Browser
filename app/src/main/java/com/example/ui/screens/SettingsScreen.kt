package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.manager.AdBlockMode
import com.example.manager.AppLanguage
import com.example.manager.AppTheme
import com.example.manager.DisplayMode
import com.example.manager.PrivacyShieldMode
import com.example.ui.components.DeveloperAiAttributionSection
import com.example.ui.components.SettingsDivider
import com.example.ui.components.SettingsNavRow
import com.example.ui.components.SettingsRangeSliderRow
import com.example.ui.components.SettingsSection
import com.example.ui.components.SettingsSliderRow
import com.example.ui.components.SettingsSwitchRow
import com.example.ui.theme.LiquidGlassRadioButton
import com.example.ui.theme.interactiveLiquidGlass
import com.example.ui.viewmodel.BrowserViewModel

enum class SettingsCategory(val titleRes: Int, val icon: ImageVector) {
    FEATURES(R.string.settings_cat_features, Icons.Outlined.Widgets),
    GENERAL(R.string.settings_cat_general, Icons.Outlined.Tune),
    APPEARANCE(R.string.settings_cat_appearance, Icons.Outlined.Palette),
    PASSWORDS_AUTOFILL(R.string.settings_cat_passwords, Icons.Outlined.Password),
    ABOUT(R.string.settings_cat_about, Icons.Outlined.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onBack)

    val currentEngine by viewModel.selectedSearchEngine.collectAsState()
    val adBlockMode by viewModel.adBlockMode.collectAsState()
    val isTrackerEnabled by viewModel.trackerBlockEnabled.collectAsState()
    val isAdguardDnsLayerEnabled by viewModel.adguardDnsLayerEnabled.collectAsState()
    val currentTheme by viewModel.appTheme.collectAsState()
    val currentMode by viewModel.displayMode.collectAsState()
    val liquidGlassEnabled by viewModel.liquidGlassEnabled.collectAsState()
    val isInspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()
    val allowScreenRecording by viewModel.allowScreenRecording.collectAsState()
    val isStreamingBoostEnabled by viewModel.streamingBoostEnabled.collectAsState()
    val downloadAcceleration by viewModel.downloadAcceleration.collectAsState()
    val isDataSaverEnabled by viewModel.dataSaverEnabled.collectAsState()
    val isBackgroundPlaybackEnabled by viewModel.backgroundPlaybackEnabled.collectAsState()
    val openPopupsInNewTab by viewModel.openPopupsInNewTab.collectAsState()
    val blockSuspiciousRedirects by viewModel.blockSuspiciousRedirects.collectAsState()

    val privacyShieldMode by viewModel.privacyShieldMode.collectAsState()
    val preventWebRtcLeak by viewModel.preventWebRtcLeak.collectAsState()
    val strictWebRtcMode by viewModel.strictWebRtcMode.collectAsState()
    val clearCookiesOnExit by viewModel.clearCookiesOnExit.collectAsState()
    val forceWebPageDarkMode by viewModel.forceWebPageDarkMode.collectAsState()
    val currentLanguage by viewModel.appLanguage.collectAsState()

    val isSavePasswordsEnabled by viewModel.isSavePasswordsEnabled.collectAsState()
    val isAutofillEnabled by viewModel.isAutofillEnabled.collectAsState()
    val blockClipboardHijack by viewModel.blockClipboardHijack.collectAsState()
    val httpsOnlyMode by viewModel.httpsOnlyMode.collectAsState()
    val phishingProtectionEnabled by viewModel.phishingProtectionEnabled.collectAsState()
    val blockCameraMicStrict by viewModel.blockCameraMicStrict.collectAsState()

    var selectedCategory by remember { mutableStateOf(SettingsCategory.FEATURES) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDisplayModeDialog by remember { mutableStateOf(false) }
    var showPrivacyShieldDialog by remember { mutableStateOf(false) }
    var showSitePermissions by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAdBlockModeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showDownloadThreadsDialog by remember { mutableStateOf(false) }

    if (showSitePermissions) {
        SitePermissionsDialog(viewModel = viewModel, onDismiss = { showSitePermissions = false })
    }

    if (showThemeDialog) {
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_theme_title),
            options = listOf(
                AppTheme.SYSTEM to stringResource(R.string.theme_system),
                AppTheme.LIGHT to stringResource(R.string.theme_light),
                AppTheme.DARK to stringResource(R.string.theme_dark)
            ),
            selected = currentTheme,
            onSelect = {
                viewModel.setTheme(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showDisplayModeDialog) {
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_display_mode_title),
            options = listOf(
                DisplayMode.MOBILE to stringResource(R.string.mode_mobile),
                DisplayMode.DESKTOP to stringResource(R.string.mode_desktop)
            ),
            selected = currentMode,
            onSelect = {
                viewModel.setDisplayMode(it)
                showDisplayModeDialog = false
            },
            onDismiss = { showDisplayModeDialog = false }
        )
    }

    if (showPrivacyShieldDialog) {
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_privacy_shield_title),
            options = listOf(
                PrivacyShieldMode.STANDARD to stringResource(R.string.privacy_shield_standard),
                PrivacyShieldMode.STRICT to stringResource(R.string.privacy_shield_strict)
            ),
            selected = privacyShieldMode,
            onSelect = {
                viewModel.setPrivacyShieldMode(it)
                showPrivacyShieldDialog = false
            },
            onDismiss = { showPrivacyShieldDialog = false }
        )
    }

    if (showAdBlockModeDialog) {
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_adblock_title),
            options = listOf(
                AdBlockMode.OFF to stringResource(R.string.adblock_off),
                AdBlockMode.BALANCED to stringResource(R.string.adblock_balanced),
                AdBlockMode.AGGRESSIVE to stringResource(R.string.adblock_aggressive)
            ),
            selected = adBlockMode,
            onSelect = {
                viewModel.setAdBlockMode(it)
                showAdBlockModeDialog = false
            },
            onDismiss = { showAdBlockModeDialog = false }
        )
    }

    if (showLanguageDialog) {
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_language_title),
            options = listOf(
                AppLanguage.SYSTEM to stringResource(R.string.lang_system),
                AppLanguage.INDONESIAN to stringResource(R.string.lang_indonesian),
                AppLanguage.ENGLISH to stringResource(R.string.lang_english)
            ),
            selected = currentLanguage,
            onSelect = { lang ->
                viewModel.setAppLanguage(lang)
                showLanguageDialog = false
                val langLabel = when (lang) {
                    AppLanguage.INDONESIAN -> context.getString(R.string.lang_indonesian)
                    AppLanguage.ENGLISH -> context.getString(R.string.lang_english)
                    AppLanguage.SYSTEM -> context.getString(R.string.lang_system)
                }
                Toast.makeText(context, context.getString(R.string.lang_changed_toast, langLabel), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showSearchEngineDialog) {
        val engines = remember { com.example.manager.SearchEngine.getAllEngines() }
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_search_engine_title),
            options = engines.map { it.engineName to it.engineName },
            selected = currentEngine,
            onSelect = { engine ->
                viewModel.setSearchEngine(engine)
                showSearchEngineDialog = false
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_default_engine_toast, engine),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { showSearchEngineDialog = false }
        )
    }

    if (showDownloadThreadsDialog) {
        val threadOptions = listOf("1", "4", "8", "16")
        SingleChoiceSelectionDialog(
            title = stringResource(R.string.pref_download_accel_title),
            options = threadOptions.map { it to "${it}x" },
            selected = downloadAcceleration,
            onSelect = { threads ->
                viewModel.setDownloadAcceleration(threads)
                showDownloadThreadsDialog = false
            },
            onDismiss = { showDownloadThreadsDialog = false }
        )
    }

    if (showClearDataDialog) {
        ClearBrowsingDataDialog(
            onClearCacheOnly = {
                viewModel.clearBrowserCacheOnly(context)
                Toast.makeText(context, context.getString(R.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                showClearDataDialog = false
            },
            onClearAllData = {
                viewModel.clearCacheAndCookies(context, closeTabs = false)
                Toast.makeText(context, context.getString(R.string.toast_all_data_cleared), Toast.LENGTH_SHORT).show()
                showClearDataDialog = false
            },
            onClearHistory = {
                viewModel.clearAllHistory()
                Toast.makeText(context, context.getString(R.string.toast_history_cleared), Toast.LENGTH_SHORT).show()
                showClearDataDialog = false
            },
            onClearBookmarks = {
                viewModel.clearAllBookmarks()
                Toast.makeText(context, context.getString(R.string.toast_bookmarks_cleared), Toast.LENGTH_SHORT).show()
                showClearDataDialog = false
            },
            onDismiss = { showClearDataDialog = false }
        )
    }

    val themeLabel = when (currentTheme) {
        AppTheme.SYSTEM -> stringResource(R.string.theme_system)
        AppTheme.LIGHT -> stringResource(R.string.theme_light)
        AppTheme.DARK -> stringResource(R.string.theme_dark)
    }
    val displayModeLabel = when (currentMode) {
        DisplayMode.MOBILE -> stringResource(R.string.mode_mobile)
        DisplayMode.DESKTOP -> stringResource(R.string.mode_desktop)
    }
    val privacyShieldLabel = when (privacyShieldMode) {
        PrivacyShieldMode.STANDARD -> stringResource(R.string.privacy_shield_standard)
        PrivacyShieldMode.STRICT -> stringResource(R.string.privacy_shield_strict)
    }
    val adBlockLabel = when (adBlockMode) {
        AdBlockMode.OFF -> stringResource(R.string.adblock_off)
        AdBlockMode.BALANCED -> stringResource(R.string.adblock_balanced)
        AdBlockMode.AGGRESSIVE -> stringResource(R.string.adblock_aggressive)
    }
    val languageLabel = when (currentLanguage) {
        AppLanguage.INDONESIAN -> stringResource(R.string.lang_indonesian)
        AppLanguage.ENGLISH -> stringResource(R.string.lang_english)
        AppLanguage.SYSTEM -> stringResource(R.string.lang_system)
    }

    @Composable
    fun renderFeaturesSection() {
        // 1. AdBlock & Kontrol Konten
        SettingsSection(title = stringResource(R.string.settings_section_adblock)) {
            SettingsNavRow(
                title = stringResource(R.string.pref_adblock_title),
                value = adBlockLabel,
                icon = Icons.Outlined.Shield,
                onClick = { showAdBlockModeDialog = true }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_tracker_title),
                subtitle = stringResource(R.string.pref_tracker_summary),
                icon = Icons.Outlined.TrackChanges,
                checked = isTrackerEnabled,
                onCheckedChange = { viewModel.setTrackerBlockEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_redirects_title),
                subtitle = stringResource(R.string.pref_redirects_summary),
                icon = Icons.Outlined.Block,
                checked = blockSuspiciousRedirects,
                onCheckedChange = { viewModel.setBlockSuspiciousRedirects(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_popups_title),
                subtitle = stringResource(R.string.pref_popups_summary),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                checked = openPopupsInNewTab,
                onCheckedChange = { viewModel.setOpenPopupsInNewTab(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_adguard_dns_title),
                subtitle = stringResource(R.string.pref_adguard_dns_summary),
                icon = Icons.Outlined.Dns,
                checked = isAdguardDnsLayerEnabled,
                onCheckedChange = { viewModel.setAdguardDnsLayerEnabled(it) }
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.pref_private_dns_title),
                subtitle = stringResource(R.string.pref_private_dns_summary),
                icon = Icons.Outlined.SettingsEthernet,
                onClick = {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Perisai Privasi & Jaringan
        SettingsSection(title = stringResource(R.string.settings_section_privacy)) {
            SettingsNavRow(
                title = stringResource(R.string.pref_privacy_shield_title),
                value = privacyShieldLabel,
                icon = Icons.Outlined.Security,
                onClick = { showPrivacyShieldDialog = true }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_webrtc_leak_title),
                subtitle = stringResource(R.string.pref_webrtc_leak_summary),
                icon = Icons.Outlined.VpnLock,
                checked = preventWebRtcLeak,
                onCheckedChange = { viewModel.setPreventWebRtcLeak(it) }
            )
            if (preventWebRtcLeak) {
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_strict_webrtc_title),
                    subtitle = stringResource(R.string.settings_strict_webrtc_summary),
                    icon = Icons.Outlined.GppGood,
                    checked = strictWebRtcMode,
                    onCheckedChange = { viewModel.setStrictWebRtcMode(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Media, Akselerasi & DevTools
        SettingsSection(title = stringResource(R.string.settings_section_advanced)) {
            SettingsSwitchRow(
                title = stringResource(R.string.pref_background_playback_title),
                subtitle = stringResource(R.string.pref_background_playback_summary),
                icon = Icons.Outlined.Headphones,
                checked = isBackgroundPlaybackEnabled,
                onCheckedChange = { viewModel.setBackgroundPlaybackEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_streaming_boost_title),
                subtitle = stringResource(R.string.pref_streaming_boost_summary),
                icon = Icons.Outlined.Speed,
                checked = isStreamingBoostEnabled,
                onCheckedChange = { viewModel.setStreamingBoostEnabled(it) }
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.pref_download_accel_title),
                value = "${downloadAcceleration}x",
                icon = Icons.Outlined.Download,
                onClick = { showDownloadThreadsDialog = true }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_data_saver_title),
                subtitle = stringResource(R.string.pref_data_saver_summary),
                icon = Icons.Outlined.DataSaverOn,
                checked = isDataSaverEnabled,
                onCheckedChange = { viewModel.setDataSaverEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_devtools_title),
                subtitle = stringResource(R.string.pref_devtools_summary),
                icon = Icons.Outlined.Code,
                checked = isInspectToolsEnabled,
                onCheckedChange = { viewModel.setInspectToolsEnabled(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Dolby Atmos & Vision
        val dolbyAtmosEnabled by viewModel.dolbyAtmosEnabled.collectAsState()
        val dolbyAtmosProfile by viewModel.dolbyAtmosProfile.collectAsState()
        val dolbyVisionEnabled by viewModel.dolbyVisionEnabled.collectAsState()
        val dolbyVisionMode by viewModel.dolbyVisionMode.collectAsState()
        val dolbyCapabilities by viewModel.dolbyDeviceCapabilities.collectAsState()

        SettingsSection(title = stringResource(R.string.settings_dolby_section_title)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.example.ui.components.DolbyHardwareDetectorCard(
                    capabilities = dolbyCapabilities,
                    onRescan = {
                        viewModel.refreshDolbyDeviceCapabilities()
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_rescan_done),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                com.example.ui.components.DolbyAtmosControlCard(
                    enabled = dolbyAtmosEnabled,
                    canEnable = dolbyCapabilities.canEnableAtmos,
                    disabledReason = dolbyCapabilities.atmosDisabledReason,
                    selectedProfile = dolbyAtmosProfile,
                    onToggle = {
                        val decision = viewModel.setDolbyAtmosEnabled(it)
                        Toast.makeText(context, decision.reason, Toast.LENGTH_SHORT).show()
                    },
                    onSelectProfile = { viewModel.setDolbyAtmosProfile(it) }
                )
                com.example.ui.components.DolbyVisionControlCard(
                    enabled = dolbyVisionEnabled,
                    canEnable = dolbyCapabilities.canEnableDolbyVision,
                    disabledReason = dolbyCapabilities.visionDisabledReason,
                    selectedMode = dolbyVisionMode,
                    onToggle = {
                        val decision = viewModel.setDolbyVisionEnabled(it)
                        Toast.makeText(context, decision.reason, Toast.LENGTH_SHORT).show()
                    },
                    onSelectMode = { viewModel.setDolbyVisionMode(it) }
                )
            }
        }
    }

    @Composable
    fun renderGeneralSection() {
        val dbEncryptionStatus by com.example.data.AppDatabase.encryptionStatusFlow.collectAsState()

        // 1. Pencarian & Bahasa
        SettingsSection(title = stringResource(R.string.category_general)) {
            SettingsNavRow(
                title = stringResource(R.string.pref_search_engine_title),
                subtitle = stringResource(R.string.pref_search_engine_summary),
                value = currentEngine,
                icon = Icons.Outlined.Search,
                onClick = { showSearchEngineDialog = true }
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.pref_language_title),
                subtitle = stringResource(R.string.pref_language_summary),
                value = languageLabel,
                icon = Icons.Outlined.Language,
                onClick = { showLanguageDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Izin Situs & Keamanan Navigasi
        SettingsSection(title = stringResource(R.string.category_security)) {
            SettingsNavRow(
                title = stringResource(R.string.pref_site_permissions_title),
                subtitle = stringResource(R.string.pref_site_permissions_summary),
                icon = Icons.Outlined.AdminPanelSettings,
                onClick = { showSitePermissions = true }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.security_https_only_title),
                subtitle = stringResource(R.string.security_https_only_sub),
                icon = Icons.Outlined.Https,
                checked = httpsOnlyMode,
                onCheckedChange = { viewModel.setHttpsOnlyMode(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.security_phishing_title),
                subtitle = stringResource(R.string.security_phishing_sub),
                icon = Icons.Outlined.GppMaybe,
                checked = phishingProtectionEnabled,
                onCheckedChange = { viewModel.setPhishingProtectionEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.security_cam_mic_title),
                subtitle = stringResource(R.string.security_cam_mic_sub),
                icon = Icons.Outlined.VideocamOff,
                checked = blockCameraMicStrict,
                onCheckedChange = { viewModel.setBlockCameraMicStrict(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_screen_recording_title),
                subtitle = stringResource(R.string.pref_screen_recording_summary),
                icon = Icons.Outlined.ScreenshotMonitor,
                checked = allowScreenRecording,
                onCheckedChange = { viewModel.setAllowScreenRecording(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Pembersihan & Data
        SettingsSection(title = stringResource(R.string.category_storage)) {
            SettingsNavRow(
                title = stringResource(R.string.menu_clear_browsing_data),
                subtitle = stringResource(R.string.pref_clear_all_data),
                icon = Icons.Outlined.DeleteSweep,
                onClick = { showClearDataDialog = true }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_clear_cookies_exit_title),
                subtitle = stringResource(R.string.pref_clear_cookies_exit_summary),
                icon = Icons.Outlined.Cookie,
                checked = clearCookiesOnExit,
                onCheckedChange = { viewModel.setClearCookiesOnExit(it) }
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_reset_quick_links),
                subtitle = stringResource(R.string.settings_reset_quick_links_summary),
                icon = Icons.Outlined.Restore,
                onClick = {
                    viewModel.resetQuickLinksToDefaults()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_reset_quick_links_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = if (dbEncryptionStatus.isEncrypted) Icons.Outlined.Lock else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (dbEncryptionStatus.isEncrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_db_status_prefix, dbEncryptionStatus.title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = dbEncryptionStatus.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    fun renderAppearanceSection() {
        val desktopZoomScale by viewModel.desktopZoomScale.collectAsState()
        var adaptiveZoomBounds by remember(desktopZoomScale) {
            val minBound = (desktopZoomScale - 25).coerceIn(50, 150).toFloat()
            val maxBound = (desktopZoomScale + 25).coerceIn(100, 200).toFloat()
            mutableStateOf(minBound..maxBound)
        }
        SettingsSection(title = stringResource(R.string.settings_cat_appearance)) {
            SettingsNavRow(
                title = stringResource(R.string.pref_theme_title),
                subtitle = stringResource(R.string.pref_theme_summary),
                value = themeLabel,
                icon = Icons.Outlined.Palette,
                onClick = { showThemeDialog = true }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.settings_liquid_glass_title),
                subtitle = stringResource(R.string.settings_liquid_glass_summary),
                icon = Icons.Outlined.BlurOn,
                checked = liquidGlassEnabled,
                onCheckedChange = { viewModel.setLiquidGlassEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_force_dark_title),
                subtitle = stringResource(R.string.pref_force_dark_summary),
                icon = Icons.Outlined.DarkMode,
                checked = forceWebPageDarkMode,
                onCheckedChange = { viewModel.setForceWebPageDarkMode(it) }
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.pref_display_mode_title),
                value = displayModeLabel,
                icon = Icons.Outlined.Devices,
                onClick = { showDisplayModeDialog = true }
            )
            SettingsDivider()
            SettingsSliderRow(
                title = stringResource(R.string.pref_desktop_zoom_title),
                valueLabel = "${desktopZoomScale}%",
                icon = Icons.Outlined.ZoomIn,
                value = desktopZoomScale.toFloat(),
                valueRange = 50f..200f,
                steps = 9,
                onValueChange = { viewModel.setDesktopZoomScale((it.toInt() / 5) * 5) }
            )
            SettingsDivider()
            SettingsRangeSliderRow(
                title = stringResource(R.string.pref_adaptive_zoom_title),
                valueLabel = "${adaptiveZoomBounds.start.toInt()}% - ${adaptiveZoomBounds.endInclusive.toInt()}%",
                icon = Icons.Outlined.AspectRatio,
                value = adaptiveZoomBounds,
                valueRange = 50f..200f,
                steps = 5,
                onValueChange = { range ->
                    adaptiveZoomBounds = range
                    val midZoom = ((range.start + range.endInclusive) / 2f).toInt()
                    viewModel.setDesktopZoomScale(((midZoom / 5) * 5).coerceIn(50, 200))
                }
            )
        }
    }

    @Composable
    fun renderPasswordsAndAutofillSection() {
        SettingsSection(title = stringResource(R.string.settings_cat_passwords)) {
            SettingsSwitchRow(
                title = stringResource(R.string.pref_save_passwords_title),
                subtitle = stringResource(R.string.pref_save_passwords_summary),
                icon = Icons.Outlined.Password,
                checked = isSavePasswordsEnabled,
                onCheckedChange = { viewModel.setSavePasswordsEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_autofill_title),
                subtitle = stringResource(R.string.pref_autofill_summary),
                icon = Icons.Outlined.EditNote,
                checked = isAutofillEnabled,
                onCheckedChange = { viewModel.setAutofillEnabled(it) }
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.pref_clipboard_shield_title),
                subtitle = stringResource(R.string.pref_clipboard_shield_summary),
                icon = Icons.Outlined.ContentPasteOff,
                checked = blockClipboardHijack,
                onCheckedChange = { viewModel.setBlockClipboardHijack(it) }
            )
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pref_keystore_status_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.pref_keystore_status_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    fun renderAboutSection() {
        SettingsAboutBanner()
        Spacer(modifier = Modifier.height(16.dp))
        SettingsSection(title = stringResource(R.string.settings_reset)) {
            SettingsNavRow(
                title = stringResource(R.string.settings_reset),
                subtitle = stringResource(R.string.settings_reset_confirm),
                icon = Icons.Outlined.RestartAlt,
                onClick = {
                    viewModel.resetToDefaults()
                    viewModel.resetQuickLinksToDefaults()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_reset_confirm),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.resetToDefaults()
                            viewModel.resetQuickLinksToDefaults()
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_reset_confirm),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text(stringResource(R.string.settings_reset))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isTablet = maxWidth >= 700.dp

            if (isTablet) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(250.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SettingsCategory.values().forEach { category ->
                                val isSelected = category == selectedCategory
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            text = stringResource(category.titleRes),
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    selected = isSelected,
                                    onClick = { selectedCategory = category },
                                    icon = { Icon(category.icon, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        AnimatedContent(
                            targetState = selectedCategory,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "settings_pane"
                        ) { category ->
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 680.dp)
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                when (category) {
                                    SettingsCategory.FEATURES -> renderFeaturesSection()
                                    SettingsCategory.GENERAL -> renderGeneralSection()
                                    SettingsCategory.APPEARANCE -> renderAppearanceSection()
                                    SettingsCategory.PASSWORDS_AUTOFILL -> renderPasswordsAndAutofillSection()
                                    SettingsCategory.ABOUT -> renderAboutSection()
                                }
                            }
                        }
                    }
                }
            } else {
                var showAllMobileSections by remember { mutableStateOf(true) }
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Modern Horizontal Category Pill Filter Bar on Mobile
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = showAllMobileSections,
                                onClick = { showAllMobileSections = true },
                                label = {
                                    Text(
                                        text = stringResource(R.string.settings_cat_all),
                                        fontWeight = if (showAllMobileSections) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            )
                        }
                        items(SettingsCategory.values().toList()) { category ->
                            val isSelected = !showAllMobileSections && selectedCategory == category
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    showAllMobileSections = false
                                    selectedCategory = category
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = category.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(category.titleRes),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (showAllMobileSections) {
                            renderFeaturesSection()
                            Spacer(modifier = Modifier.height(16.dp))
                            renderGeneralSection()
                            Spacer(modifier = Modifier.height(16.dp))
                            renderAppearanceSection()
                            Spacer(modifier = Modifier.height(16.dp))
                            renderPasswordsAndAutofillSection()
                            Spacer(modifier = Modifier.height(16.dp))
                            renderAboutSection()
                        } else {
                            when (selectedCategory) {
                                SettingsCategory.FEATURES -> renderFeaturesSection()
                                SettingsCategory.GENERAL -> renderGeneralSection()
                                SettingsCategory.APPEARANCE -> renderAppearanceSection()
                                SettingsCategory.PASSWORDS_AUTOFILL -> renderPasswordsAndAutofillSection()
                                SettingsCategory.ABOUT -> renderAboutSection()
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SingleChoiceSelectionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.interactiveLiquidGlass(
            shape = RoundedCornerShape(24.dp),
            elevation = 8.dp
        ),
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.Transparent,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .interactiveLiquidGlass(
                                isActive = isSelected,
                                shape = RoundedCornerShape(14.dp),
                                elevation = if (isSelected) 3.dp else 1.dp
                            )
                            .clickable { onSelect(value) },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            LiquidGlassRadioButton(
                                selected = isSelected,
                                onClick = { onSelect(value) },
                                modifier = Modifier.interactiveLiquidGlass(
                                    isActive = isSelected,
                                    shape = CircleShape,
                                    elevation = 2.dp
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ClearBrowsingDataDialog(
    onClearCacheOnly: () -> Unit,
    onClearAllData: () -> Unit,
    onClearHistory: () -> Unit,
    onClearBookmarks: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.menu_clear_browsing_data),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onClearCacheOnly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pref_clear_cache), textAlign = TextAlign.Center)
                }
                Button(
                    onClick = onClearAllData,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pref_clear_all_data), textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onClearHistory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pref_clear_history))
                }
                OutlinedButton(
                    onClick = onClearBookmarks,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pref_clear_bookmarks))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun SettingsAboutBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AdBlockBrandEmblem(
                size = 64.dp,
                cornerRadius = 18.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_about_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            DeveloperAiAttributionSection(
                isCompact = false,
                showCardContainer = false
            )
        }
    }
}

@Composable
fun SitePermissionsDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    var permissionsMap by remember { mutableStateOf(viewModel.permissionManager.getAllPermissions()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_site_permissions_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
            ) {
                if (permissionsMap.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_site_permissions_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(permissionsMap.keys.toList()) { origin ->
                            val sitePerms = permissionsMap[origin] ?: emptyMap()
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = origin,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    sitePerms.forEach { (type, state) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(type.name, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                text = state.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (state == com.example.manager.PermissionState.ALLOW) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    TextButton(
                                        onClick = {
                                            viewModel.permissionManager.resetWebsitePermission(origin)
                                            permissionsMap = viewModel.permissionManager.getAllPermissions()
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text(stringResource(R.string.settings_reset), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
        dismissButton = {
            if (permissionsMap.isNotEmpty()) {
                TextButton(
                    onClick = {
                        viewModel.permissionManager.clearAllPermissions()
                        permissionsMap = emptyMap()
                    }
                ) {
                    Text(stringResource(R.string.settings_clear_all), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
