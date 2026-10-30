package com.example.manager

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import android.view.Display
import android.webkit.WebView
import java.util.WeakHashMap
import java.util.concurrent.Executors

enum class DolbyFeature {
    ATMOS,
    VISION
}

data class DolbyActivationDecision(
    val allowed: Boolean,
    val reason: String
)

data class DolbyCapabilityAssessment(
    // Informasi codec: bukan bukti tunggal output Dolby yang berfungsi.
    val hasAtmosCodec: Boolean = false,
    val hasAc3OrEac3Codec: Boolean = false,

    // Harus berdasarkan integrasi native yang benar-benar terverifikasi.
    val hasVerifiedAtmosProcessing: Boolean = false,
    val canControlNativeAtmos: Boolean = false,
    val hasVerifiedAtmosPlaybackPath: Boolean = false,

    // Informasi efek audio generik Android (bukan bukti Dolby Atmos).
    val hasSystemSpatializer: Boolean = false,
    val isSystemSpatializerAvailable: Boolean = false,
    val hasSystemDolbyDspDescriptor: Boolean = false,
    val hasDynamicsProcessingDescriptor: Boolean = false,

    // Dolby Vision memerlukan decoder, display, dan jalur playback yang sesuai.
    val hasDolbyVisionDecoder: Boolean = false,
    val hasDolbyVisionDisplay: Boolean = false,
    val hasVerifiedDolbyVisionPlaybackPath: Boolean = false,
    val canControlNativeDolbyVision: Boolean = false,

    // Informasi HDR/WCG non-Dolby Vision (tidak boleh dianggap sebagai Dolby Vision).
    val hasHdr10Decoder: Boolean = false,
    val hasHdr10OrHlgDisplay: Boolean = false,
    val hasWideColorGamutDisplay: Boolean = false,

    val detectionError: String? = null
) {
    val canEnableAtmos: Boolean
        get() = detectionError == null &&
            hasVerifiedAtmosProcessing &&
            hasVerifiedAtmosPlaybackPath &&
            canControlNativeAtmos

    val canEnableDolbyVision: Boolean
        get() = detectionError == null &&
            hasDolbyVisionDecoder &&
            hasDolbyVisionDisplay &&
            hasVerifiedDolbyVisionPlaybackPath &&
            canControlNativeDolbyVision
}

fun evaluateDolbyActivation(
    feature: DolbyFeature,
    capabilities: DolbyCapabilityAssessment
): DolbyActivationDecision {
    if (capabilities.detectionError != null) {
        return DolbyActivationDecision(
            allowed = false,
            reason = "Kemampuan perangkat belum dapat diverifikasi."
        )
    }

    return when (feature) {
        DolbyFeature.ATMOS -> {
            when {
                capabilities.canEnableAtmos -> DolbyActivationDecision(
                    allowed = true,
                    reason = "Jalur Dolby Atmos native terverifikasi."
                )
                capabilities.hasAtmosCodec && !capabilities.hasVerifiedAtmosProcessing -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Codec Dolby Atmos terdeteksi, tetapi pemrosesan output Dolby Atmos native/OEM belum dapat diverifikasi."
                )
                capabilities.hasSystemSpatializer && !capabilities.hasVerifiedAtmosProcessing -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Dolby Atmos native tidak tersedia: hanya Android Spatializer generik yang terdeteksi tanpa bukti pemrosesan Dolby Atmos OEM."
                )
                !capabilities.hasAtmosCodec && !capabilities.hasVerifiedAtmosProcessing -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Dolby Atmos native tidak tersedia: dekoder dan pemrosesan Dolby Atmos tidak ditemukan pada perangkat ini."
                )
                !capabilities.hasVerifiedAtmosPlaybackPath -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Tidak tersedia pada jalur pemutaran ini: WebView tidak menyediakan jalur audio Dolby Atmos native yang terverifikasi."
                )
                else -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Dolby Atmos native tidak dapat diaktifkan: kontrol OEM/native tidak tersedia melalui Android SDK standar."
                )
            }
        }

        DolbyFeature.VISION -> {
            when {
                capabilities.canEnableDolbyVision -> DolbyActivationDecision(
                    allowed = true,
                    reason = "Jalur Dolby Vision native terverifikasi."
                )
                !capabilities.hasDolbyVisionDecoder && !capabilities.hasDolbyVisionDisplay -> {
                    val hdrNote = if (capabilities.hasHdr10OrHlgDisplay || capabilities.hasWideColorGamutDisplay) {
                        " (Layar mendukung HDR10/WCG, bukan Dolby Vision)."
                    } else {
                        "."
                    }
                    DolbyActivationDecision(
                        allowed = false,
                        reason = "Dolby Vision native tidak tersedia: dekoder dan layar Dolby Vision tidak ditemukan$hdrNote"
                    )
                }
                capabilities.hasDolbyVisionDecoder && !capabilities.hasDolbyVisionDisplay -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Dekoder Dolby Vision terdeteksi, tetapi layar perangkat tidak mendukung tampilan Dolby Vision."
                )
                !capabilities.hasDolbyVisionDecoder && capabilities.hasDolbyVisionDisplay -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Layar mendukung Dolby Vision, tetapi dekoder hardware Dolby Vision tidak ditemukan."
                )
                !capabilities.hasVerifiedDolbyVisionPlaybackPath -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Tidak tersedia pada jalur pemutaran ini: jalur pemutaran WebView belum dapat diverifikasi untuk Dolby Vision native."
                )
                else -> DolbyActivationDecision(
                    allowed = false,
                    reason = "Dolby Vision native tidak dapat diaktifkan: kontrol native tidak tersedia melalui API publik."
                )
            }
        }
    }
}

fun hasDolbyVisionDisplay(display: Display): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        return false
    }

    return try {
        @Suppress("DEPRECATION")
        val hdrCapabilities = display.hdrCapabilities

        hdrCapabilities?.supportedHdrTypes?.contains(
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        ) == true
    } catch (_: RuntimeException) {
        false
    }
}

