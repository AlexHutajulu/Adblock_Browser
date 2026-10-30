package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.interactiveLiquidGlass

@Composable
fun AntigravityLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.img_logo_antigravity),
        contentDescription = "Google Antigravity Official Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun GeminiLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.img_logo_gemini),
        contentDescription = "Gemini Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun ClaudeLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.img_logo_claude),
        contentDescription = "Claude Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun ChatGptLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.ic_logo_chatgpt),
        contentDescription = "ChatGPT Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun MetaMuseLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.img_logo_meta_muse),
        contentDescription = "Meta Muse Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun AiAssistantBadge(
    name: String,
    provider: String = "",
    iconBgColor: Color,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    isHighlighted: Boolean = false,
    logo: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.testTag("ai_badge_${name.lowercase().replace(" ", "_")}"),
        shape = RoundedCornerShape(16.dp),
        color = if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
        },
        tonalElevation = if (isHighlighted) 4.dp else 1.dp,
        border = BorderStroke(
            width = if (isHighlighted) 1.2.dp else 1.dp,
            color = if (isHighlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isCompact) 10.dp else 12.dp,
                vertical = if (isCompact) 6.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isCompact) 24.dp else 28.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                logo()
            }
            Spacer(modifier = Modifier.width(7.dp))
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 11.5.sp else 12.5.sp,
                        lineHeight = 14.sp
                    ),
                    color = if (isHighlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (provider.isNotBlank() && !isCompact) {
                    Text(
                        text = provider,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperAiAttributionSection(
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    showCardContainer: Boolean = true
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (showCardContainer) (if (isCompact) 12.dp else 16.dp) else 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Developer Credit Badge
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF3186FF).copy(alpha = 0.5f),
                            Color(0xFF00B95C).copy(alpha = 0.4f),
                            Color(0xFFFBBC04).copy(alpha = 0.4f),
                            Color(0xFFFC413D).copy(alpha = 0.5f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Developed by Alex Hutajulu",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 11.5.sp else 12.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Kolaborasi Resmi 5 AI (Antigravity • Gemini • Claude • ChatGPT • Meta Muse):",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (isCompact) 11.sp else 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 5 Official AI Collaborator Badges: Antigravity, Gemini, Claude, ChatGPT, Meta Muse
            val badgeIconSize: Dp = if (isCompact) 16.dp else 18.dp
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                AiAssistantBadge(
                    name = "Antigravity",
                    provider = "Google DeepMind",
                    iconBgColor = Color(0xFFFFFFFF),
                    isCompact = isCompact,
                    isHighlighted = true
                ) {
                    AntigravityLogoIcon(modifier = Modifier.size(badgeIconSize))
                }

                AiAssistantBadge(
                    name = "Gemini",
                    provider = "Google AI",
                    iconBgColor = Color(0xFF0B0F19),
                    isCompact = isCompact
                ) {
                    GeminiLogoIcon(modifier = Modifier.size(badgeIconSize))
                }

                AiAssistantBadge(
                    name = "Claude",
                    provider = "Anthropic",
                    iconBgColor = Color(0xFFFFF8F5),
                    isCompact = isCompact
                ) {
                    ClaudeLogoIcon(modifier = Modifier.size(badgeIconSize))
                }

                AiAssistantBadge(
                    name = "ChatGPT",
                    provider = "OpenAI",
                    iconBgColor = Color(0xFF000000),
                    isCompact = isCompact
                ) {
                    ChatGptLogoIcon(modifier = Modifier.size(badgeIconSize))
                }

                AiAssistantBadge(
                    name = "Meta Muse",
                    provider = "Meta AI",
                    iconBgColor = Color(0xFFEBF3FF),
                    isCompact = isCompact
                ) {
                    MetaMuseLogoIcon(modifier = Modifier.size(badgeIconSize))
                }
            }
        }
    }

    if (showCardContainer) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .interactiveLiquidGlass(
                    shape = RoundedCornerShape(24.dp),
                    elevation = 3.dp
                )
        ) {
            content()
        }
    } else {
        Box(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }
}
