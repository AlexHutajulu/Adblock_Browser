package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.manager.AdBlockMode
import com.example.manager.DisplayMode
import com.example.ui.components.DeveloperAiAttributionSection
import com.example.ui.components.DolbyAnimatedWaveBadge
import com.example.ui.theme.*
import com.example.ui.viewmodel.BrowserViewModel

private data class BentoSpeedDialSite(
    val title: String,
    val subtitle: String,
    val url: String,
    val letter: String,
    val accentColor: Color
)

private val defaultSpeedDialSites = listOf(
    BentoSpeedDialSite("Google", "Pencarian", "https://www.google.com", "G", GoogleBlue),
    BentoSpeedDialSite("YouTube", "Video & Musik", "https://m.youtube.com", "YT", GoogleRed),
    BentoSpeedDialSite("Wikipedia", "Ensiklopedia", "https://www.wikipedia.org", "W", Color(0xFF475569)),
    BentoSpeedDialSite("GitHub", "Kode & Repo", "https://github.com", "GH", Color(0xFF1E293B)),
    BentoSpeedDialSite("Reddit", "Komunitas", "https://www.reddit.com", "R", Color(0xFFFF4500)),
    BentoSpeedDialSite("DuckDuckGo", "Privasi", "https://duckduckgo.com", "DD", Color(0xFFDE5833)),
    BentoSpeedDialSite("Berita", "Headline", "https://news.google.com", "N", GoogleGreen),
    BentoSpeedDialSite("Translate", "Terjemahan", "https://translate.google.com", "TR", Color(0xFF2563EB))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: BrowserViewModel,
    onNavigate: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    onFocusOmnibox: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var searchInput by remember { mutableStateOf("") }

    val activeTabId by viewModel.activeTabId.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTab = tabs.find { it.id == activeTabId }
    val isIncognito = activeTab?.isPrivate == true

    val selectedEngine by viewModel.selectedSearchEngine.collectAsState()
    val adBlockEnabled by viewModel.adBlockEnabled.collectAsState()
    val adBlockMode by viewModel.adBlockMode.collectAsState()
    val trackerBlockEnabled by viewModel.trackerBlockEnabled.collectAsState()
    val trackersBlockedCount by viewModel.trackerCookiesBlockedCount.collectAsState()
    val bookmarks by viewModel.bookmarksState.collectAsState()
    val recentHistory by viewModel.historyState.collectAsState()

    if (isIncognito) {
        ModernIncognitoNewTabPage(
            searchInput = searchInput,
            onSearchInputChange = { searchInput = it },
            onSearchSubmit = onSearchSubmit,
            onExitIncognito = {
                val normalTab = tabs.firstOrNull { !it.isPrivate }
                if (normalTab != null) {
                    viewModel.selectTab(normalTab.id)
                } else {
                    viewModel.addNewTab(isPrivate = false)
                }
            }
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        val layoutProfile = WindowLayoutProfile(maxWidth, maxHeight)
        val currentDisplayMode by viewModel.displayMode.collectAsState()
        val isDesktopMode = currentDisplayMode == DisplayMode.DESKTOP
        val isWide = layoutProfile.isTabletOrWide
        val isBentoTwoColumn = maxWidth >= 340.dp
        val isCompactHeight = maxHeight < 500.dp
        val contentMaxWidth = if (isWide || isDesktopMode) 900.dp else maxWidth

        var hasEntered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { hasEntered = true }
        val entranceAlpha by animateFloatAsState(
            targetValue = if (hasEntered) 1f else 0f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "home_entrance_alpha"
        )
        val entranceOffsetY by animateFloatAsState(
            targetValue = if (hasEntered) 0f else 14f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "home_entrance_y"
        )

        val extensionsList by viewModel.extensions.collectAsState()
        val enabledExtCount = remember(extensionsList) { extensionsList.count { it.isEnabled } }
        val dolbyAtmosEnabled by viewModel.dolbyAtmosEnabled.collectAsState()
        val dolbyVisionEnabled by viewModel.dolbyVisionEnabled.collectAsState()
        val dolbyCapabilities by viewModel.dolbyDeviceCapabilities.collectAsState()

        Column(
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entranceAlpha
                    translationY = entranceOffsetY
                }
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isWide) 24.dp else if (maxWidth < 340.dp) 10.dp else 14.dp,
                    vertical = if (isCompactHeight) 8.dp else 14.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Modern Top Workspace Bar: Brand Identity + Segmented Mobile/Desktop Switcher + Incognito
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Brand Identity Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_alex_singularity),
                        contentDescription = "AlexBrowser Logo",
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .shadow(4.dp, RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "AdBlock Browser",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                letterSpacing = (-0.3).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (adBlockEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.outline)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDesktopMode) "Workspace Desktop" else "Workspace Mobile",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Right Segmented Mode Switcher (Mobile | Desktop) + Incognito Orb
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val isMobileSelected = !isDesktopMode
                            Surface(
                                onClick = { viewModel.setDisplayMode(DisplayMode.MOBILE) },
                                shape = RoundedCornerShape(100.dp),
                                color = if (isMobileSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PhoneAndroid,
                                        contentDescription = "Mode Mobile",
                                        tint = if (isMobileSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "Mobile",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.5.sp
                                        ),
                                        color = if (isMobileSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }

                            Surface(
                                onClick = { viewModel.setDisplayMode(DisplayMode.DESKTOP) },
                                shape = RoundedCornerShape(100.dp),
                                color = if (isDesktopMode) MaterialTheme.colorScheme.primary else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DesktopWindows,
                                        contentDescription = "Mode Desktop",
                                        tint = if (isDesktopMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "Desktop",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.5.sp
                                        ),
                                        color = if (isDesktopMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Incognito Quick Launch Button
                    Surface(
                        onClick = { viewModel.addNewTab(isPrivate = true) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = "Tab Samaran Baru",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 16.dp))

            // 2. Unified Command & Search Omnibox with Integrated Search Engine Selector
            var showSearchEngineMenu by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .interactiveLiquidGlass(shape = RoundedCornerShape(22.dp), elevation = 6.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Integrated Search Engine Selector Pill inside Left of Omnibox
                    Box {
                        Surface(
                            onClick = { showSearchEngineMenu = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Pilih Mesin Pencari",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = selectedEngine.take(10),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showSearchEngineMenu,
                            onDismissRequest = { showSearchEngineMenu = false },
                            modifier = Modifier.widthIn(min = 220.dp)
                        ) {
                            com.example.manager.SearchEngine.getAllEngines().forEach { engine ->
                                val isSelected = selectedEngine.equals(engine.engineName, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = engine.engineName,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (engine.description.isNotBlank()) {
                                                Text(
                                                    text = engine.description,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Dipilih",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSearchEngine(engine.engineName)
                                        showSearchEngineMenu = false
                                        Toast.makeText(
                                            context,
                                            "${engine.engineName} disetel sebagai mesin pencari",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        TextField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    text = if (isDesktopMode) "Ketik URL atau cari di $selectedEngine (Desktop)..."
                                    else "Ketik alamat web atau cari...",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (searchInput.isNotBlank()) {
                                        onSearchSubmit(searchInput.trim())
                                    }
                                }
                            )
                        )
                    }

                    if (searchInput.isNotEmpty()) {
                        IconButton(
                            onClick = { searchInput = "" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Bersihkan",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Surface(
                            onClick = { onSearchSubmit(searchInput.trim()) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = "Telusuri",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else if (onFocusOmnibox != null) {
                        IconButton(
                            onClick = onFocusOmnibox,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = "Fokus Bilah Alamat",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Quick Workspace Dock (4 Non-Clipping Action Pills)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BentoQuickActionPill(
                    icon = Icons.Outlined.Bookmarks,
                    label = "Bookmark",
                    badgeText = if (bookmarks.isNotEmpty()) "${bookmarks.size}" else null,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setBookmarksVisible(true) }
                )
                BentoQuickActionPill(
                    icon = Icons.Outlined.History,
                    label = "Riwayat",
                    badgeText = null,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setHistoryVisible(true) }
                )
                BentoQuickActionPill(
                    icon = Icons.Outlined.FileDownload,
                    label = "Unduhan",
                    badgeText = null,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setDownloadsVisible(true) }
                )
                BentoQuickActionPill(
                    icon = Icons.Outlined.Extension,
                    label = "Ekstensi",
                    badgeText = if (enabledExtCount > 0) "$enabledExtCount" else null,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setExtensionsHubVisible(true) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Bento 2-Column Live Telemetry & Media Studio Cards (AdShield + Dolby Atmos/Vision)
            val adBlockModeLabel = when (adBlockMode) {
                AdBlockMode.OFF -> "Nonaktif"
                AdBlockMode.BALANCED -> "Balance+"
                AdBlockMode.AGGRESSIVE -> "Agresif Ultra"
            }

            if (isBentoTwoColumn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BentoAdShieldCard(
                        adBlockEnabled = adBlockEnabled,
                        adBlockModeName = adBlockModeLabel,
                        trackerBlockEnabled = trackerBlockEnabled,
                        trackersBlockedCount = trackersBlockedCount,
                        modifier = Modifier.weight(1f)
                    )
                    BentoDolbyStudioCard(
                        dolbyAtmosEnabled = dolbyAtmosEnabled,
                        dolbyVisionEnabled = dolbyVisionEnabled,
                        canEnableAtmos = dolbyCapabilities.canEnableAtmos,
                        canEnableVision = dolbyCapabilities.canEnableDolbyVision,
                        onClick = { viewModel.setDolbyDialogVisible(true) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                BentoAdShieldCard(
                    adBlockEnabled = adBlockEnabled,
                    adBlockModeName = adBlockModeLabel,
                    trackerBlockEnabled = trackerBlockEnabled,
                    trackersBlockedCount = trackersBlockedCount,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                BentoDolbyStudioCard(
                    dolbyAtmosEnabled = dolbyAtmosEnabled,
                    dolbyVisionEnabled = dolbyVisionEnabled,
                    canEnableAtmos = dolbyCapabilities.canEnableAtmos,
                    canEnableVision = dolbyCapabilities.canEnableDolbyVision,
                    onClick = { viewModel.setDolbyDialogVisible(true) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Modern Speed Dial Bento Grid (8 Columns on Wide/Desktop, 4 Columns on Mobile)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .interactiveLiquidGlass(shape = RoundedCornerShape(24.dp), elevation = 3.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.StarOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Pintasan Cepat",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = if (isWide) "Tata Letak Desktop (8 Kolom)" else "Tata Letak Adaptif (4 Kolom)",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val cols = if (isWide) 8 else 4
                    val rows = defaultSpeedDialSites.chunked(cols)
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { item ->
                                BentoSpeedDialTile(
                                    site = item,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onNavigate(item.url) }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Adaptive Bookmarks & Recent History Bento Panels (Side-by-Side on Wide/Desktop, Stacked on Mobile)
            if (bookmarks.isNotEmpty() || recentHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                if (isWide && bookmarks.isNotEmpty() && recentHistory.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        BookmarksBentoPanel(
                            bookmarks = bookmarks,
                            onNavigate = onNavigate,
                            onViewAll = { viewModel.setBookmarksVisible(true) },
                            modifier = Modifier.weight(1f)
                        )
                        HistoryBentoPanel(
                            history = recentHistory,
                            onNavigate = onNavigate,
                            onViewAll = { viewModel.setHistoryVisible(true) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    if (bookmarks.isNotEmpty()) {
                        BookmarksBentoPanel(
                            bookmarks = bookmarks,
                            onNavigate = onNavigate,
                            onViewAll = { viewModel.setBookmarksVisible(true) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (bookmarks.isNotEmpty() && recentHistory.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (recentHistory.isNotEmpty()) {
                        HistoryBentoPanel(
                            history = recentHistory,
                            onNavigate = onNavigate,
                            onViewAll = { viewModel.setHistoryVisible(true) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 7. 5-AI Collaborator Attribution Card (Antigravity, Gemini, Claude, ChatGPT, Meta Muse)
            DeveloperAiAttributionSection(
                isCompact = !isWide,
                showCardContainer = true
            )

            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

@Composable
private fun BentoSpeedDialTile(
    site: BentoSpeedDialSite,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tileInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = tileInteraction,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(site.accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(site.accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = site.letter,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            fontSize = 11.5.sp
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = site.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = site.subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BentoQuickActionPill(
    icon: ImageVector,
    label: String,
    badgeText: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val btnInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .height(50.dp)
            .interactiveLiquidGlass(
                interactionSource = btnInteraction,
                shape = RoundedCornerShape(16.dp),
                elevation = 2.dp
            )
            .clickable(
                interactionSource = btnInteraction,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                if (!badgeText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BentoAdShieldCard(
    adBlockEnabled: Boolean,
    adBlockModeName: String,
    trackerBlockEnabled: Boolean,
    trackersBlockedCount: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 124.dp)
            .interactiveLiquidGlass(shape = RoundedCornerShape(22.dp), elevation = 3.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = if (adBlockEnabled) Color(0xFF10B981).copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = if (adBlockEnabled) adBlockModeName else "Off",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = if (adBlockEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (trackersBlockedCount > 0) "$trackersBlockedCount Diblokir" else "AdShield Aktif",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (trackerBlockEnabled) "Auto-Blokir Iklan, Pelacak & Pop-up" else "Pemblokir Iklan & Pop-up",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BentoDolbyStudioCard(
    dolbyAtmosEnabled: Boolean,
    dolbyVisionEnabled: Boolean,
    canEnableAtmos: Boolean,
    canEnableVision: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dolbyCardInteraction = remember { MutableInteractionSource() }
    val isAnyActive = dolbyAtmosEnabled || dolbyVisionEnabled
    Surface(
        modifier = modifier
            .heightIn(min = 124.dp)
            .interactiveLiquidGlass(
                isActive = isAnyActive,
                interactionSource = dolbyCardInteraction,
                shape = RoundedCornerShape(22.dp),
                elevation = 3.dp
            )
            .clickable(
                interactionSource = dolbyCardInteraction,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DolbyAnimatedWaveBadge(isActive = isAnyActive)
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = "Buka Studio",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Dolby Atmos & Vision",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Atmos: ${if (dolbyAtmosEnabled) "ON" else if (canEnableAtmos) "Siap" else "Off"}  •  Vision: ${if (dolbyVisionEnabled) "ON" else if (canEnableVision) "Siap" else "Off"}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = if (isAnyActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookmarksBentoPanel(
    bookmarks: List<com.example.data.BookmarkItem>,
    onNavigate: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.liquidGlass(shape = RoundedCornerShape(22.dp), elevation = 2.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Bookmarks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bookmark Tersimpan",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(
                    onClick = onViewAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Semua", style = MaterialTheme.typography.labelMedium)
                }
            }
            bookmarks.take(3).forEach { bmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigate(bmark.url) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bmark.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = bmark.url,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBentoPanel(
    history: List<com.example.data.HistoryItem>,
    onNavigate: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.liquidGlass(shape = RoundedCornerShape(22.dp), elevation = 2.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Aktivitas Terakhir",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(
                    onClick = onViewAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Semua", style = MaterialTheme.typography.labelMedium)
                }
            }
            history.take(3).forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigate(item.url) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title.ifBlank { item.url },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.url,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernIncognitoNewTabPage(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    onExitIncognito: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ChromeIncognitoBg),
        contentAlignment = Alignment.TopCenter
    ) {
        val layoutProfile = WindowLayoutProfile(maxWidth, maxHeight)
        val isWide = layoutProfile.isTabletOrWide
        val isCompactHeight = maxHeight < 500.dp

        Column(
            modifier = Modifier
                .widthIn(max = if (isWide) 680.dp else maxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (maxWidth < 340.dp) 12.dp else 20.dp, vertical = if (isCompactHeight) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = ChromeIncognitoOmnibox
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = ChromeIncognitoAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Sesi Privat Terisolasi",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ChromeIncognitoText
                        )
                    }
                }

                TextButton(
                    onClick = onExitIncognito,
                    modifier = Modifier.heightIn(min = 38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = ChromeIncognitoAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Tab Reguler", color = ChromeIncognitoAccent, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 18.dp))

            Box(
                modifier = Modifier
                    .size(if (isCompactHeight) 56.dp else 72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ChromeIncognitoOmnibox),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Mode Penyamaran",
                    tint = ChromeIncognitoAccent,
                    modifier = Modifier.size(if (isCompactHeight) 28.dp else 36.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Penjelajahan Samaran Ultra-Aman",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (isCompactHeight) 20.sp else 23.sp
                ),
                color = ChromeIncognitoText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .liquidGlass(shape = RoundedCornerShape(22.dp), isIncognito = true, elevation = 3.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Cari",
                        tint = ChromeIncognitoAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TextField(
                        value = searchInput,
                        onValueChange = onSearchInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Cari atau ketik URL secara privat...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ChromeIncognitoSubtext,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = ChromeIncognitoAccent,
                            focusedTextColor = ChromeIncognitoText,
                            unfocusedTextColor = ChromeIncognitoText
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (searchInput.isNotBlank()) {
                                    onSearchSubmit(searchInput.trim())
                                }
                            }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IncognitoBentoBlock(
                        title = "Tidak Disimpan ke Disk:",
                        bullets = listOf(
                            "Riwayat penjelajahan & pencarian",
                            "Penyimpanan DOM (localStorage) & cookie pihak ketiga",
                            "Data formulir & jejak sesi"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IncognitoBentoBlock(
                        title = "Perlindungan Aktif:",
                        bullets = listOf(
                            "Blokir otomatis semua pop-up tab baru",
                            "AdShield Ultra & Anti-Fingerprinting",
                            "Isolasi penuh dari tab reguler"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                IncognitoBentoBlock(
                    title = "Tidak Disimpan ke Disk:",
                    bullets = listOf(
                        "Riwayat penjelajahan & pencarian",
                        "Penyimpanan DOM (localStorage) & cookie pihak ketiga",
                        "Data formulir & jejak sesi"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                IncognitoBentoBlock(
                    title = "Perlindungan Aktif:",
                    bullets = listOf(
                        "Blokir otomatis semua pop-up tab baru",
                        "AdShield Ultra & Anti-Fingerprinting",
                        "Isolasi penuh dari tab reguler"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun IncognitoBentoBlock(
    title: String,
    bullets: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.liquidGlass(shape = RoundedCornerShape(18.dp), isIncognito = true, elevation = 2.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = ChromeIncognitoText
            )
            Spacer(modifier = Modifier.height(6.dp))
            bullets.forEach { item ->
                Text(
                    text = "•  $item",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = ChromeIncognitoSubtext,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