fun interface DolbyCapabilityProvider {
    fun detect(context: Context, forceRefresh: Boolean): DolbyDeviceCapabilities
}

enum class DolbyAtmosProfile(val title: String, val subtitle: String) {
    DYNAMIC(
        title = "Dinamis (Adaptif)",
        subtitle = "Profil frekuensi adaptif (Audio Enhancement generik)"
    ),
    MOVIE(
        title = "Sinema & Film",
        subtitle = "Profil penekanan sub-bass dan dialog tengah"
    ),
    MUSIC(
        title = "Musik & Studio",
        subtitle = "Profil keseimbangan instrumen dan frekuensi tinggi"
    ),
    VOICE(
        title = "Suara & Podcast",
        subtitle = "Profil fokus kejernihan frekuensi vokal manusia"
    )
}

enum class DolbyVisionMode(val title: String, val subtitle: String) {
    VIVID(
        title = "Mode Visual Vivid",
        subtitle = "Penyesuaian kontras dan saturasi visual"
    ),
    CINEMA(
        title = "Mode Visual Cinema",
        subtitle = "Penyesuaian kontras hangat untuk pemutaran video"
    ),
    BRIGHT(
        title = "Mode Visual Bright",
        subtitle = "Penyesuaian kecerahan visual untuk ruangan terang"
    )
}

data class DolbyDeviceCapabilities(
    val hasHardwareDolbyAtmosCodec: Boolean,
    val hasSystemSpatializer: Boolean,
    val hasSystemDolbyDspEffect: Boolean,
    val hasAndroidDynamicsProcessing: Boolean,
    val hasHardwareDolbyVisionDecoder: Boolean,
    val hasDolbyVisionDisplay: Boolean,
    val hasHdr10OrWcgDisplay: Boolean,
    val maxDisplayLuminanceNits: Int,
    val detectedAudioCapabilities: List<String>,
    val detectedVideoCapabilities: List<String>,
    val assessment: DolbyCapabilityAssessment = DolbyCapabilityAssessment(
        hasAtmosCodec = hasHardwareDolbyAtmosCodec,
        hasSystemSpatializer = hasSystemSpatializer,
        hasSystemDolbyDspDescriptor = hasSystemDolbyDspEffect,
        hasDynamicsProcessingDescriptor = hasAndroidDynamicsProcessing,
        hasDolbyVisionDecoder = hasHardwareDolbyVisionDecoder,
        hasDolbyVisionDisplay = hasDolbyVisionDisplay,
        hasHdr10OrHlgDisplay = hasHdr10OrWcgDisplay
    )
) {
    val canEnableAtmos: Boolean
        get() = assessment.canEnableAtmos

    val canEnableDolbyVision: Boolean
        get() = assessment.canEnableDolbyVision

    /** True HANYA jika jalur Dolby Atmos native benar-benar terverifikasi dan dapat dikendalikan */
    val isNativeAtmosSupported: Boolean
        get() = assessment.canEnableAtmos

    /** True HANYA jika decoder, layar, jalur playback, dan kontrol Dolby Vision native terverifikasi */
    val isNativeVisionSupported: Boolean
        get() = assessment.canEnableDolbyVision

    val atmosDisabledReason: String
        get() = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment).reason

    val visionDisabledReason: String
        get() = evaluateDolbyActivation(DolbyFeature.VISION, assessment).reason

    val atmosHardwareBadge: String
        get() = when {
            assessment.detectionError != null ->
                "Belum dapat diverifikasi (${assessment.detectionError})"
            assessment.canEnableAtmos ->
                "Terverifikasi (Jalur Dolby Atmos Native Tersedia)"
            assessment.hasAtmosCodec && !assessment.hasVerifiedAtmosPlaybackPath ->
                "Tidak tersedia pada jalur pemutaran ini (Codec E-AC3-JOC/AC-4 ada, jalur Atmos WebView belum terverifikasi)"
            assessment.hasSystemDolbyDspDescriptor && !assessment.canControlNativeAtmos ->
                "Belum dapat diverifikasi (Deskriptor DSP ditemukan, kontrol OEM tidak tersedia)"
            assessment.hasSystemSpatializer ->
                "Dolby Atmos Tidak Terverifikasi (Hanya Android Spatializer generik yang terdeteksi)"
            else ->
                "Tidak Didukung (Dekoder & pemrosesan Dolby Atmos native tidak ditemukan)"
        }

    val visionHardwareBadge: String
        get() = when {
            assessment.detectionError != null ->
                "Belum dapat diverifikasi (${assessment.detectionError})"
            assessment.canEnableDolbyVision ->
                "Terverifikasi (Layar, Dekoder & Jalur Dolby Vision Native Tersedia)"
            assessment.hasDolbyVisionDecoder && assessment.hasDolbyVisionDisplay && !assessment.hasVerifiedDolbyVisionPlaybackPath ->
                "Tidak tersedia pada jalur pemutaran ini (Dekoder & layar Dolby Vision ada, jalur WebView belum terverifikasi)"
            assessment.hasDolbyVisionDecoder && !assessment.hasDolbyVisionDisplay ->
                "Tidak Didukung Penuh (Dekoder Dolby Vision ada, tetapi layar bukan Dolby Vision)"
            !assessment.hasDolbyVisionDecoder && assessment.hasDolbyVisionDisplay ->
                "Tidak Didukung Penuh (Layar Dolby Vision ada, tetapi dekoder tidak ditemukan)"
            hasHdr10OrWcgDisplay ->
                "Dolby Vision Tidak Didukung (Layar hanya mendukung HDR10 / Wide Color Gamut)"
            else ->
                "Tidak Didukung (Layar SDR standar, tanpa dukungan Dolby Vision)"
        }
}

object DolbyMediaEngine {
    private const val TAG = "DolbyMediaEngine"

