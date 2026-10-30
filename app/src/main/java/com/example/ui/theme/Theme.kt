package com.example.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalIsDarkTheme = compositionLocalOf { false }
val LocalLiquidGlassEnabled = compositionLocalOf { true }

@Composable
fun isAppInDarkTheme(): Boolean {
    return LocalIsDarkTheme.current || MaterialTheme.colorScheme.background.luminance() < 0.5f
}

@Composable
fun isLiquidGlassEnabled(): Boolean {
    return LocalLiquidGlassEnabled.current
}

object AdBlockSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

@Stable
data class WindowLayoutProfile(
    val maxWidth: Dp,
    val maxHeight: Dp
) {
    // Mode Jendela Melayang (Floating Window) atau Split-Screen sangat kecil
    val isFloatingOrCompactHeight: Boolean get() = maxHeight < 560.dp || maxWidth < 360.dp
    // Standar unifikasi Tablet / Split-Screen Lebar (>= 600dp)
    val isTabletOrWide: Boolean get() = maxWidth >= 600.dp && maxHeight >= 480.dp
    // Mode Split-Screen Menengah (600dp - 839dp)
    val isMediumSplit: Boolean get() = maxWidth in 600.dp..839.dp
    // Lebar dinamis untuk panel samping agar tidak pernah memotong viewport utama
    val adaptiveSidebarWidth: Dp get() = when {
        maxWidth < 600.dp -> maxWidth
        maxWidth < 780.dp -> 250.dp
        else -> 300.dp
    }
}

/**
 * Interactive Liquid Glass Modifier (Sesuai Video Referensi 00:00 - 01:21):
 * - Efek cembung kaca 3-stop gradient (bebas dari bug bulatan anomali di tengah tombol/tab)
 * - Garis pantulan cahaya tajam (Specular Rim) di atas-kiri & bias kristal di bawah-kanan
 * - Pendaran cahaya kapsul merata (Full-Capsule Touch Sheen) & skala membal (GPU graphicsLayer Spring Physics)
 * - 100% GPU-Safe 120 FPS di atas Android WebView (tanpa RenderEffect blur & tanpa relayout saat ditekan)
 */
