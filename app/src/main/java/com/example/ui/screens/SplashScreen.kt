package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AdBlockBrandEmblem(
    size: Dp = 96.dp,
    cornerRadius: Dp = 26.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color(0xFF428BFF).copy(alpha = 0.30f),
                spotColor = Color(0xFF6366F1).copy(alpha = 0.40f)
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = Color(0xFF080D19),
        border = BorderStroke(
            width = 1.25.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF60A5FA).copy(alpha = 0.65f),
                    Color(0xFF818CF8).copy(alpha = 0.35f)
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
    val loadProgress = remember { Animatable(0.08f) }
    val logoScale = remember { Animatable(0.88f) }

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
                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
            )
        }
        delay(980)
        onTimeout()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        val isCompactHeight = maxHeight < 520.dp

        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 28.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AdBlockBrandEmblem(
                    size = if (isCompactHeight) 76.dp else 96.dp,
                    cornerRadius = if (isCompactHeight) 22.dp else 28.dp,
                    modifier = Modifier.scale(logoScale.value)
                )

                Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 18.dp))

                Text(
                    text = "AdBlock Browser",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (isCompactHeight) 21.sp else 25.sp,
                        letterSpacing = (-0.3).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Fast · Simple · Ultra-Protected",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
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
                        .width(190.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(100.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Memuat mesin perisai generasi baru",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 6.dp)
            ) {
                DeveloperAiAttributionSection(
                    isCompact = true,
                    showCardContainer = true
                )
            }
        }
    }
}
