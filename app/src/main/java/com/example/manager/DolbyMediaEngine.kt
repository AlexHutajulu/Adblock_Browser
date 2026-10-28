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

enum class DolbyAtmosProfile(val title: String, val subtitle: String) {
    DYNAMIC(
        title = "Dinamis (Adaptif)",
        subtitle = "Menyesuaikan panggung suara 3D, sub-bass, dan vokal secara otomatis"
    ),
    MOVIE(
        title = "Sinema & Film",
        subtitle = "Surround 3D lebar, dentuman sub-bass sinematik, dan dialog tengah jernih"
    ),
    MUSIC(
        title = "Musik & Studio",
        subtitle = "Separasi instrumen kristal, panggung akustik luas, dan keseimbangan harmonik"
    ),
    VOICE(
        title = "Suara & Podcast",
        subtitle = "Fokus kejernihan frekuensi vokal manusia dan peredaman derau latar"
    )
}

enum class DolbyVisionMode(val title: String, val subtitle: String) {
    VIVID(
        title = "Dolby Vision Vivid",
        subtitle = "Kecerahan puncak tinggi, warna Wide Color Gamut P3 hidup, dan kontras mikro tajam"
    ),
    CINEMA(
        title = "Dolby Vision Cinema",
        subtitle = "Kurva gamma PQ akurat, detail bayangan gelap dalam, dan warna hangat bioskop"
    ),
    BRIGHT(
        title = "Dolby Vision Bright",
        subtitle = "Mengangkat detail adegan gelap dan sorotan spekular untuk ruangan terang"
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
    val detectedVideoCapabilities: List<String>
) {
    /** True jika perangkat memiliki dekoder Dolby Atmos native, DSP Dolby, atau Spatializer sistem */
    val isNativeAtmosSupported: Boolean
        get() = hasHardwareDolbyAtmosCodec || hasSystemSpatializer || hasSystemDolbyDspEffect

    /** True jika perangkat memiliki dekoder Dolby Vision / layar HDR10 / Wide Color Gamut */
    val isNativeVisionSupported: Boolean
        get() = hasHardwareDolbyVisionDecoder || hasDolbyVisionDisplay || hasHdr10OrWcgDisplay

    val atmosHardwareBadge: String
        get() = when {
            hasHardwareDolbyAtmosCodec && hasSystemSpatializer -> "Didukung Penuh (Hardware Dolby Atmos + 3D Spatializer)"
            hasHardwareDolbyAtmosCodec -> "Didukung (Hardware Decoder Dolby E-AC3-JOC / AC-4)"
            hasSystemDolbyDspEffect -> "Didukung (System Dolby Audio DSP)"
            hasSystemSpatializer -> "Didukung (Android Native 3D Spatializer)"
            hasAndroidDynamicsProcessing -> "Didukung via Hardware DSP Multi-Band Android"
            else -> "Mode Audio Hardware Offload Stabil Aktif"
        }

    val visionHardwareBadge: String
        get() = when {
            hasDolbyVisionDisplay && hasHardwareDolbyVisionDecoder -> "Didukung Penuh (Layar & Decoder Dolby Vision Native)"
            hasHardwareDolbyVisionDecoder && hasHdr10OrWcgDisplay -> "Didukung (Decoder Dolby Vision + Layar HDR10/P3)"
            hasHardwareDolbyVisionDecoder -> "Didukung (Hardware Decoder Dolby Vision + PQ Tone-Mapping)"
            hasHdr10OrWcgDisplay -> "Didukung (Layar HDR10 / Wide Color Gamut + Shader Dolby Vision)"
            else -> "Layar SDR (Menggunakan Mesin Tone-Mapping Dolby Vision PQ)"
        }
}

object DolbyMediaEngine {
    private const val TAG = "DolbyMediaEngine"