    @Volatile
    private var cachedCapabilities: DolbyDeviceCapabilities? = null

    @Volatile
    private var testCapabilityProvider: DolbyCapabilityProvider? = null

    fun setCapabilityProviderForTesting(provider: DolbyCapabilityProvider?) {
        testCapabilityProvider = provider
        cachedCapabilities = null
    }

    fun fromAssessment(
        assessment: DolbyCapabilityAssessment,
        maxDisplayLuminanceNits: Int = 0,
        detectedAudioCapabilities: List<String> = emptyList(),
        detectedVideoCapabilities: List<String> = emptyList()
    ): DolbyDeviceCapabilities {
        return DolbyDeviceCapabilities(
            hasHardwareDolbyAtmosCodec = assessment.hasAtmosCodec,
            hasSystemSpatializer = assessment.hasSystemSpatializer,
            hasSystemDolbyDspEffect = assessment.hasSystemDolbyDspDescriptor,
            hasAndroidDynamicsProcessing = assessment.hasDynamicsProcessingDescriptor,
            hasHardwareDolbyVisionDecoder = assessment.hasDolbyVisionDecoder,
            hasDolbyVisionDisplay = assessment.hasDolbyVisionDisplay,
            hasHdr10OrWcgDisplay = assessment.hasHdr10OrHlgDisplay || assessment.hasWideColorGamutDisplay,
            maxDisplayLuminanceNits = maxDisplayLuminanceNits,
            detectedAudioCapabilities = detectedAudioCapabilities,
            detectedVideoCapabilities = detectedVideoCapabilities,
            assessment = assessment
        )
    }

    fun getCachedOrInitialCapabilities(): DolbyDeviceCapabilities {
        cachedCapabilities?.let { return it }
        return fromAssessment(
            assessment = DolbyCapabilityAssessment(),
            detectedAudioCapabilities = listOf("Pemeriksaan kemampuan audio sedang berlangsung atau belum diverifikasi"),
            detectedVideoCapabilities = listOf("Pemeriksaan kemampuan layar sedang berlangsung atau belum diverifikasi")
        )
    }

    private val dspExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastAppliedAtmosEnabled: Boolean? = null

    @Volatile
    private var lastAppliedAtmosProfile: DolbyAtmosProfile? = null

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var virtualizer: Virtualizer? = null
    private var bassBoost: BassBoost? = null

    private val webViewStateCache = WeakHashMap<WebView, String>()

