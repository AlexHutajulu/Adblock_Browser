package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.interactiveLiquidGlass
import com.example.ui.theme.isAppInDarkTheme
import com.example.ui.theme.isLiquidGlassEnabled

/**
 * Permukaan kaca cair optik berlapis yang ringan & stabil (Zero-Allocation DrawCache).
 * enabled=false memakai surface solid Material 3.
 * Tidak memakai RenderEffect blur, sehingga 100% stabil 60/120 FPS di semua perangkat.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    enabled: Boolean = isLiquidGlassEnabled(),
    elevation: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isAppInDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    if (!enabled) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), shape),
            content = content
        )
        return
    }
    Box(
        modifier = modifier.interactiveLiquidGlass(
            isActive = false,
            shape = shape,
            isDark = dark,
            elevation = elevation
        ),
        content = content
    )
}