@Composable
fun Modifier.interactiveLiquidGlass(
    isActive: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = RoundedCornerShape(28.dp),
    isDark: Boolean = isAppInDarkTheme(),
    isIncognito: Boolean = false,
    elevation: Dp = 6.dp
): Modifier {
    val glassEnabled = isLiquidGlassEnabled()
    val effectiveDark = isDark || isIncognito

    // Jika Liquid Glass dimatikan (OFF) dari Pengaturan, gunakan permukaan solid Material 3 yang bersih
    if (!glassEnabled) {
        val solidBg = when {
            isActive && effectiveDark -> Color(0xFF1F3A60)
            isActive && !effectiveDark -> Color(0xFFD3E3FD)
            isIncognito -> ChromeIncognitoSurface
            effectiveDark -> Color(0xFF282A2E)
            else -> Color(0xFFF3F6FC)
        }
        val solidBorder = when {
            isActive && effectiveDark -> Color(0xFF8AB4F8)
            isActive && !effectiveDark -> Color(0xFF0B57D0)
            effectiveDark -> Color(0xFF444746)
            else -> Color(0xFFDADCE0)
        }
        return this
            .shadow(elevation = (elevation * 0.35f).coerceAtLeast(0.dp), shape = shape)
            .clip(shape)
            .background(color = solidBg, shape = shape)
            .border(width = 1.dp, color = solidBorder, shape = shape)
    }

    val isPressed by interactionSource.collectIsPressedAsState()

    // Fisika Pegas (Spring Scale) dijalankan murni di GPU RenderThread (graphicsLayer) tanpa relayout
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.955f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "liquid_scale"
    )

    // Pendaran Cahaya Sentuhan (HANYA saat disentuh, tanpa alokasi objek per-frame)
    val pressSheenAlpha by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = tween(140),
        label = "liquid_press_sheen"
    )

    // Warna optik berlapis di-cache penuh (Zero Coroutine Overhead pada ratusan komponen statis)
    val glassSurfaceBrush = remember(isActive, effectiveDark, isIncognito) {
        val topColor = when {
            isActive && effectiveDark -> GlassActiveDarkTop
            isActive && !effectiveDark -> GlassActiveLightTop
            isIncognito -> GlassSurfaceIncognitoTop
            effectiveDark -> GlassSurfaceDarkTop
            else -> GlassSurfaceLightTop
        }
        val upperMidColor = when {
            isActive && effectiveDark -> GlassActiveDarkMid
            isActive && !effectiveDark -> GlassActiveLightMid
            effectiveDark -> GlassSurfaceDarkMid
            else -> GlassSurfaceLightMid
        }
        val lowerMidColor = when {
            isActive && effectiveDark -> GlassActiveDarkMid.copy(alpha = 0.64f)
            isActive && !effectiveDark -> GlassActiveLightMid.copy(alpha = 0.76f)
            effectiveDark -> GlassSurfaceDarkMid.copy(alpha = 0.44f)
            else -> GlassSurfaceLightMid.copy(alpha = 0.65f)
        }
        val bottomColor = when {
            isActive && effectiveDark -> GlassActiveDarkBottom
            isActive && !effectiveDark -> GlassActiveLightBottom
            isIncognito -> GlassSurfaceIncognitoBottom
            effectiveDark -> GlassSurfaceDarkBottom
            else -> GlassSurfaceLightBottom
        }
        Brush.verticalGradient(colors = listOf(topColor, upperMidColor, lowerMidColor, bottomColor))
    }

    val rimBrush = remember(isActive, effectiveDark, isIncognito) {
        val rimHighlight = when {
            isActive && !effectiveDark -> Color(0xFFFFFFFF)
            isActive && effectiveDark -> Color(0xD9B8D4FF)
            isIncognito -> GlassBorderIncognitoHighlight
            effectiveDark -> GlassBorderDarkHighlight
            else -> GlassBorderLightHighlight
        }
        val rimUpperMid = when {
            isActive && !effectiveDark -> Color(0x993B82F6)
            isActive && effectiveDark -> Color(0x808AB4F8)
            effectiveDark -> Color.White.copy(alpha = 0.34f)
            else -> Color.White.copy(alpha = 0.72f)
        }
        val rimLowerMid = when {
            isActive && !effectiveDark -> Color(0x660B57D0)
            isActive && effectiveDark -> Color(0x5260A5FA)
            effectiveDark -> Color(0x3894A3B8)
            else -> Color(0x52CBD5E1)
        }
        val rimShadow = when {
            isActive && !effectiveDark -> Color(0x800B57D0)
            isActive && effectiveDark -> Color(0x8060A5FA)
            effectiveDark -> GlassBorderDarkShadow
            else -> GlassBorderLightShadow
        }
        Brush.linearGradient(
            colors = listOf(rimHighlight, rimUpperMid, rimLowerMid, rimShadow),
            start = Offset(0f, 0f),
            end = Offset(460f, 460f)
        )
    }

    val touchGlowColor = if (effectiveDark) LiquidTouchGlow else LiquidTouchGlowLight
    val effectiveElevation = (elevation * 0.45f).coerceAtLeast(0.dp)

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (effectiveElevation > 0.dp) {
                Modifier.shadow(
                    elevation = effectiveElevation,
                    shape = shape,
                    ambientColor = if (effectiveDark) Color.Black.copy(alpha = 0.55f) else Color(0xFF0F172A).copy(alpha = 0.14f),
                    spotColor = if (isActive) Color(0xFF0B57D0).copy(alpha = 0.28f) else Color(0xFF0F172A).copy(alpha = 0.18f)
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(brush = glassSurfaceBrush, shape = shape)
        .drawWithCache {
            val h = size.height.coerceAtLeast(1f)
            val w = size.width.coerceAtLeast(1f)

            // 1. Kubah Spekular Atas (Crown Optical Reflection) — di-cache per ukuran komponen
            val crownTopAlpha = if (effectiveDark) (if (isActive) 0.32f else 0.22f) else (if (isActive) 0.68f else 0.58f)
            val crownMidAlpha = if (effectiveDark) 0.06f else 0.18f
            val crownBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = crownTopAlpha),
                    Color.White.copy(alpha = crownMidAlpha),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.46f
            )

            // 2. Garis Kilau Kristal Puncak (Top 1.5dp Specular Micro-Crest)
            val crestHeight = 2.2.dp.toPx().coerceAtMost(h * 0.18f)
            val crestBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = if (effectiveDark) 0.42f else 0.82f),
                    Color.White.copy(alpha = if (effectiveDark) 0.52f else 0.92f),
                    Color.White.copy(alpha = if (effectiveDark) 0.42f else 0.82f),
                    Color.Transparent
                ),
                startX = w * 0.06f,
                endX = w * 0.94f
            )

            // 3. Bias Refraksi Prisik Bawah (Bottom Caustic Bounce Light)
            val causticColor = if (effectiveDark) {
                if (isActive) Color(0xFF8AB4F8).copy(alpha = 0.24f)
                else Color(0xFFA8C7FA).copy(alpha = 0.11f)
            } else {
                if (isActive) Color(0xFF0B57D0).copy(alpha = 0.16f)
                else Color(0xFF3B82F6).copy(alpha = 0.07f)
            }
            val bottomCausticBrush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, causticColor),
                startY = h * 0.62f,
                endY = h
            )

            // 4. Pendaran Cahaya Sentuhan (Pre-cached Diagonal Sheen Brush)
            val pressSheenBrush = Brush.linearGradient(
                colors = listOf(
                    touchGlowColor.copy(alpha = 0.22f),
                    touchGlowColor.copy(alpha = 0.42f),
                    touchGlowColor.copy(alpha = 0.22f)
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )

            onDrawWithContent {
                // Zero-allocation draw calls menggunakan Shader yang sudah di-cache
                drawRect(brush = crownBrush, size = Size(w, h * 0.46f))
                drawRect(brush = crestBrush, size = Size(w, crestHeight))
                drawRect(
                    brush = bottomCausticBrush,
                    topLeft = Offset(0f, h * 0.62f),
                    size = Size(w, h * 0.38f)
                )
                if (pressSheenAlpha > 0.01f) {
                    drawRect(brush = pressSheenBrush, alpha = pressSheenAlpha)
                }
                drawContent()
            }
        }
        .border(width = 1.25.dp, brush = rimBrush, shape = shape)
}