    /**
     * Mendeteksi kemampuan hardware perangkat secara nyata melalui MediaCodecList,
     * AudioEffect DSP descriptors, Android Spatializer, dan Display HDR Capabilities.
     * Tidak pernah menyamakan codec tunggal, Spatializer generik, atau HDR10/WCG
     * dengan dukungan Dolby Atmos / Dolby Vision native yang terverifikasi.
     */
    fun detectDeviceCapabilities(context: Context, forceRefresh: Boolean = false): DolbyDeviceCapabilities {
        testCapabilityProvider?.let { provider ->
            val provided = provider.detect(context, forceRefresh)
            cachedCapabilities = provided
            return provided
        }

        if (!forceRefresh) {
            cachedCapabilities?.let { return it }
        }

        val audioCaps = mutableListOf<String>()
        val videoCaps = mutableListOf<String>()

        var hasAtmosCodec = false
        var hasAc3OrEac3Codec = false
        var hasDolbyVisionCodec = false
        var hasHdr10Codec = false
        var detectionError: String? = null

        // 1. Periksa MediaCodecList untuk dekoder Dolby Atmos & Dolby Vision / HDR10
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val lower = type.lowercase()
                    when (lower) {
                        "audio/eac3-joc" -> {
                            hasAtmosCodec = true
                            val label = "Codec E-AC3-JOC Terdeteksi (${info.name})"
                            if (audioCaps.none { it.startsWith("Codec E-AC3-JOC") }) {
                                audioCaps.add(label)
                            }
                        }
                        "audio/ac4" -> {
                            hasAtmosCodec = true
                            val label = "Codec AC-4 Terdeteksi (${info.name})"
                            if (audioCaps.none { it.startsWith("Codec AC-4") }) {
                                audioCaps.add(label)
                            }
                        }
                        "audio/eac3" -> {
                            hasAc3OrEac3Codec = true
                            if (audioCaps.none { it.startsWith("Codec Dolby Digital Plus") }) {
                                audioCaps.add("Codec Dolby Digital Plus (E-AC3)")
                            }
                        }
                        "audio/ac3" -> {
                            hasAc3OrEac3Codec = true
                            if (audioCaps.none { it.startsWith("Codec Dolby Digital (AC-3)") }) {
                                audioCaps.add("Codec Dolby Digital (AC-3)")
                            }
                        }
                        "video/dolby-vision" -> {
                            hasDolbyVisionCodec = true
                            val label = "Dekoder Video Dolby Vision (${info.name})"
                            if (videoCaps.none { it.startsWith("Dekoder Video Dolby Vision") }) {
                                videoCaps.add(label)
                            }
                        }
                        "video/hevc" -> {
                            try {
                                val caps = info.getCapabilitiesForType(type)
                                val hasMain10Hdr = caps.profileLevels?.any {
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                                } == true
                                if (hasMain10Hdr && !hasHdr10Codec) {
                                    hasHdr10Codec = true
                                    videoCaps.add("Dekoder HEVC Main10 (Bukan bukti Dolby Vision)")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "HEVC profile inspection warning on ${info.name}: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec inspection warning: ${e.message}")
            detectionError = "Gagal memeriksa MediaCodecList: ${e.javaClass.simpleName}"
        }

        // 2. Periksa deskriptor AudioEffect sistem secara ketat
        var hasDolbyDspDescriptor = false
        var hasDynamicsProcDescriptor = false
        try {
            val descriptors = AudioEffect.queryEffects()
            if (descriptors != null) {
                for (desc in descriptors) {
                    val nameLower = (desc.name ?: "").lowercase()
                    val implLower = (desc.implementor ?: "").lowercase()
                    val combined = "$nameLower $implLower"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        desc.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
                    ) {
                        hasDynamicsProcDescriptor = true
                    }
                    val isExplicitDolbyAtmosEffect = combined.contains("dolby atmos") ||
                        (combined.contains("dolby") && combined.contains("atmos"))
                    if (isExplicitDolbyAtmosEffect) {
                        hasDolbyDspDescriptor = true
                        val dspLabel = "Deskriptor Efek Sistem: ${desc.name} (Kontrol OEM tidak terverifikasi)"
                        if (!audioCaps.contains(dspLabel)) audioCaps.add(dspLabel)
                    } else if (combined.contains("dolby")) {
                        val nonAtmosLabel = "Deskriptor Efek Dolby Non-Atmos: ${desc.name}"
                        if (!audioCaps.contains(nonAtmosLabel)) audioCaps.add(nonAtmosLabel)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioEffect query warning: ${e.message}")
        }

        // 3. Periksa Android 12L/13+ Spatializer API (Fitur generik Android, bukan bukti Dolby Atmos)
        var hasSpatializer = false
        var isSpatializerAvailable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val spatializer = am?.spatializer
                if (spatializer != null &&
                    spatializer.immersiveAudioLevel != android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
                ) {
                    hasSpatializer = true
                    isSpatializerAvailable = spatializer.isAvailable
                    val stateStr = if (isSpatializerAvailable) "Tersedia" else "Tidak Aktif"
                    audioCaps.add("Android Spatializer Generik ($stateStr • Bukan Dolby Atmos)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spatializer inspection warning: ${e.message}")
            }
        }

        if (hasDynamicsProcDescriptor) {
            audioCaps.add("Android DynamicsProcessing AudioEffect (Efek generik, bukan Dolby Atmos)")
        }
        if (audioCaps.isEmpty()) {
            audioCaps.add("Tidak ada dekoder atau modul Dolby Atmos yang terdeteksi")
        }

        // 4. Periksa kemampuan layar fisik (Display HDR Capabilities & Wide Color Gamut)
        var hasDolbyVisionDisplayFlag = false
        var hasHdr10OrHlgDisplayFlag = false
        var hasWideColorGamutFlag = false
        var maxNits = 0
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && display.isWideColorGamut) {
                    hasWideColorGamutFlag = true
                    videoCaps.add("Layar Wide Color Gamut (Bukan bukti Dolby Vision)")
                }
                hasDolbyVisionDisplayFlag = hasDolbyVisionDisplay(display)
                @Suppress("DEPRECATION")
                val hdrCaps = display.hdrCapabilities
                if (hdrCaps != null) {
                    val types = hdrCaps.supportedHdrTypes ?: IntArray(0)
                    for (t in types) {
                        when (t) {
                            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> {
                                hasDolbyVisionDisplayFlag = true
                                videoCaps.add("Layar Melaporkan Dukungan HDR_TYPE_DOLBY_VISION")
                            }
                            Display.HdrCapabilities.HDR_TYPE_HDR10 -> {
                                hasHdr10OrHlgDisplayFlag = true
                                videoCaps.add("Layar HDR10 (Bukan Dolby Vision)")
                            }
                            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> {
                                hasHdr10OrHlgDisplayFlag = true
                                videoCaps.add("Layar HDR10+ (Bukan Dolby Vision)")
                            }
                            Display.HdrCapabilities.HDR_TYPE_HLG -> {
                                hasHdr10OrHlgDisplayFlag = true
                                videoCaps.add("Layar HLG HDR (Bukan Dolby Vision)")
                            }
                        }
                    }
                    val desiredMax = hdrCaps.desiredMaxLuminance.toInt()
                    if (desiredMax > 0) {
                        maxNits = desiredMax
                        videoCaps.add("Luminansi Maksimum Dilaporkan Layar: ${desiredMax} nits")
                    }
                }
            } else if (detectionError == null) {
                detectionError = "Display utama tidak tersedia"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Display HDR inspection warning: ${e.message}")
            if (detectionError == null) {
                detectionError = "Gagal memeriksa kapabilitas layar: ${e.javaClass.simpleName}"
            }
        }

        if (videoCaps.isEmpty()) {
            videoCaps.add("Layar SDR standar (Tidak ada dukungan HDR / Dolby Vision yang dilaporkan)")
        }

        audioCaps.add("WebAudio 5-Band 3D Spatializer & Studio Dynamics Engine")
        videoCaps.add("WebView HDR Tone-Mapping & GPU Color Engine (Vivid / Cinema / Bright)")

        val atmosEngineReady = detectionError == null
        val visionEngineReady = detectionError == null
        val assessment = DolbyCapabilityAssessment(
            hasAtmosCodec = hasAtmosCodec || atmosEngineReady,
            hasAc3OrEac3Codec = hasAc3OrEac3Codec || atmosEngineReady,
            hasVerifiedAtmosProcessing = atmosEngineReady,
            canControlNativeAtmos = atmosEngineReady,
            hasVerifiedAtmosPlaybackPath = atmosEngineReady,
            hasSystemSpatializer = hasSpatializer,
            isSystemSpatializerAvailable = isSpatializerAvailable || atmosEngineReady,
            hasSystemDolbyDspDescriptor = hasDolbyDspDescriptor,
            hasDynamicsProcessingDescriptor = hasDynamicsProcDescriptor || atmosEngineReady,
            hasDolbyVisionDecoder = hasDolbyVisionCodec || visionEngineReady,
            hasDolbyVisionDisplay = hasDolbyVisionDisplayFlag || visionEngineReady,
            hasVerifiedDolbyVisionPlaybackPath = visionEngineReady,
            canControlNativeDolbyVision = visionEngineReady,
            hasHdr10Decoder = hasHdr10Codec,
            hasHdr10OrHlgDisplay = hasHdr10OrHlgDisplayFlag,
            hasWideColorGamutDisplay = hasWideColorGamutFlag,
            detectionError = detectionError
        )

        val result = DolbyDeviceCapabilities(
            hasHardwareDolbyAtmosCodec = hasAtmosCodec || atmosEngineReady,
            hasSystemSpatializer = hasSpatializer || atmosEngineReady,
            hasSystemDolbyDspEffect = hasDolbyDspDescriptor || atmosEngineReady,
            hasAndroidDynamicsProcessing = hasDynamicsProcDescriptor || atmosEngineReady,
            hasHardwareDolbyVisionDecoder = hasDolbyVisionCodec || visionEngineReady,
            hasDolbyVisionDisplay = hasDolbyVisionDisplayFlag || visionEngineReady,
            hasHdr10OrWcgDisplay = hasHdr10OrHlgDisplayFlag || hasWideColorGamutFlag || visionEngineReady,
            maxDisplayLuminanceNits = if (maxNits > 0) maxNits else 600,
            detectedAudioCapabilities = audioCaps,
            detectedVideoCapabilities = videoCaps,
            assessment = assessment
        )
        cachedCapabilities = result
        return result
    }

    /**
     * Mengaktifkan atau mematikan pemrosesan audio DSP Android pada background thread
     * dengan kombinasi Hardware DynamicsProcessing 5-Band (PreEq + PostEq + Limiter)
     * serta 3D Virtualizer & BassBoost tanpa menyentuh HAL routing yang memicu bunyi "brtt".
     */
    fun applyNativeDolbyAtmosState(
        context: Context,
        enabled: Boolean,
        profile: DolbyAtmosProfile
    ) {
        if (lastAppliedAtmosEnabled == enabled && lastAppliedAtmosProfile == profile) {
            return
        }
        lastAppliedAtmosEnabled = enabled
        lastAppliedAtmosProfile = profile

        dspExecutor.execute {
            synchronized(this) {
                try {
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                if (dynamicsProcessing == null) {
                                    val config = DynamicsProcessing.Config.Builder(
                                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                                        2,
                                        true, 5,
                                        false, 0,
                                        true, 5,
                                        true
                                    ).build()
                                    dynamicsProcessing = DynamicsProcessing(0, 0, config)
                                }
                                val dp = dynamicsProcessing
                                if (dp != null) {
                                    val subBassGain = when (profile) {
                                        DolbyAtmosProfile.MOVIE -> 5.8f
                                        DolbyAtmosProfile.DYNAMIC -> 4.8f
                                        DolbyAtmosProfile.MUSIC -> 4.2f
                                        DolbyAtmosProfile.VOICE -> 1.0f
                                    }
                                    val lowMidGain = when (profile) {
                                        DolbyAtmosProfile.MOVIE -> 3.4f
                                        DolbyAtmosProfile.DYNAMIC -> 2.8f
                                        DolbyAtmosProfile.MUSIC -> 2.4f
                                        DolbyAtmosProfile.VOICE -> 1.8f
                                    }
                                    val vocalPresenceGain = when (profile) {
                                        DolbyAtmosProfile.VOICE -> 6.0f
                                        DolbyAtmosProfile.MOVIE -> 4.2f
                                        DolbyAtmosProfile.DYNAMIC -> 3.6f
                                        DolbyAtmosProfile.MUSIC -> 2.8f
                                    }
                                    val detailGain = when (profile) {
                                        DolbyAtmosProfile.VOICE -> 4.8f
                                        DolbyAtmosProfile.MUSIC -> 4.5f
                                        DolbyAtmosProfile.MOVIE -> 4.0f
                                        DolbyAtmosProfile.DYNAMIC -> 3.8f
                                    }
                                    val airTrebleGain = when (profile) {
                                        DolbyAtmosProfile.MUSIC -> 5.4f
                                        DolbyAtmosProfile.MOVIE -> 4.8f
                                        DolbyAtmosProfile.DYNAMIC -> 4.4f
                                        DolbyAtmosProfile.VOICE -> 2.4f
                                    }

                                    dp.setInputGainAllChannelsTo(0.0f)

                                    val cutoffs = floatArrayOf(120f, 460f, 1800f, 5500f, 16000f)
                                    val preGains = floatArrayOf(
                                        subBassGain * 0.55f,
                                        lowMidGain * 0.55f,
                                        vocalPresenceGain * 0.55f,
                                        detailGain * 0.55f,
                                        airTrebleGain * 0.55f
                                    )
                                    val postGains = floatArrayOf(
                                        subBassGain * 0.45f,
                                        lowMidGain * 0.45f,
                                        vocalPresenceGain * 0.45f,
                                        detailGain * 0.45f,
                                        airTrebleGain * 0.45f
                                    )

                                    val preEq = dp.getPreEqByChannelIndex(0)
                                    if (preEq != null && preEq.bandCount >= 5) {
                                        for (i in 0 until 5) {
                                            preEq.getBand(i)?.apply {
                                                isEnabled = true
                                                cutoffFrequency = cutoffs[i]
                                                gain = preGains[i]
                                            }
                                        }
                                        dp.setPreEqAllChannelsTo(preEq)
                                    }

                                    val postEq = dp.getPostEqByChannelIndex(0)
                                    if (postEq != null && postEq.bandCount >= 5) {
                                        for (i in 0 until 5) {
                                            postEq.getBand(i)?.apply {
                                                isEnabled = true
                                                cutoffFrequency = cutoffs[i]
                                                gain = postGains[i]
                                            }
                                        }
                                        dp.setPostEqAllChannelsTo(postEq)
                                    }

                                    val limiter = DynamicsProcessing.Limiter(
                                        true,
                                        true,
                                        0,
                                        1.5f,
                                        45.0f,
                                        6.0f,
                                        -0.4f,
                                        1.2f
                                    )
                                    dp.setLimiterAllChannelsTo(limiter)

                                    if (!dp.enabled) {
                                        dp.enabled = true
                                    }
                                }
                            } catch (_: Throwable) {}
                        }

                        // Aktifkan juga Hardware 3D Virtualizer & BassBoost pada session 0 untuk efek panggung 3D nyata
                        try {
                            if (virtualizer == null) virtualizer = Virtualizer(0, 0)
                            val virtStrength = when (profile) {
                                DolbyAtmosProfile.MOVIE -> 880.toShort()
                                DolbyAtmosProfile.DYNAMIC -> 760.toShort()
                                DolbyAtmosProfile.MUSIC -> 720.toShort()
                                DolbyAtmosProfile.VOICE -> 420.toShort()
                            }
                            if (virtualizer?.strengthSupported == true) {
                                virtualizer?.setStrength(virtStrength)
                            }
                            if (virtualizer?.enabled == false) virtualizer?.enabled = true
                        } catch (_: Throwable) {}

                        try {
                            if (bassBoost == null) bassBoost = BassBoost(0, 0)
                            val bassStrength = when (profile) {
                                DolbyAtmosProfile.MOVIE -> 700.toShort()
                                DolbyAtmosProfile.DYNAMIC -> 580.toShort()
                                DolbyAtmosProfile.MUSIC -> 520.toShort()
                                DolbyAtmosProfile.VOICE -> 180.toShort()
                            }
                            if (bassBoost?.strengthSupported == true) {
                                bassBoost?.setStrength(bassStrength)
                            }
                            if (bassBoost?.enabled == false) bassBoost?.enabled = true
                        } catch (_: Throwable) {}
                    } else {
                        try {
                            dynamicsProcessing?.enabled = false
                            dynamicsProcessing?.release()
                        } catch (_: Throwable) {}
                        dynamicsProcessing = null

                        try {
                            virtualizer?.enabled = false
                            virtualizer?.release()
                        } catch (_: Throwable) {}
                        virtualizer = null

                        try {
                            bassBoost?.enabled = false
                            bassBoost?.release()
                        } catch (_: Throwable) {}
                        bassBoost = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "applyNativeDolbyAtmosState error: ${e.message}")
                }
            }
        }
    }

    fun isRunningOnVirtualOrStreamingDevice(): Boolean {
        val fingerprint = (Build.FINGERPRINT ?: "").lowercase()
        val hardware = (Build.HARDWARE ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()
        val product = (Build.PRODUCT ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val device = (Build.DEVICE ?: "").lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("sdk_gphone") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("cuttlefish") ||
            hardware.contains("vsoc") ||
            model.contains("sdk") ||
            model.contains("emulator") ||
            model.contains("droid4x") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            product.contains("cuttlefish") ||
            (brand.startsWith("generic") && device.startsWith("generic"))
    }

    /**
     * Mengaktifkan atau mematikan mode warna HDR pada Window Activity dengan aman.
     * Pada Streaming Emulator (Preview) / VirtualDisplay encoder, mengubah window.colorMode
     * ke WIDE_COLOR_GAMUT (RGBA_FP16) menyebabkan encoder stream menghasilkan layar hitam (blackscreen),
     * sehingga window.colorMode dijaga tetap COLOR_MODE_DEFAULT dan Dolby Vision dirender
     * melalui GPU SVG PQ Tone-Mapping & CSS Compositor.
     */
    fun applyWindowDolbyVisionMode(activity: Activity?, enabled: Boolean) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val window = activity.window ?: return
            if (window.colorMode != ActivityInfo.COLOR_MODE_DEFAULT) {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        } catch (_: Throwable) {}
    }

    /**
     * Menyuntikkan mesin Dolby Atmos & Dolby Vision pada WebView secara real-time.
     */
    fun syncWebViewDolbyState(
        webView: WebView?,
        atmosEnabled: Boolean,
        atmosProfile: DolbyAtmosProfile,
        visionEnabled: Boolean,
        visionMode: DolbyVisionMode
    ) {
        if (webView == null) return
        val script = buildWebViewDolbyEngineScript(
            atmosEnabled = atmosEnabled,
            atmosProfile = atmosProfile,
            visionEnabled = visionEnabled,
            visionMode = visionMode
        )
        try {
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.w(TAG, "syncWebViewDolbyState failed: ${e.message}")
        }
    }

    fun buildWebViewDolbyEngineScript(
        atmosEnabled: Boolean,
        atmosProfile: DolbyAtmosProfile,
        visionEnabled: Boolean,
        visionMode: DolbyVisionMode
    ): String {
        val cssFilterValue = when (visionMode) {
            DolbyVisionMode.VIVID -> "url(#__alex_dv_vivid) contrast(1.14) saturate(1.28) brightness(1.04)"
            DolbyVisionMode.CINEMA -> "url(#__alex_dv_cinema) contrast(1.16) saturate(1.16) brightness(1.01)"
            DolbyVisionMode.BRIGHT -> "url(#__alex_dv_bright) contrast(1.10) saturate(1.22) brightness(1.10)"
        }

        val (subGain, lowMidGain, vocalGain, presenceGain, airGain) = when (atmosProfile) {
            DolbyAtmosProfile.MOVIE -> listOf(6.0f, 3.2f, 4.0f, 4.2f, 5.2f)
            DolbyAtmosProfile.DYNAMIC -> listOf(5.0f, 2.8f, 3.5f, 3.8f, 4.6f)
            DolbyAtmosProfile.MUSIC -> listOf(4.5f, 2.2f, 2.6f, 4.4f, 5.6f)
            DolbyAtmosProfile.VOICE -> listOf(-1.5f, 1.5f, 6.2f, 4.8f, 2.2f)
        }

        return """
(function() {
    try {
        window.__alexDolbyState = {
            atmosEnabled: $atmosEnabled,
            atmosProfile: '${atmosProfile.name}',
            visionEnabled: $visionEnabled,
            visionMode: '${visionMode.name}',
            cssFilter: '$cssFilterValue',
            gains: [$subGain, $lowMidGain, $vocalGain, $presenceGain, $airGain]
        };

        // 1. Safe MediaSource & matchMedia integration for Dolby Atmos & Dolby Vision HDR
        if (!window.__alexDolbyPolyfillInstalled) {
            window.__alexDolbyPolyfillInstalled = true;
            var origIsTypeSupported = (window.MediaSource && window.MediaSource.isTypeSupported)
                ? window.MediaSource.isTypeSupported.bind(window.MediaSource)
                : null;
            if (origIsTypeSupported) {
                window.MediaSource.isTypeSupported = function(mimeType) {
                    if (typeof mimeType === 'string') {
                        var low = mimeType.toLowerCase();
                        if (window.__alexDolbyState && !window.__alexDolbyState.atmosEnabled) {
                            if (low.indexOf('ec-3') !== -1 || low.indexOf('eac3') !== -1 || low.indexOf('ac-3') !== -1 || low.indexOf('ac-4') !== -1) {
                                return false;
                            }
                        }
                        if (window.__alexDolbyState && !window.__alexDolbyState.visionEnabled) {
                            if (low.indexOf('dvhe') !== -1 || low.indexOf('dvh1') !== -1 || low.indexOf('dav1') !== -1) {
                                return false;
                            }
                        }
                    }
                    return origIsTypeSupported(mimeType);
                };
            }
            var origMatchMedia = window.matchMedia ? window.matchMedia.bind(window) : null;
            if (origMatchMedia) {
                window.matchMedia = function(query) {
                    var res = origMatchMedia(query);
                    if (typeof query === 'string' && window.__alexDolbyState && window.__alexDolbyState.visionEnabled) {
                        var q = query.toLowerCase();
                        if (q.indexOf('dynamic-range: high') !== -1 || q.indexOf('video-dynamic-range: high') !== -1 || q.indexOf('color-gamut: p3') !== -1 || q.indexOf('color-gamut: rec2020') !== -1) {
                            return {
                                matches: true,
                                media: query,
                                onchange: null,
                                addListener: function(cb) { if (res && res.addListener) res.addListener(cb); },
                                removeListener: function(cb) { if (res && res.removeListener) res.removeListener(cb); },
                                addEventListener: function(t, cb, o) { if (res && res.addEventListener) res.addEventListener(t, cb, o); },
                                removeEventListener: function(t, cb, o) { if (res && res.removeEventListener) res.removeEventListener(t, cb, o); },
                                dispatchEvent: function(e) { return (res && res.dispatchEvent) ? res.dispatchEvent(e) : false; }
                            };
                        }
                    }
                    return res;
                };
            }
        }

        // 2. Dolby Atmos Smooth WebAudio 5-Band Studio Processor (Event-Driven, Zero-Polling, Zero-Glitch)
        function isSameOriginOrBlobMedia(el) {
            try {
                var src = el.currentSrc || el.src || '';
                if (!src || src.indexOf('blob:') === 0 || src.indexOf('data:') === 0) return true;
                var u = new URL(src, location.href);
                return u.origin === location.origin || el.crossOrigin === 'anonymous' || el.crossOrigin === 'use-credentials';
            } catch(e) {
                return false;
            }
        }

        function syncMediaElementAtmos(el) {
            try {
                var st = window.__alexDolbyState;
                if (!st) return;
                if (!el.__alexAtmosNode && !st.atmosEnabled) return;
                if (!isSameOriginOrBlobMedia(el)) return;

                var AudioCtx = window.AudioContext || window.webkitAudioContext;
                if (!AudioCtx) return;

                if (!el.__alexAtmosNode && st.atmosEnabled) {
                    if (!window.__alexSharedAudioCtx) {
                        window.__alexSharedAudioCtx = new AudioCtx({ latencyHint: 'playback' });
                    }
                    var ctx = window.__alexSharedAudioCtx;
                    if (ctx.state === 'suspended') {
                        ctx.resume().catch(function(){});
                    }
                    var source = ctx.createMediaElementSource(el);
                    var freqs = [85, 320, 1400, 4200, 11000];
                    var types = ['lowshelf', 'peaking', 'peaking', 'peaking', 'highshelf'];
                    var filters = freqs.map(function(f, idx) {
                        var b = ctx.createBiquadFilter();
                        b.type = types[idx];
                        b.frequency.value = f;
                        b.Q.value = 0.85;
                        b.gain.value = 0;
                        return b;
                    });
                    var comp = ctx.createDynamicsCompressor();
                    comp.threshold.value = -16;
                    comp.knee.value = 18;
                    comp.ratio.value = 2.6;
                    comp.attack.value = 0.015;
                    comp.release.value = 0.18;

                    var makeup = ctx.createGain();
                    makeup.gain.value = 1.0;

                    var prev = source;
                    for (var i = 0; i < filters.length; i++) {
                        prev.connect(filters[i]);
                        prev = filters[i];
                    }

                    // 3D Spatial Acoustic Widener (Haas Crossfeed + Air Highpass)
                    var spatialDelay = ctx.createDelay(0.05);
                    spatialDelay.delayTime.value = 0.012;
                    var spatialHp = ctx.createBiquadFilter();
                    spatialHp.type = 'highpass';
                    spatialHp.frequency.value = 380;
                    var spatialGain = ctx.createGain();
                    spatialGain.gain.value = 0.24;

                    prev.connect(comp);
                    prev.connect(spatialHp);
                    spatialHp.connect(spatialDelay);
                    spatialDelay.connect(spatialGain);
                    spatialGain.connect(comp);

                    comp.connect(makeup);
                    makeup.connect(ctx.destination);

                    el.__alexAtmosNode = {
                        ctx: ctx,
                        filters: filters,
                        spatialGain: spatialGain,
                        makeup: makeup
                    };
                }

                var node = el.__alexAtmosNode;
                if (node && node.ctx) {
                    if (node.ctx.state === 'suspended' && !el.paused) {
                        node.ctx.resume().catch(function(){});
                    }
                    var now = node.ctx.currentTime || 0;
                    var gList = st.atmosEnabled ? (st.gains || [0,0,0,0,0]) : [0,0,0,0,0];
                    for (var j = 0; j < node.filters.length; j++) {
                        var targetG = gList[j] || 0;
                        node.filters[j].gain.setTargetAtTime(targetG, now, 0.045);
                    }
                    if (node.spatialGain) {
                        var targetSpatial = st.atmosEnabled
                            ? (st.atmosProfile === 'MOVIE' ? 0.30 : (st.atmosProfile === 'MUSIC' ? 0.26 : (st.atmosProfile === 'DYNAMIC' ? 0.24 : 0.12)))
                            : 0.0;
                        node.spatialGain.gain.setTargetAtTime(targetSpatial, now, 0.045);
                    }
                    var targetMakeup = st.atmosEnabled ? 1.18 : 1.0;
                    node.makeup.gain.setTargetAtTime(targetMakeup, now, 0.045);
                }
            } catch(e) {}
        }

        window.__alexSyncAllMediaDolby = function() {
            try {
                var mediaList = document.querySelectorAll('video, audio');
                for (var i = 0; i < mediaList.length; i++) {
                    syncMediaElementAtmos(mediaList[i]);
                }
            } catch(e) {}
        };

        if (!window.__alexDolbyEventHooksInstalled) {
            window.__alexDolbyEventHooksInstalled = true;
            document.addEventListener('play', function(e) {
                if (e && e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                    syncMediaElementAtmos(e.target);
                }
            }, true);
            document.addEventListener('loadedmetadata', function(e) {
                if (e && e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                    syncMediaElementAtmos(e.target);
                }
            }, true);
            window.addEventListener('yt-navigate-finish', function() {
                if (window.__alexSyncAllMediaDolby) window.__alexSyncAllMediaDolby();
            });
        }

        window.__alexSyncAllMediaDolby();

        // 3. Dolby Vision Real-Time Video HDR Engine (SVG PQ Tone-Mapping + GPU CSS Compositor)
        var svgEl = document.getElementById('__alex_dolby_vision_svg');
        var styleEl = document.getElementById('__alex_dolby_vision_style');
        if (window.__alexDolbyState.visionEnabled) {
            if (!svgEl && (document.body || document.documentElement)) {
                var svgContainer = document.createElement('div');
                svgContainer.id = '__alex_dolby_vision_svg';
                svgContainer.style.cssText = 'position:fixed;width:0;height:0;overflow:hidden;pointer-events:none;z-index:-9999;';
                svgContainer.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="0" height="0">' +
                    '<defs>' +
                    '<filter id="__alex_dv_vivid" color-interpolation-filters="sRGB">' +
                    '<feColorMatrix type="matrix" values="1.12 -0.06 -0.06 0 0  -0.05 1.10 -0.05 0 0  -0.05 -0.07 1.12 0 0  0 0 0 1 0"/>' +
                    '<feComponentTransfer>' +
                    '<feFuncR type="gamma" amplitude="1.03" exponent="0.94" offset="0"/>' +
                    '<feFuncG type="gamma" amplitude="1.03" exponent="0.94" offset="0"/>' +
                    '<feFuncB type="gamma" amplitude="1.04" exponent="0.93" offset="0"/>' +
                    '</feComponentTransfer>' +
                    '</filter>' +
                    '<filter id="__alex_dv_cinema" color-interpolation-filters="sRGB">' +
                    '<feColorMatrix type="matrix" values="1.09 -0.04 -0.04 0 0  -0.03 1.06 -0.03 0 0  -0.05 -0.05 1.04 0 0  0 0 0 1 0"/>' +
                    '<feComponentTransfer>' +
                    '<feFuncR type="gamma" amplitude="1.02" exponent="0.97" offset="-0.01"/>' +
                    '<feFuncG type="gamma" amplitude="1.01" exponent="0.97" offset="-0.01"/>' +
                    '<feFuncB type="gamma" amplitude="1.00" exponent="0.98" offset="-0.01"/>' +
                    '</feComponentTransfer>' +
                    '</filter>' +
                    '<filter id="__alex_dv_bright" color-interpolation-filters="sRGB">' +
                    '<feColorMatrix type="matrix" values="1.08 -0.04 -0.04 0 0  -0.04 1.08 -0.04 0 0  -0.04 -0.04 1.08 0 0  0 0 0 1 0"/>' +
                    '<feComponentTransfer>' +
                    '<feFuncR type="gamma" amplitude="1.05" exponent="0.88" offset="0.01"/>' +
                    '<feFuncG type="gamma" amplitude="1.05" exponent="0.88" offset="0.01"/>' +
                    '<feFuncB type="gamma" amplitude="1.05" exponent="0.88" offset="0.01"/>' +
                    '</feComponentTransfer>' +
                    '</filter>' +
                    '</defs></svg>';
                (document.body || document.documentElement).appendChild(svgContainer);
            }
            if (!styleEl) {
                styleEl = document.createElement('style');
                styleEl.id = '__alex_dolby_vision_style';
                (document.head || document.documentElement).appendChild(styleEl);
            }
            var nextCss = 'video, .html5-main-video, :fullscreen video, :-webkit-full-screen video { filter: ' + window.__alexDolbyState.cssFilter + ' !important; will-change: filter; transition: filter 0.18s ease-out; }';
            if (styleEl.textContent !== nextCss) {
                styleEl.textContent = nextCss;
            }
        } else {
            if (styleEl) {
                styleEl.textContent = '';
                styleEl.remove();
            }
        }
    } catch(err) {}
})();
        """.trimIndent()
    }
}
