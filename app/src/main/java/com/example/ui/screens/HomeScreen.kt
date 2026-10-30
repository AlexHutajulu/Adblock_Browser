package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.DeveloperAiAttributionSection
import com.example.ui.components.DolbyAnimatedWaveBadge
import com.example.ui.theme.*
import com.example.ui.viewmodel.BrowserViewModel

private data class ChromeShortcutItem(
    val title: String,
    val url: String,
    val letter: String,
    val badgeColor: Color
)

private val defaultChromeShortcuts = listOf(
    ChromeShortcutItem("Google", "https://www.google.com", "G", GoogleBlue),
    ChromeShortcutItem("YouTube", "https://m.youtube.com", "Y", GoogleRed),
    ChromeShortcutItem("Wikipedia", "https://www.wikipedia.org", "W", Color(0xFF5F6368)),
    ChromeShortcutItem("GitHub", "https://github.com", "GH", Color(0xFF24292F)),
    ChromeShortcutItem("Reddit", "https://www.reddit.com", "R", Color(0xFFFF4500)),
    ChromeShortcutItem("DuckDuckGo", "https://duckduckgo.com", "D", Color(0xFFDE5833)),
    ChromeShortcutItem("Berita", "https://news.google.com", "N", GoogleGreen),
    ChromeShortcutItem("Translate", "https://translate.google.com", "T", Color(0xFF1A73E8))
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
        ChromeIncognitoNewTabPage(
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
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        val layoutProfile = WindowLayoutProfile(maxWidth, maxHeight)
        val isWide = layoutProfile.isTabletOrWide
        val isCompactHeight = maxHeight < 500.dp
        val contentMaxWidth = if (isWide) 780.dp else maxWidth

        var hasEntered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { hasEntered = true }
        val entranceAlpha by animateFloatAsState(
            targetValue = if (hasEntered) 1f else 0f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            label = "home_entrance_alpha"
        )
        val entranceOffsetY by animateFloatAsState(
            targetValue = if (hasEntered) 0f else 18f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "home_entrance_y"
        )

        val currentDisplayMode by viewModel.displayMode.collectAsState()
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
                    horizontal = if (isWide) 28.dp else if (maxWidth < 340.dp) 12.dp else 16.dp,
                    vertical = if (isCompactHeight) 10.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Modern Top Control Strip (Search Engine Selector, Mobile/Desktop Toggle, Incognito Pill)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var showSearchEngineMenu by remember { mutableStateOf(false) }
                val engineInteraction = remember { MutableInteractionSource() }
                Box {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .interactiveLiquidGlass(
                                interactionSource = engineInteraction,
                                shape = RoundedCornerShape(100.dp),
                                elevation = 2.dp
                            )
                            .clickable(
                                interactionSource = engineInteraction,
                                indication = null
                            ) { showSearchEngineMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "Ganti Mesin Pencari",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedEngine,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
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

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val modeInteraction = remember { MutableInteractionSource() }
                    val isDesktopMode = currentDisplayMode == com.example.manager.DisplayMode.DESKTOP
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .interactiveLiquidGlass(
                                isActive = isDesktopMode,
                                interactionSource = modeInteraction,
                                shape = RoundedCornerShape(100.dp),
                                elevation = 2.dp
                            )
                            .clickable(
                                interactionSource = modeInteraction,
                                indication = null
                            ) {
                                val next = if (isDesktopMode) com.example.manager.DisplayMode.MOBILE
                                else com.example.manager.DisplayMode.DESKTOP
                                viewModel.setDisplayMode(next)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDesktopMode) Icons.Outlined.DesktopWindows else Icons.Outlined.PhoneAndroid,
                                contentDescription = "Ganti Mode Tampilan",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isDesktopMode) "Mode Desktop" else "Mode Mobile",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }

                    val incognitoPillInteraction = remember { MutableInteractionSource() }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .interactiveLiquidGlass(
                                interactionSource = incognitoPillInteraction,
                                shape = RoundedCornerShape(100.dp),
                                elevation = 2.dp
                            )
                            .clickable(
                                interactionSource = incognitoPillInteraction,
                                indication = null
                            ) { viewModel.addNewTab(isPrivate = true) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = "Tab Samaran Baru",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Samaran",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else if (isWide) 28.dp else 20.dp))

            // 2. Clean Brand Hero & Search Engine Wordmark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_alex_singularity),
                    contentDescription = "Alex Browser Singularity",
                    modifier = Modifier
                        .size(if (isCompactHeight) 40.dp else if (isWide) 54.dp else 46.dp)
                        .clip(CircleShape)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFF428BFF).copy(alpha = 0.28f),
                            spotColor = Color(0xFF6366F1).copy(alpha = 0.35f)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                val engineHeader = remember(selectedEngine) {
                    when {
                        selectedEngine.equals("Google", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = GoogleBlue)) { append("G") }
                            withStyle(SpanStyle(color = GoogleRed)) { append("o") }
                            withStyle(SpanStyle(color = GoogleYellow)) { append("o") }
                            withStyle(SpanStyle(color = GoogleBlue)) { append("g") }
                            withStyle(SpanStyle(color = GoogleGreen)) { append("l") }
                            withStyle(SpanStyle(color = GoogleRed)) { append("e") }
                        }
                        selectedEngine.equals("DuckDuckGo", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFFDE5833))) { append("DuckDuckGo") }
                        }
                        selectedEngine.equals("Bing", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF00838F))) { append("Bing") }
                        }
                        selectedEngine.equals("Brave Search", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFFFB542B))) { append("Brave") }
                        }
                        selectedEngine.equals("Yahoo", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF6001D2))) { append("Yahoo!") }
                        }
                        selectedEngine.equals("Ecosia", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF00875A))) { append("Ecosia") }
                        }
                        selectedEngine.equals("Yandex", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFFE60000))) { append("Yandex") }
                        }
                        selectedEngine.equals("Startpage", ignoreCase = true) -> buildAnnotatedString {
                            withStyle(SpanStyle(color = Color(0xFF2252EC))) { append("Startpage") }
                        }
                        else -> buildAnnotatedString { append(selectedEngine) }
                    }
                }
                Text(
                    text = engineHeader,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = if (isCompactHeight) 28.sp else if (isWide) 44.sp else 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.8).sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 18.dp))

            // 3. Modern Floating Omnibox Search Pill
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .interactiveLiquidGlass(shape = RoundedCornerShape(28.dp), elevation = 6.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f))
                            .then(if (onFocusOmnibox != null) Modifier.clickable { onFocusOmnibox() } else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Cari",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

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
                                    text = "Cari di $selectedEngine atau ketik alamat web...",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp),
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
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Bersihkan",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(
                            onClick = { onSearchSubmit(searchInput.trim()) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = "Telusuri",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 18.dp))

            // 4. Adaptive Quick Links Grid (8 columns on Wide/Desktop, 4 columns on Mobile)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .interactiveLiquidGlass(shape = RoundedCornerShape(24.dp), elevation = 4.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (isCompactHeight) 12.dp else 16.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 10.dp else 14.dp)
                ) {
                    val cols = if (isWide) 8 else 4
                    val rows = defaultChromeShortcuts.chunked(cols)
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            rowItems.forEach { item ->
                                ChromeSiteShortcutTile(
                                    title = item.title,
                                    letter = item.letter,
                                    badgeColor = item.badgeColor,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onNavigate(item.url) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Clean 4-Item Quick Hub (Zero-Clipping on Mobile & Desktop)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromeQuickActionButton(
                    icon = Icons.Outlined.StarOutline,
                    label = "Bookmark",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setBookmarksVisible(true) }
                )
                ChromeQuickActionButton(
                    icon = Icons.Outlined.History,
                    label = "Riwayat",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setHistoryVisible(true) }
                )
                ChromeQuickActionButton(
                    icon = Icons.Outlined.FileDownload,
                    label = "Unduhan",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setDownloadsVisible(true) }
                )
                ChromeQuickActionButton(
                    icon = Icons.Outlined.Extension,
                    label = if (enabledExtCount > 0) "Ekstensi ($enabledExtCount)" else "Ekstensi",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setExtensionsHubVisible(true) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Adaptive Status Cards (Side-by-Side on Desktop/Tablet, Stacked on Mobile)
            val adBlockModeLabel = when (adBlockMode) {
                com.example.manager.AdBlockMode.OFF -> "Nonaktif"
                com.example.manager.AdBlockMode.BALANCED -> "Seimbang+"
                com.example.manager.AdBlockMode.AGGRESSIVE -> "Agresif Ultra"
            }

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChromePrivacySummaryCard(
                        adBlockEnabled = adBlockEnabled,
                        adBlockModeName = adBlockModeLabel,
                        trackerBlockEnabled = trackerBlockEnabled,
                        trackersBlockedCount = trackersBlockedCount,
                        modifier = Modifier.weight(1f)
                    )
                    DolbyHomeSummaryCard(
                        dolbyAtmosEnabled = dolbyAtmosEnabled,
                        dolbyVisionEnabled = dolbyVisionEnabled,
                        canEnableAtmos = dolbyCapabilities.canEnableAtmos,
                        canEnableVision = dolbyCapabilities.canEnableDolbyVision,
                        onClick = { viewModel.setDolbyDialogVisible(true) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ChromePrivacySummaryCard(
                    adBlockEnabled = adBlockEnabled,
                    adBlockModeName = adBlockModeLabel,
                    trackerBlockEnabled = trackerBlockEnabled,
                    trackersBlockedCount = trackersBlockedCount,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                DolbyHomeSummaryCard(
                    dolbyAtmosEnabled = dolbyAtmosEnabled,
                    dolbyVisionEnabled = dolbyVisionEnabled,
                    canEnableAtmos = dolbyCapabilities.canEnableAtmos,
                    canEnableVision = dolbyCapabilities.canEnableDolbyVision,
                    onClick = { viewModel.setDolbyDialogVisible(true) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 7. Saved Bookmarks & Recent History Cards
            if (bookmarks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(shape = RoundedCornerShape(20.dp), elevation = 2.dp),
                    shape = RoundedCornerShape(20.dp),
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Bookmark Tersimpan",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = { viewModel.setBookmarksVisible(true) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Lihat Semua", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        bookmarks.take(3).forEach { bmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(bmark.url) }
                                    .padding(vertical = 7.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bmark.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = bmark.url,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
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

            if (recentHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(shape = RoundedCornerShape(20.dp), elevation = 2.dp),
                    shape = RoundedCornerShape(20.dp),
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Baru Saja Dikunjungi",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = { viewModel.setHistoryVisible(true) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Lihat Semua", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        recentHistory.take(3).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(item.url) }
                                    .padding(vertical = 7.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.url },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
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

            Spacer(modifier = Modifier.height(16.dp))

            // 8. 5 AI Collaborator Attribution (Antigravity, Gemini, Claude, ChatGPT, Meta Muse)
            DeveloperAiAttributionSection(
                isCompact = !isWide,
                showCardContainer = true
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DolbyHomeSummaryCard(
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
            .interactiveLiquidGlass(
                isActive = isAnyActive,
                interactionSource = dolbyCardInteraction,
                shape = RoundedCornerShape(20.dp),
                elevation = 3.dp
            )
            .clickable(
                interactionSource = dolbyCardInteraction,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DolbyAnimatedWaveBadge(isActive = isAnyActive)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dolby Atmos & Vision",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isAnyActive) "Mesin Audio 3D & Video HDR Aktif" else "Ketuk untuk mengaktifkan peningkatan media",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Atur",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromePrivacyChip(
                    label = "Atmos: ${if (dolbyAtmosEnabled) "ON" else if (canEnableAtmos) "Siap" else "Off"}",
                    isActive = dolbyAtmosEnabled,
                    modifier = Modifier.weight(1f)
                )
                ChromePrivacyChip(
                    label = "Vision: ${if (dolbyVisionEnabled) "ON" else if (canEnableVision) "Siap" else "Off"}",
                    isActive = dolbyVisionEnabled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ChromeSiteShortcutTile(
    title: String,
    letter: String,
    badgeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tileInteraction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = tileInteraction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 5.dp, horizontal = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .interactiveLiquidGlass(
                    interactionSource = tileInteraction,
                    shape = CircleShape,
                    elevation = 3.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 12.5.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChromeQuickActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val btnInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .height(54.dp)
            .interactiveLiquidGlass(
                interactionSource = btnInteraction,
                shape = RoundedCornerShape(18.dp),
                elevation = 2.dp
            )
            .clickable(
                interactionSource = btnInteraction,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChromePrivacySummaryCard(
    adBlockEnabled: Boolean,
    adBlockModeName: String,
    trackerBlockEnabled: Boolean,
    trackersBlockedCount: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.interactiveLiquidGlass(shape = RoundedCornerShape(20.dp), elevation = 3.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AlexShield & Pop-up Guard",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (trackersBlockedCount > 0) "$trackersBlockedCount iklan, pelacak & pop-up diblokir"
                        else "Perlindungan iklan, pelacak & pop-up aktif",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromePrivacyChip(
                    label = "AdShield: ${if (adBlockEnabled) adBlockModeName else "Off"}",
                    isActive = adBlockEnabled,
                    modifier = Modifier.weight(1f)
                )
                ChromePrivacyChip(
                    label = "Pelacak: ${if (trackerBlockEnabled) "Diblokir" else "Off"}",
                    isActive = trackerBlockEnabled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ChromePrivacyChip(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChromeIncognitoNewTabPage(
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
                .widthIn(max = if (isWide) 640.dp else maxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (maxWidth < 340.dp) 14.dp else 22.dp, vertical = if (isCompactHeight) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onExitIncognito,
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = ChromeIncognitoAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Beralih ke Tab Reguler", color = ChromeIncognitoAccent)
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 4.dp else 8.dp))

            Box(
                modifier = Modifier
                    .size(if (isCompactHeight) 54.dp else 68.dp)
                    .clip(CircleShape)
                    .background(ChromeIncognitoOmnibox),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Mode Penyamaran",
                    tint = ChromeIncognitoText,
                    modifier = Modifier.size(if (isCompactHeight) 26.dp else 34.dp)
                )
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 14.dp))

            Text(
                text = "Mode Penyamaran (Incognito)",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = ChromeIncognitoText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 16.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .liquidGlass(shape = RoundedCornerShape(26.dp), isIncognito = true, elevation = 3.dp),
                shape = RoundedCornerShape(26.dp),
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
                        tint = ChromeIncognitoSubtext,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TextField(
                        value = searchInput,
                        onValueChange = onSearchInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Cari atau ketik URL di Mode Samaran",
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

            Spacer(modifier = Modifier.height(if (isCompactHeight) 14.dp else 20.dp))

            Text(
                text = "Tab samaran tidak menyimpan riwayat penjelajahan, penyimpanan DOM persisten (localStorage), maupun data formulir, serta memblokir cookie pihak ketiga.",
                style = MaterialTheme.typography.bodyMedium,
                color = ChromeIncognitoSubtext,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IncognitoInfoBlock(
                        title = "Tidak disimpan:",
                        bullets = listOf(
                            "Riwayat penjelajahan & unduhan privat",
                            "Penyimpanan DOM (localStorage) & cookie pihak ketiga",
                            "Data formulir & cache HTTP disk"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IncognitoInfoBlock(
                        title = "Perlindungan aktif:",
                        bullets = listOf(
                            "Pemblokiran otomatis semua pop-up tab baru",
                            "AdShield & Anti-Pelacak lintas situs",
                            "Pembersihan sesi saat tab samaran ditutup"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                IncognitoInfoBlock(
                    title = "Tidak disimpan:",
                    bullets = listOf(
                        "Riwayat penjelajahan & unduhan privat",
                        "Penyimpanan DOM (localStorage) & cookie pihak ketiga",
                        "Data formulir & cache HTTP disk"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                IncognitoInfoBlock(
                    title = "Perlindungan aktif:",
                    bullets = listOf(
                        "Pemblokiran otomatis semua pop-up tab baru",
                        "AdShield & Anti-Pelacak lintas situs",
                        "Pembersihan sesi saat tab samaran ditutup"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun IncognitoInfoBlock(
    title: String,
    bullets: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.liquidGlass(shape = RoundedCornerShape(16.dp), isIncognito = true, elevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
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