@Composable
fun Modifier.liquidGlass(
    isActive: Boolean = false,
    cornerRadius: Dp = 24.dp,
    isDark: Boolean = isAppInDarkTheme()
): Modifier = this.interactiveLiquidGlass(
    isActive = isActive,
    shape = RoundedCornerShape(cornerRadius),
    isDark = isDark,
    elevation = if (isActive) 3.dp else 6.dp
)

/**
 * Modifier GPU-Safe Liquid Glass (Shape-based): Memberikan permukaan kaca translusen halus
 * dengan garis pantulan cahaya tepi (specular edge highlight) tanpa menguras memori GPU.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    isIncognito: Boolean = false,
    elevation: Dp = 2.dp
): Modifier {
    val isDark = isAppInDarkTheme()
    return this.interactiveLiquidGlass(
        isActive = false,
        shape = shape,
        isDark = isDark,
        isIncognito = isIncognito,
        elevation = elevation
    )
}

@Composable
fun Modifier.liquidGlassBottomBar(
    isDark: Boolean = isAppInDarkTheme(),
    isIncognito: Boolean = false
): Modifier = this.interactiveLiquidGlass(
    isActive = false,
    shape = RoundedCornerShape(30.dp),
    isDark = isDark,
    isIncognito = isIncognito,
    elevation = 10.dp
)

/**
 * Lensa Kaca pada Switch (Sesuai Video Referensi Detik 00:32 - 00:39):
 * Jalur (track) dan kapsul thumb menggunakan interactiveLiquidGlass dengan animasi pegas.
 */
@Composable
fun LiquidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = isAppInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val trackTint by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            checked -> Color(0xFF34C759).copy(alpha = if (isDark) 0.78f else 0.88f)
            isDark -> Color(0xFF2B2E36).copy(alpha = 0.65f)
            else -> Color(0xFFDCE4F0).copy(alpha = 0.75f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "liquid_switch_track"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 3.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquid_switch_offset"
    )

    val thumbWidth by animateDpAsState(
        targetValue = if (isPressed) 30.dp else 26.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "liquid_switch_thumb_w"
    )

    Box(
        modifier = modifier
            .width(52.dp)
            .height(31.dp)
            .interactiveLiquidGlass(
                isActive = checked,
                interactionSource = interactionSource,
                shape = RoundedCornerShape(100.dp),
                isDark = isDark,
                elevation = 2.dp
            )
            .background(trackTint, RoundedCornerShape(100.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // Capsule Glass Lens Thumb
        Box(
            modifier = Modifier
                .offset(x = if (checked && isPressed) thumbOffset - 4.dp else thumbOffset)
                .width(thumbWidth)
                .height(25.dp)
                .interactiveLiquidGlass(
                    isActive = false,
                    interactionSource = interactionSource,
                    shape = RoundedCornerShape(100.dp),
                    isDark = false,
                    elevation = 4.dp
                )
        )
    }
}

/**
 * Lensa Kaca pada RadioButton:
 * Cincin luar dan titik cairan dalam menggunakan interactiveLiquidGlass dengan fisika pegas.
 */
@Composable
fun LiquidGlassRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = isAppInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val innerDotSize by animateDpAsState(
        targetValue = if (selected) 12.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquid_radio_dot"
    )

    Box(
        modifier = modifier
            .size(26.dp)
            .interactiveLiquidGlass(
                isActive = selected,
                interactionSource = interactionSource,
                shape = CircleShape,
                isDark = isDark,
                elevation = if (selected) 3.dp else 1.dp
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (innerDotSize > 0.5.dp) {
            Box(
                modifier = Modifier
                    .size(innerDotSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                            )
                        )
                    )
                    .border(0.8.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            )
        }
    }
}

