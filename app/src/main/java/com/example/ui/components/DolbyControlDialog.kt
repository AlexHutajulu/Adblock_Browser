package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SpatialAudioOff
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.HdrEnhancedSelect
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.manager.DolbyAtmosProfile
import com.example.manager.DolbyDeviceCapabilities
import com.example.manager.DolbyVisionMode
import com.example.ui.theme.LiquidGlassRadioButton
import com.example.ui.theme.LiquidGlassSwitch
import com.example.ui.theme.interactiveLiquidGlass
import com.example.ui.theme.isAppInDarkTheme
import com.example.ui.viewmodel.BrowserViewModel

@Composable
fun DolbyControlDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()
    val atmosEnabled by viewModel.dolbyAtmosEnabled.collectAsState()
    val atmosProfile by viewModel.dolbyAtmosProfile.collectAsState()
    val visionEnabled by viewModel.dolbyVisionEnabled.collectAsState()
    val visionMode by viewModel.dolbyVisionMode.collectAsState()
    val capabilities by viewModel.dolbyDeviceCapabilities.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 680.dp)
                .interactiveLiquidGlass(
                    isActive = false,
                    shape = RoundedCornerShape(28.dp),
                    isDark = isDark,
                    elevation = 14.dp
                )
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val isAnyVerifiedActive = (atmosEnabled && capabilities.canEnableAtmos) ||
                            (visionEnabled && capabilities.canEnableDolbyVision)
                        DolbyAnimatedWaveBadge(isActive = isAnyVerifiedActive)
                        Column {
                            Text(
                                text = "Dolby Atmos & Dolby Vision",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    isAnyVerifiedActive ->
                                        "Jalur Dolby Native Terverifikasi Aktif"
                                    !capabilities.canEnableAtmos && !capabilities.canEnableDolbyVision ->
                                        "Fitur Native Tidak Tersedia pada Perangkat / Jalur Pemutaran Ini"
                                    else ->
                                        "Pemrosesan Dolby Native Nonaktif"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val closeInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .interactiveLiquidGlass(
                                interactionSource = closeInteraction,
                                shape = CircleShape,
                                elevation = 2.dp
                            )
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                onClick = onDismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Hardware Compatibility Detector Card
                DolbyHardwareDetectorCard(
                    capabilities = capabilities,
                    onRescan = {
                        viewModel.refreshDolbyDeviceCapabilities()
                        Toast.makeText(
                            context,
                            "Deteksi ulang kapabilitas perangkat selesai",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                // Dolby Atmos Card
                DolbyAtmosControlCard(
                    enabled = atmosEnabled && capabilities.canEnableAtmos,
                    canEnable = capabilities.canEnableAtmos,
                    disabledReason = capabilities.atmosDisabledReason,
                    selectedProfile = atmosProfile,
                    onToggle = { requested ->
                        val decision = viewModel.setDolbyAtmosEnabled(requested)
                        Toast.makeText(
                            context,
                            decision.reason,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onSelectProfile = { profile ->
                        viewModel.setDolbyAtmosProfile(profile)
                    }
                )

                // Dolby Vision Card
                DolbyVisionControlCard(
                    enabled = visionEnabled && capabilities.canEnableDolbyVision,
                    canEnable = capabilities.canEnableDolbyVision,
                    disabledReason = capabilities.visionDisabledReason,
                    selectedMode = visionMode,
                    onToggle = { requested ->
                        val decision = viewModel.setDolbyVisionEnabled(requested)
                        Toast.makeText(
                            context,
                            decision.reason,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onSelectMode = { mode ->
                        viewModel.setDolbyVisionMode(mode)
                    }
                )
            }
        }
    }
}

@Composable
fun DolbyAnimatedWaveBadge(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val pulse by animateFloatAsState(
        targetValue = if (isActive) 1.04f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "dolby_pulse"
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = if (isActive) {
                        listOf(Color(0xFF0B57D0), Color(0xFF7046FF))
                    } else {
                        listOf(Color(0xFF475569), Color(0xFF334155))
                    }
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.GraphicEq else Icons.Default.SpatialAudioOff,
            contentDescription = "Status Dolby",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun DolbyHardwareDetectorCard(
    capabilities: DolbyDeviceCapabilities,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDetails by remember { mutableStateOf(false) }
    val rescanInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .interactiveLiquidGlass(
                isActive = false,
                shape = RoundedCornerShape(20.dp),
                elevation = 3.dp
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Detektor Hardware Perangkat",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Pemeriksaan dekoder MediaCodec, DSP, & Layar HDR",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .height(34.dp)
                    .interactiveLiquidGlass(
                        interactionSource = rescanInteraction,
                        shape = RoundedCornerShape(100.dp),
                        elevation = 2.dp
                    )
                    .clickable(
                        interactionSource = rescanInteraction,
                        indication = null,
                        onClick = onRescan
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Deteksi Ulang",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Cek Ulang",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Status Baris 1: Dolby Atmos
        HardwareStatusBadgeRow(
            title = "Status Perangkat Dolby Atmos",
            badgeText = capabilities.atmosHardwareBadge,
            isNativeSupported = capabilities.isNativeAtmosSupported
        )

        // Status Baris 2: Dolby Vision
        HardwareStatusBadgeRow(
            title = "Status Perangkat Dolby Vision / HDR",
            badgeText = capabilities.visionHardwareBadge,
            isNativeSupported = capabilities.isNativeVisionSupported
        )

        Text(
            text = if (showDetails) "Sembunyikan Rincian Codec & Layar ▲" else "Lihat Rincian Codec & Layar (${capabilities.detectedAudioCapabilities.size + capabilities.detectedVideoCapabilities.size} modul) ▼",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showDetails = !showDetails }
                .padding(vertical = 4.dp)
        )

        AnimatedVisibility(
            visible = showDetails,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Modul Audio Terdeteksi:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                capabilities.detectedAudioCapabilities.forEach { cap ->
                    Text(
                        text = "• $cap",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Modul Visual & HDR Terdeteksi:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                capabilities.detectedVideoCapabilities.forEach { cap ->
                    Text(
                        text = "• $cap",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareStatusBadgeRow(
    title: String,
    badgeText: String,
    isNativeSupported: Boolean
) {
    val badgeColor = if (isNativeSupported) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor.copy(alpha = 0.10f))
            .border(1.dp, badgeColor.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isNativeSupported) Icons.Default.CheckCircle else Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = badgeColor
            )
        }
    }
}

@Composable
fun DolbyAtmosControlCard(
    enabled: Boolean,
    selectedProfile: DolbyAtmosProfile,
    onToggle: (Boolean) -> Unit,
    onSelectProfile: (DolbyAtmosProfile) -> Unit,
    modifier: Modifier = Modifier,
    canEnable: Boolean = false,
    disabledReason: String = "Dolby Atmos native tidak tersedia atau belum terverifikasi."
) {
    val effectiveEnabled = enabled && canEnable
    Column(
        modifier = modifier
            .fillMaxWidth()
            .interactiveLiquidGlass(
                isActive = effectiveEnabled,
                shape = RoundedCornerShape(22.dp),
                elevation = 4.dp
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.SurroundSound,
                    contentDescription = null,
                    tint = if (effectiveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Dolby Atmos (Native)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (canEnable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Text(
                        text = when {
                            effectiveEnabled -> "Aktif • ${selectedProfile.title}"
                            canEnable -> "Tersedia • Nonaktif (Klik untuk mengaktifkan)"
                            else -> disabledReason
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LiquidGlassSwitch(
                checked = effectiveEnabled,
                onCheckedChange = onToggle,
                enabled = canEnable || enabled
            )
        }

        AnimatedVisibility(
            visible = effectiveEnabled,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Pilih Profil Dolby Atmos:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DolbyAtmosProfile.entries.forEach { profile ->
                    val isSelected = profile == selectedProfile
                    val rowInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .interactiveLiquidGlass(
                                isActive = isSelected,
                                interactionSource = rowInteraction,
                                shape = RoundedCornerShape(14.dp),
                                elevation = if (isSelected) 3.dp else 1.dp
                            )
                            .clickable(
                                interactionSource = rowInteraction,
                                indication = null,
                                enabled = canEnable
                            ) { onSelectProfile(profile) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LiquidGlassRadioButton(
                            selected = isSelected,
                            onClick = { onSelectProfile(profile) },
                            enabled = canEnable
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = profile.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DolbyVisionControlCard(
    enabled: Boolean,
    selectedMode: DolbyVisionMode,
    onToggle: (Boolean) -> Unit,
    onSelectMode: (DolbyVisionMode) -> Unit,
    modifier: Modifier = Modifier,
    canEnable: Boolean = false,
    disabledReason: String = "Dolby Vision native tidak tersedia atau belum terverifikasi."
) {
    val effectiveEnabled = enabled && canEnable
    Column(
        modifier = modifier
            .fillMaxWidth()
            .interactiveLiquidGlass(
                isActive = effectiveEnabled,
                shape = RoundedCornerShape(22.dp),
                elevation = 4.dp
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.HdrOn,
                    contentDescription = null,
                    tint = if (effectiveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Dolby Vision (Native)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (canEnable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Text(
                        text = when {
                            effectiveEnabled -> "Aktif • ${selectedMode.title}"
                            canEnable -> "Tersedia • Nonaktif (Klik untuk mengaktifkan)"
                            else -> disabledReason
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LiquidGlassSwitch(
                checked = effectiveEnabled,
                onCheckedChange = onToggle,
                enabled = canEnable || enabled
            )
        }

        AnimatedVisibility(
            visible = effectiveEnabled,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Pilih Mode Dolby Vision:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DolbyVisionMode.entries.forEach { mode ->
                    val isSelected = mode == selectedMode
                    val rowInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .interactiveLiquidGlass(
                                isActive = isSelected,
                                interactionSource = rowInteraction,
                                shape = RoundedCornerShape(14.dp),
                                elevation = if (isSelected) 3.dp else 1.dp
                            )
                            .clickable(
                                interactionSource = rowInteraction,
                                indication = null,
                                enabled = canEnable
                            ) { onSelectMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LiquidGlassRadioButton(
                            selected = isSelected,
                            onClick = { onSelectMode(mode) },
                            enabled = canEnable
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = mode.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