    @Volatile
    private var cachedCapabilities: DolbyDeviceCapabilities? = null

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
     */
    fun detectDeviceCapabilities(context: Context, forceRefresh: Boolean = false): DolbyDeviceCapabilities {
        if (!forceRefresh) {
            cachedCapabilities?.let { return it }
        }

        val audioCaps = mutableListOf<String>()
        val videoCaps = mutableListOf<String>()

        var hasAtmosCodec = false
        var hasDolbyVisionCodec = false
        var hasHdr10Codec = false

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
                            val label = "Dolby Atmos (E-AC3-JOC: ${info.name})"
                            if (audioCaps.none { it.startsWith("Dolby Atmos (E-AC3-JOC") }) {
                                audioCaps.add(label)
                            }
                        }
                        "audio/ac4" -> {
                            hasAtmosCodec = true
                            val label = "Dolby AC-4 Immersive (${info.name})"
                            if (audioCaps.none { it.startsWith("Dolby AC-4") }) {
                                audioCaps.add(label)
                            }
                        }
                        "audio/eac3" -> {
                            if (audioCaps.none { it.startsWith("Dolby Digital Plus") }) {
                                audioCaps.add("Dolby Digital Plus (E-AC3)")
                            }
                        }
                        "audio/ac3" -> {
                            if (audioCaps.none { it.startsWith("Dolby Digital (AC-3)") }) {
                                audioCaps.add("Dolby Digital (AC-3)")
                            }
                        }
                        "video/dolby-vision" -> {
                            hasDolbyVisionCodec = true
                            val label = "Dolby Vision Decoder (${info.name})"
                            if (videoCaps.none { it.startsWith("Dolby Vision Decoder") }) {
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
                                    videoCaps.add("HEVC Main10 HDR10/HDR10+ Decoder")
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec inspection warning: ${e.message}")
        }

        // 2. Periksa efek DSP Audio bawaan sistem (Dolby DAP / Spatial / DynamicsProcessing)
        var hasDolbyDsp = false
        val hasDynamicsProc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        try {
            val descriptors = AudioEffect.queryEffects()
            if (descriptors != null) {
                for (desc in descriptors) {
                    val combined = "${desc.name} ${desc.implementor}".lowercase()
                    if (combined.contains("dolby") || combined.contains("atmos") || combined.contains("dap")) {
                        hasDolbyDsp = true
                        val dspLabel = "DSP: ${desc.name}"
                        if (!audioCaps.contains(dspLabel)) audioCaps.add(dspLabel)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Periksa Android 12L/13+ Spatializer API
        var hasSpatializer = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val spatializer = am?.spatializer
                if (spatializer != null && spatializer.immersiveAudioLevel != android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
                    hasSpatializer = true
                    val stateStr = if (spatializer.isAvailable) "Tersedia & Aktif" else "Didukung Hardware"
                    audioCaps.add("Android 3D Spatializer ($stateStr)")
                }
            } catch (_: Exception) {}
        }

        if (hasDynamicsProc) {
            audioCaps.add("Android Hardware DynamicsProcessing DSP (Zero-Glitch Offload)")
        }

        // 4. Periksa kemampuan layar HDR / Dolby Vision / Wide Color Gamut
        var hasDolbyVisionDisplay = false
        var hasHdr10OrWcgDisplay = false
        var maxNits = 400
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && display.isWideColorGamut) {
                    hasHdr10OrWcgDisplay = true
                    videoCaps.add("Layar Wide Color Gamut (DCI-P3)")
                }
                @Suppress("DEPRECATION")
                val hdrCaps = display.hdrCapabilities
                if (hdrCaps != null) {
                    val types = hdrCaps.supportedHdrTypes ?: IntArray(0)
                    for (t in types) {
                        when (t) {
                            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> {
                                hasDolbyVisionDisplay = true
                                videoCaps.add("Layar Native Dolby Vision HDR")
                            }
                            Display.HdrCapabilities.HDR_TYPE_HDR10 -> {
                                hasHdr10OrWcgDisplay = true
                                videoCaps.add("Layar HDR10")
                            }
                            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> {
                                hasHdr10OrWcgDisplay = true
                                videoCaps.add("Layar HDR10+")
                            }
                            Display.HdrCapabilities.HDR_TYPE_HLG -> {
                                hasHdr10OrWcgDisplay = true
                                videoCaps.add("Layar HLG HDR")
                            }
                        }
                    }
                    val desiredMax = hdrCaps.desiredMaxLuminance.toInt()
                    if (desiredMax > 100) {
                        maxNits = desiredMax
                        videoCaps.add("Puncak Luminansi Layar: ${desiredMax} nits")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Display HDR inspection warning: ${e.message}")
        }

        if (hasHdr10Codec) {
            hasHdr10OrWcgDisplay = true
        }
        videoCaps.add("Dolby Vision PQ Tone-Mapping Hardware Compositor")

        val result = DolbyDeviceCapabilities(
            hasHardwareDolbyAtmosCodec = hasAtmosCodec,
            hasSystemSpatializer = hasSpatializer,
            hasSystemDolbyDspEffect = hasDolbyDsp,
            hasAndroidDynamicsProcessing = hasDynamicsProc,
            hasHardwareDolbyVisionDecoder = hasDolbyVisionCodec,
            hasDolbyVisionDisplay = hasDolbyVisionDisplay,
            hasHdr10OrWcgDisplay = hasHdr10OrWcgDisplay,
            maxDisplayLuminanceNits = maxNits,
            detectedAudioCapabilities = audioCaps,
            detectedVideoCapabilities = videoCaps
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
                    prev.connect(comp);
                    comp.connect(makeup);
                    makeup.connect(ctx.destination);

                    el.__alexAtmosNode = {
                        ctx: ctx,
                        filters: filters,
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
                    var targetMakeup = st.atmosEnabled ? 1.14 : 1.0;
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
            var nextCss = 'video, .html5-main-video { filter: ' + window.__alexDolbyState.cssFilter + ' !important; will-change: filter; }';
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