/**
 * Lensa Kaca pada Slider (Sesuai Video Referensi Detik 00:54 - 00:57):
 * Kapsul track & thumb lensa kaca bening 3D di atas jalur aktif.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDark = isAppInDarkTheme()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .interactiveLiquidGlass(
                isActive = false,
                interactionSource = interactionSource,
                shape = RoundedCornerShape(18.dp),
                isDark = isDark,
                elevation = 2.dp
            )
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f)
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 22.dp)
                        .interactiveLiquidGlass(
                            isActive = true,
                            interactionSource = interactionSource,
                            shape = RoundedCornerShape(100.dp),
                            isDark = false,
                            elevation = 5.dp
                        )
                )
            }
        )
    }
}

/**
 * Lensa Kaca pada RangeSlider (Dual Thumb Capsule Glass Lens):
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 0
) {
    val startInteraction = remember { MutableInteractionSource() }
    val endInteraction = remember { MutableInteractionSource() }
    val isDark = isAppInDarkTheme()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .interactiveLiquidGlass(
                isActive = false,
                interactionSource = startInteraction,
                shape = RoundedCornerShape(18.dp),
                isDark = isDark,
                elevation = 2.dp
            )
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        RangeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            startInteractionSource = startInteraction,
            endInteractionSource = endInteraction,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f)
            ),
            startThumb = {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 22.dp)
                        .interactiveLiquidGlass(
                            isActive = true,
                            interactionSource = startInteraction,
                            shape = RoundedCornerShape(100.dp),
                            isDark = false,
                            elevation = 5.dp
                        )
                )
            },
            endThumb = {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 22.dp)
                        .interactiveLiquidGlass(
                            isActive = true,
                            interactionSource = endInteraction,
                            shape = RoundedCornerShape(100.dp),
                            isDark = false,
                            elevation = 5.dp
                        )
                )
            }
        )
    }
}


private val ChromeDarkColorScheme = darkColorScheme(
    primary = ChromeBlueDark,
    onPrimary = ChromeOnBlueDark,
    primaryContainer = ChromeBlueContainerDark,
    onPrimaryContainer = ChromeOnBlueContainerDark,
    secondary = ChromeGreenDark,
    onSecondary = Color(0xFF0A3818),
    secondaryContainer = Color(0xFF0F5223),
    onSecondaryContainer = Color(0xFFC4EED0),
    tertiary = GoogleYellow,
    onTertiary = Color(0xFF3E2D00),
    background = ChromeBgDark,
    onBackground = ChromeTextPrimaryDark,
    surface = ChromeSurfaceDark,
    onSurface = ChromeTextPrimaryDark,
    surfaceVariant = ChromeSurfaceContainerDark,
    onSurfaceVariant = ChromeTextSecondaryDark,
    surfaceContainerLowest = ChromeTabStripDark,
    surfaceContainerLow = ChromeSurfaceContainerDark,
    surfaceContainer = ChromeOmniboxDark,
    surfaceContainerHigh = Color(0xFF3B3D3F),
    outline = ChromeOutlineDark,
    outlineVariant = ChromeOutlineVariantDark,
    error = ChromeRedDark,
    onError = Color(0xFF601410)
)

private val ChromeLightColorScheme = lightColorScheme(
    primary = ChromeBlueLight,
    onPrimary = ChromeOnBlueLight,
    primaryContainer = ChromeBlueContainerLight,
    onPrimaryContainer = ChromeOnBlueContainerLight,
    secondary = ChromeGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4EED0),
    onSecondaryContainer = Color(0xFF072711),
    tertiary = ChromeYellow,
    onTertiary = Color.White,
    background = ChromeBgLight,
    onBackground = ChromeTextPrimaryLight,
    surface = ChromeSurfaceLight,
    onSurface = ChromeTextPrimaryLight,
    surfaceVariant = ChromeSurfaceContainerLight,
    onSurfaceVariant = ChromeTextSecondaryLight,
    surfaceContainerLowest = ChromeTabStripLight,
    surfaceContainerLow = ChromeSurfaceContainerLight,
    surfaceContainer = ChromeOmniboxLight,
    surfaceContainerHigh = Color(0xFFE1E6EE),
    outline = ChromeOutlineLight,
    outlineVariant = ChromeOutlineVariantLight,
    error = ChromeRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    liquidGlassEnabled: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ChromeDarkColorScheme
        else -> ChromeLightColorScheme
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalLiquidGlassEnabled provides liquidGlassEnabled
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}


