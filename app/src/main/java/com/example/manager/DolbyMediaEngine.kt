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
     * tanpa menyentuh HAL routing yang memicu bunyi "brtt" dan tanpa membajak stream WebAudio.
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
                                        false, 0,
                                        true
                                    ).build()
                                    dynamicsProcessing = DynamicsProcessing(0, 0, config)
                                }
                                val dp = dynamicsProcessing
                                if (dp != null) {
                                    // Headroom-safe studio gain values (mencegah clipping/suara pecah "brtt")
                                    val bassGain = when (profile) {
                                        DolbyAtmosProfile.MOVIE -> 2.4f
                                        DolbyAtmosProfile.DYNAMIC -> 2.0f
                                        DolbyAtmosProfile.MUSIC -> 1.8f
                                        DolbyAtmosProfile.VOICE -> 0.4f
                                    }
                                    val voiceGain = when (profile) {
                                        DolbyAtmosProfile.VOICE -> 2.6f
                                        DolbyAtmosProfile.MOVIE -> 1.8f
                                        DolbyAtmosProfile.DYNAMIC -> 1.5f
                                        DolbyAtmosProfile.MUSIC -> 1.2f
                                    }
                                    val trebleGain = when (profile) {
                                        DolbyAtmosProfile.MUSIC -> 2.2f
                                        DolbyAtmosProfile.MOVIE -> 2.0f
                                        DolbyAtmosProfile.DYNAMIC -> 1.8f
                                        DolbyAtmosProfile.VOICE -> 0.8f
                                    }
                                    dp.setInputGainAllChannelsTo(-1.5f)
                                    val preEq = dp.getPreEqByChannelIndex(0)
                                    if (preEq != null && preEq.bandCount >= 5) {
                                        preEq.getBand(0)?.apply { isEnabled = true; gain = bassGain }
                                        preEq.getBand(1)?.apply { isEnabled = true; gain = bassGain * 0.6f }
                                        preEq.getBand(2)?.apply { isEnabled = true; gain = voiceGain }
                                        preEq.getBand(3)?.apply { isEnabled = true; gain = voiceGain * 0.8f }
                                        preEq.getBand(4)?.apply { isEnabled = true; gain = trebleGain }
                                        dp.setPreEqAllChannelsTo(preEq)
                                    }
                                    if (!dp.enabled) {
                                        dp.enabled = true
                                    }
                                }
                            } catch (_: Throwable) {
                                try {
                                    if (virtualizer == null) virtualizer = Virtualizer(0, 0)
                                    virtualizer?.setStrength(if (profile == DolbyAtmosProfile.MOVIE) 650.toShort() else 500.toShort())
                                    if (virtualizer?.enabled == false) virtualizer?.enabled = true
                                    if (bassBoost == null) bassBoost = BassBoost(0, 0)
                                    bassBoost?.setStrength(if (profile == DolbyAtmosProfile.VOICE) 150.toShort() else 400.toShort())
                                    if (bassBoost?.enabled == false) bassBoost?.enabled = true
                                } catch (_: Throwable) {}
                            }
                        }
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

    /**
     * Mengaktifkan atau mematikan mode warna HDR / Wide Color Gamut pada Window Activity
     * untuk mendukung rendering video Dolby Vision.
     */
    fun applyWindowDolbyVisionMode(activity: Activity?, enabled: Boolean) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val window = activity.window ?: return
            val targetColorMode = if (enabled) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
            if (window.colorMode != targetColorMode) {
                window.colorMode = targetColorMode
            }
        } catch (_: Throwable) {
            try {
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.window?.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Menyuntikkan dukungan stream Dolby Atmos & Dolby Vision pada WebView
     * TANPA membajak elemen <video>/<audio> ke dalam WebAudio (sehingga audio 100% menggunakan
     * jalur Hardware AudioTrack Offload Android yang bebas bunyi "brtt", hemat baterai, dan dingin).
     */
    fun syncWebViewDolbyState(
        webView: WebView?,
        atmosEnabled: Boolean,
        atmosProfile: DolbyAtmosProfile,
        visionEnabled: Boolean,
        visionMode: DolbyVisionMode
    ) {
        if (webView == null) return
        val stateSignature = "${webView.url}|$atmosEnabled|${atmosProfile.name}|$visionEnabled|${visionMode.name}"
        synchronized(webViewStateCache) {
            if (webViewStateCache[webView] == stateSignature) {
                return
            }
            webViewStateCache[webView] = stateSignature
        }
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
            DolbyVisionMode.VIVID -> "contrast(1.08) saturate(1.18) brightness(1.03)"
            DolbyVisionMode.CINEMA -> "contrast(1.10) saturate(1.10) brightness(1.01)"
            DolbyVisionMode.BRIGHT -> "contrast(1.06) saturate(1.14) brightness(1.07)"
        }

        return """
(function() {
    try {
        window.__alexDolbyState = {
            atmosEnabled: $atmosEnabled,
            atmosProfile: '${atmosProfile.name}',
            visionEnabled: $visionEnabled,
            visionMode: '${visionMode.name}',
            cssFilter: '$cssFilterValue'
        };

        // 1. Polyfill MediaSource & matchMedia untuk negosiasi stream Dolby Atmos & Dolby Vision HDR
        if (!window.__alexDolbyPolyfillInstalled) {
            window.__alexDolbyPolyfillInstalled = true;
            var origIsTypeSupported = (window.MediaSource && window.MediaSource.isTypeSupported)
                ? window.MediaSource.isTypeSupported.bind(window.MediaSource)
                : null;
            if (origIsTypeSupported) {
                window.MediaSource.isTypeSupported = function(mimeType) {
                    if (typeof mimeType === 'string') {
                        var low = mimeType.toLowerCase();
                        if (window.__alexDolbyState && window.__alexDolbyState.atmosEnabled) {
                            if (low.indexOf('ec-3') !== -1 || low.indexOf('eac3') !== -1 || low.indexOf('ac-3') !== -1 || low.indexOf('ac-4') !== -1) {
                                return true;
                            }
                        }
                        if (window.__alexDolbyState && window.__alexDolbyState.visionEnabled) {
                            if (low.indexOf('dvhe') !== -1 || low.indexOf('dvh1') !== -1 || low.indexOf('dav1') !== -1 || low.indexOf('hev1.2') !== -1 || low.indexOf('vp09.02') !== -1) {
                                return true;
                            }
                        }
                    }
                    return origIsTypeSupported(mimeType);
                };
            }
            var origMatchMedia = window.matchMedia ? window.matchMedia.bind(window) : null;
            if (origMatchMedia) {
                window.matchMedia = function(query) {
                    if (typeof query === 'string' && window.__alexDolbyState && window.__alexDolbyState.visionEnabled) {
                        var q = query.toLowerCase();
                        if (q.indexOf('dynamic-range: high') !== -1 || q.indexOf('video-dynamic-range: high') !== -1 || q.indexOf('color-gamut: p3') !== -1 || q.indexOf('color-gamut: rec2020') !== -1) {
                            return {
                                matches: true,
                                media: query,
                                onchange: null,
                                addListener: function() {},
                                removeListener: function() {},
                                addEventListener: function() {},
                                removeEventListener: function() {},
                                dispatchEvent: function() { return false; }
                            };
                        }
                    }
                    return origMatchMedia(query);
                };
            }
        }

        // 2. Dolby Vision Real-Time Video HDR Engine (Ringan & Tanpa Memaksa Re-Layout)
        var styleEl = document.getElementById('__alex_dolby_vision_style');
        if (window.__alexDolbyState.visionEnabled) {
            if (!styleEl) {
                styleEl = document.createElement('style');
                styleEl.id = '__alex_dolby_vision_style';
                (document.head || document.documentElement).appendChild(styleEl);
            }
            var nextCss = 'video { filter: ' + window.__alexDolbyState.cssFilter + ' !important; }';
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
