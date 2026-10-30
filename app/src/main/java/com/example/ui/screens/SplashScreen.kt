package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.DeveloperAiAttributionSection
import com.example.ui.theme.interactiveLiquidGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AdBlockBrandEmblem(
    size: Dp = 96.dp,
    cornerRadius: Dp = 28.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color(0xFF3186FF).copy(alpha = 0.35f),
                spotColor = Color(0xFF6366F1).copy(alpha = 0.45f)
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = Color(0xFF070C18),
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF3186FF),
                    Color(0xFF00B95C),
                    Color(0xFFFBBC04),
                    Color(0xFFFC413D)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_alex_singularity),
                contentDescription = "Alex Browser Singularity Logo",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    val loadProgress = remember { Animatable(0.12f) }
    val logoScale = remember { Animatable(0.86f) }

    LaunchedEffect(Unit) {
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
            )
        }
        launch {
            loadProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 880, easing = FastOutSlowInEasing)
            )
        }
        delay(960)
        onTimeout()
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
            )
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        val isCompactHeight = maxHeight < 540.dp

        Column(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Feature Status Pill
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.padding(top = if (isCompactHeight) 4.dp else 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "ADSHIELD ULTRA  •  DOLBY ATMOS & VISION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Center Hero Bento Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (isCompactHeight) 8.dp else 14.dp)
                    .interactiveLiquidGlass(
                        shape = RoundedCornerShape(28.dp),
                        elevation = 6.dp
                    ),
                shape = RoundedCornerShape(28.dp),
                color = Color.Transparent
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 20.dp,
                            vertical = if (isCompactHeight) 16.dp else 26.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AdBlockBrandEmblem(
                        size = if (isCompactHeight) 74.dp else 94.dp,
                        cornerRadius = if (isCompactHeight) 22.dp else 28.dp,
                        modifier = Modifier.scale(logoScale.value)
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 18.dp))

                    Text(
                        text = "AdBlock Browser",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (isCompactHeight) 22.sp else 26.sp,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Next-Gen Mobile & Desktop Web Experience",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 14.dp else 22.dp))

                    LinearProgressIndicator(
                        progress = { loadProgress.value },
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Menyiapkan ruang kerja & perisai privasi (${(loadProgress.value * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Bottom 5-AI Collaboration Attribution Showcase
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                DeveloperAiAttributionSection(
                    isCompact = true,
                    showCardContainer = true
                )
            }
        }
    }
}
