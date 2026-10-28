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
import com.example.ui.theme.isAppInDarkTheme
import com.example.ui.theme.isLiquidGlassEnabled

/**
 * Permukaan kaca cair yang ringan. enabled=false memakai surface solid.
 * Tidak memakai blur, sehingga aman untuk perangkat kelas bawah.
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
    val fill = remember(dark) {
        if (dark) {
            Brush.verticalGradient(listOf(Color(0xCC24262C), Color(0xB3181A1F)))
        } else {
            Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0xCCF2F4F8)))
        }
    }
    val rim = remember(dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.30f else 0.95f),
                Color.White.copy(alpha = 0.04f)
            )
        )
    }
    val base = modifier
        .shadow(if (enabled) elevation else 0.dp, shape, clip = false)
        .clip(shape)
    Box(
        modifier = if (enabled)
            base.background(fill).border(1.dp, rim, shape)
        else
            base.background(surface).border(1.dp, Color.Transparent, shape),
        content = content
    )
}
