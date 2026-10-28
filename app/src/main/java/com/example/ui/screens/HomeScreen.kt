package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.*
import com.example.ui.components.DeveloperAiAttributionSection
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
    ChromeShortcutItem("Google News", "https://news.google.com", "N", GoogleGreen),
    ChromeShortcutItem("Translate", "https://translate.google.com", "T", Color(0xFF1A73E8))
)

@OptIn(ExperimentalMaterial3Api::class)
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
        val contentMaxWidth = if (isWide) 660.dp else maxWidth

        var hasEntered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { hasEntered = true }
        val entranceAlpha by animateFloatAsState(
            targetValue = if (hasEntered) 1f else 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "home_entrance_alpha"
        )
        val entranceOffsetY by animateFloatAsState(
            targetValue = if (hasEntered) 0f else 26f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "home_entrance_y"
        )
        val heroScale by animateFloatAsState(
            targetValue = if (hasEntered) 1.0f else 0.92f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "hero_scale"
        )

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
                    horizontal = if (isWide) 32.dp else if (maxWidth < 340.dp) 12.dp else 16.dp,
                    vertical = if (isCompactHeight) 10.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Responsive Top Header Strip (Anti-Clipping on Split-Screen & Floating Window)
            val currentDisplayMode by viewModel.displayMode.collectAsState()
            val extensionsList by viewModel.extensions.collectAsState()
            val enabledExtCount = remember(extensionsList) { extensionsList.count { it.isEnabled } }
            val dolbyAtmosEnabled by viewModel.dolbyAtmosEnabled.collectAsState()
            val dolbyVisionEnabled by viewModel.dolbyVisionEnabled.collectAsState()
            val dolbyCapabilities by viewModel.dolbyDeviceCapabilities.collectAsState()

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
                                color = MaterialTheme.colorScheme.onSurface
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
                        modifier = Modifier.widthIn(min = 230.dp)
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
                    // Quick Mobile / Desktop UI Mode Toggle
                    val modeInteraction = remember { MutableInteractionSource() }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .interactiveLiquidGlass(
                                isActive = currentDisplayMode == com.example.manager.DisplayMode.DESKTOP,
                                interactionSource = modeInteraction,
                                shape = RoundedCornerShape(100.dp),
                                elevation = 2.dp
                            )
                            .clickable(
                                interactionSource = modeInteraction,
                                indication = null
                            ) {
                                val next = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                                    com.example.manager.DisplayMode.MOBILE
                                else com.example.manager.DisplayMode.DESKTOP
                                viewModel.setDisplayMode(next)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                                    Icons.Outlined.DesktopWindows else Icons.Outlined.PhoneAndroid,
                                contentDescription = "Ganti Mode Tampilan",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP) "Desktop" else "Mobile",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    val dolbyPillInteraction = remember { MutableInteractionSource() }
                    val isAnyDolbyOn = dolbyAtmosEnabled || dolbyVisionEnabled
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .interactiveLiquidGlass(
                                isActive = isAnyDolbyOn,
                                interactionSource = dolbyPillInteraction,
                                shape = RoundedCornerShape(100.dp),
                                elevation = 2.dp
                            )
                            .clickable(
                                interactionSource = dolbyPillInteraction,
                                indication = null
                            ) { viewModel.setDolbyDialogVisible(true) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SurroundSound,
                                contentDescription = "Dolby Atmos & Vision",
                                tint = if (isAnyDolbyOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (dolbyAtmosEnabled && dolbyVisionEnabled) "Dolby Atmos • Vision"
                                else if (dolbyAtmosEnabled) "Dolby Atmos"
                                else if (dolbyVisionEnabled) "Dolby Vision"
                                else "Dolby Off",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Samaran",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else if (isWide) 24.dp else 18.dp))

            // Alex Browser Singularity Brand Hero Logo with Smooth Breathing Animation
            Image(
                painter = painterResource(id = R.drawable.ic_alex_singularity),
                contentDescription = "Alex Browser Singularity",
                modifier = Modifier
                    .size(if (isCompactHeight) 52.dp else if (isWide) 84.dp else 72.dp)
                    .graphicsLayer {
                        scaleX = heroScale
                        scaleY = heroScale
                    }
                    .clip(CircleShape)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = Color(0xFF428BFF).copy(alpha = 0.3f),
                        spotColor = Color(0xFF7046FF).copy(alpha = 0.4f)
                    )
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

            // Adaptive Search Engine Wordmark Header
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
                        withStyle(SpanStyle(color = Color(0xFFFB542B))) { append("Brave Search") }
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
                    else -> buildAnnotatedString {
                        append(selectedEngine)
                    }
                }
            }

            Text(
                text = engineHeader,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = if (isCompactHeight) 32.sp else if (isWide) 52.sp else 42.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 18.dp))

            // Liquid Glass Search Pill
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
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Search badge on the left of Fakebox
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
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

                    Spacer(modifier = Modifier.width(8.dp))

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
                                    text = "Cari di $selectedEngine atau ketik URL",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
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
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Bersihkan",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { onSearchSubmit(searchInput.trim()) },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = "Telusuri",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 20.dp))

            // Liquid Glass Circular Shortcuts Grid (2 rows of 4)
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
                        .padding(vertical = if (isCompactHeight) 12.dp else 18.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 10.dp else 16.dp)
                ) {
                    val rows = defaultChromeShortcuts.chunked(4)
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

            // Liquid Glass Quick Action Hub Row (Extensions, Bookmarks, History, Downloads)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromeQuickActionButton(
                    icon = Icons.Outlined.Extension,
                    label = if (enabledExtCount > 0) "Ekstensi ($enabledExtCount)" else "Ekstensi",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setExtensionsHubVisible(true) }
                )
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
                    icon = Icons.Outlined.SurroundSound,
                    label = if (dolbyAtmosEnabled || dolbyVisionEnabled) "Dolby ON" else "Dolby OFF",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setDolbyDialogVisible(true) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dolby Atmos & Dolby Vision Live Hardware & Control Card on Home Screen
            val dolbyCardInteraction = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .interactiveLiquidGlass(
                        isActive = dolbyAtmosEnabled || dolbyVisionEnabled,
                        interactionSource = dolbyCardInteraction,
                        shape = RoundedCornerShape(20.dp),
                        elevation = 3.dp
                    )
                    .clickable(
                        interactionSource = dolbyCardInteraction,
                        indication = null
                    ) { viewModel.setDolbyDialogVisible(true) },
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    com.example.ui.components.DolbyAnimatedWaveBadge(
                        isActive = dolbyAtmosEnabled || dolbyVisionEnabled
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dolby Atmos & Dolby Vision",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Atmos: ${if (dolbyAtmosEnabled) "ON" else "OFF"} (${if (dolbyCapabilities.isNativeAtmosSupported) "Native" else "3D DSP"}) • Vision: ${if (dolbyVisionEnabled) "ON" else "OFF"} (${if (dolbyCapabilities.isNativeVisionSupported) "HDR Native" else "PQ Shader"})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Atur",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Saved Bookmarks Section (if user has bookmarks)
            if (bookmarks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(shape = RoundedCornerShape(20.dp), elevation = 2.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
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
                                modifier = Modifier.heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Lihat Semua", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        bookmarks.take(3).forEach { bmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(bmark.url) }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
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
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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

            // Continue Browsing / Recent History Card
            if (recentHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(shape = RoundedCornerShape(20.dp), elevation = 2.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
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
                                    text = "Baru saja dikunjungi",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = { viewModel.setHistoryVisible(true) },
                                modifier = Modifier.heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Lihat Semua", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        recentHistory.take(3).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(item.url) }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
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
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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

            // AlexShield & Privacy Guard Report Card
            ChromePrivacySummaryCard(
                adBlockEnabled = adBlockEnabled,
                adBlockModeName = adBlockMode.name,
                trackerBlockEnabled = trackerBlockEnabled,
                trackersBlockedCount = trackersBlockedCount,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 4 AI Collaborator Attribution (Gemini, Claude, ChatGPT, Meta Muse)
            DeveloperAiAttributionSection(
                isCompact = !isWide,
                showCardContainer = true
            )

            Spacer(modifier = Modifier.height(24.dp))
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
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = tileInteraction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .interactiveLiquidGlass(
                    interactionSource = tileInteraction,
                    shape = CircleShape,
                    elevation = 3.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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
            .height(56.dp)
            .interactiveLiquidGlass(
                interactionSource = btnInteraction,
                shape = RoundedCornerShape(18.dp),
                elevation = 3.dp
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
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
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
        modifier = modifier.interactiveLiquidGlass(shape = RoundedCornerShape(20.dp), elevation = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AlexShield & Privacy Guard",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (trackersBlockedCount > 0) "$trackersBlockedCount iklan & pelacak diblokir lintas situs"
                        else "Perlindungan iklan & pelacak real-time aktif",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromePrivacyChip(
                    label = "AdShield: ${if (adBlockEnabled) adBlockModeName else "Nonaktif"}",
                    isActive = adBlockEnabled,
                    modifier = Modifier.weight(1f)
                )
                ChromePrivacyChip(
                    label = "Pelacak: ${if (trackerBlockEnabled) "Diblokir" else "Diizinkan"}",
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
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
                .widthIn(max = if (isWide) 620.dp else maxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (maxWidth < 340.dp) 14.dp else 24.dp, vertical = if (isCompactHeight) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onExitIncognito,
                    modifier = Modifier.heightIn(min = 44.dp)
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
                    .size(if (isCompactHeight) 56.dp else 72.dp)
                    .clip(CircleShape)
                    .background(ChromeIncognitoOmnibox),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Mode Penyamaran",
                    tint = ChromeIncognitoText,
                    modifier = Modifier.size(if (isCompactHeight) 28.dp else 36.dp)
                )
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 16.dp))

            Text(
                text = "Mode Penyamaran (Incognito)",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal),
                color = ChromeIncognitoText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 20.dp))

            // Incognito Omnibox Search Pill with Liquid Glass
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

            Spacer(modifier = Modifier.height(if (isCompactHeight) 16.dp else 24.dp))

            Text(
                text = "Tab samaran tidak menyimpan riwayat penjelajahan, penyimpanan DOM persisten (localStorage), maupun data formulir, serta memblokir cookie pihak ketiga. Catatan: Android WebView berbagi satu mesin cookie pihak pertama lintas tab; cookie sesi akan dibersihkan otomatis saat seluruh tab samaran ditutup dan tidak ada tab reguler aktif.",
                style = MaterialTheme.typography.bodyMedium,
                color = ChromeIncognitoSubtext,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IncognitoInfoBlock(
                        title = "AdBlock Browser tidak menyimpan:",
                        bullets = listOf(
                            "Riwayat penjelajahan & daftar unduhan privat Anda",
                            "Penyimpanan DOM persisten (localStorage) & cookie pihak ketiga",
                            "Informasi yang dimasukkan pada formulir atau cache HTTP disk"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IncognitoInfoBlock(
                        title = "Aktivitas mungkin tetap terlihat oleh:",
                        bullets = listOf(
                            "Sesi login pihak pertama aktif di tab reguler (batasan Android WebView)",
                            "Situs web yang Anda kunjungi & penyedia layanan internet (ISP)",
                            "Administrator jaringan kantor atau sekolah Anda"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                IncognitoInfoBlock(
                    title = "AdBlock Browser tidak menyimpan:",
                    bullets = listOf(
                        "Riwayat penjelajahan & daftar unduhan privat Anda",
                        "Penyimpanan DOM persisten (localStorage) & cookie pihak ketiga",
                        "Informasi yang dimasukkan pada formulir atau cache HTTP disk"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                IncognitoInfoBlock(
                    title = "Aktivitas mungkin tetap terlihat oleh:",
                    bullets = listOf(
                        "Sesi login pihak pertama aktif di tab reguler (batasan Android WebView)",
                        "Situs web yang Anda kunjungi & penyedia layanan internet (ISP)",
                        "Administrator jaringan kantor atau sekolah Anda"
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
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ChromeIncognitoText
            )
            Spacer(modifier = Modifier.height(8.dp))
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

