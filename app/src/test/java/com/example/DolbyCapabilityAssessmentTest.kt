package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.manager.DolbyCapabilityAssessment
import com.example.manager.DolbyCapabilityProvider
import com.example.manager.DolbyFeature
import com.example.manager.DolbyMediaEngine
import com.example.manager.SettingsManager
import com.example.manager.evaluateDolbyActivation
import com.example.ui.viewmodel.BrowserViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DolbyCapabilityAssessmentTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        DolbyMediaEngine.setCapabilityProviderForTesting(null)
        val settings = SettingsManager(application)
        settings.dolbyAtmosEnabled = false
        settings.dolbyVisionEnabled = false
    }

    @After
    fun tearDown() {
        DolbyMediaEngine.setCapabilityProviderForTesting(null)
    }

    private fun verifiedAtmosAssessment(): DolbyCapabilityAssessment = DolbyCapabilityAssessment(
        hasAtmosCodec = true,
        hasVerifiedAtmosProcessing = true,
        hasVerifiedAtmosPlaybackPath = true,
        canControlNativeAtmos = true
    )

    private fun verifiedVisionAssessment(): DolbyCapabilityAssessment = DolbyCapabilityAssessment(
        hasDolbyVisionDecoder = true,
        hasDolbyVisionDisplay = true,
        hasVerifiedDolbyVisionPlaybackPath = true,
        canControlNativeDolbyVision = true
    )

    @Test
    fun scenario1_atmosVerified_visionUnavailable_onlyAtmosCanBeEnabled() {
        val assessment = verifiedAtmosAssessment()
        assertTrue(assessment.canEnableAtmos)
        assertFalse(assessment.canEnableDolbyVision)

        val atmosDecision = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment)
        val visionDecision = evaluateDolbyActivation(DolbyFeature.VISION, assessment)
        assertTrue(atmosDecision.allowed)
        assertFalse(visionDecision.allowed)
    }

    @Test
    fun scenario2_visionVerified_atmosUnavailable_onlyVisionCanBeEnabled() {
        val assessment = verifiedVisionAssessment()
        assertFalse(assessment.canEnableAtmos)
        assertTrue(assessment.canEnableDolbyVision)

        val atmosDecision = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment)
        val visionDecision = evaluateDolbyActivation(DolbyFeature.VISION, assessment)
        assertFalse(atmosDecision.allowed)
        assertTrue(visionDecision.allowed)
    }

    @Test
    fun scenario3_bothAvailable_bothCanBeEnabledIndependently() {
        val assessment = verifiedAtmosAssessment().copy(
            hasDolbyVisionDecoder = true,
            hasDolbyVisionDisplay = true,
            hasVerifiedDolbyVisionPlaybackPath = true,
            canControlNativeDolbyVision = true
        )
        assertTrue(assessment.canEnableAtmos)
        assertTrue(assessment.canEnableDolbyVision)

        DolbyMediaEngine.setCapabilityProviderForTesting(
            DolbyCapabilityProvider { _, _ -> DolbyMediaEngine.fromAssessment(assessment) }
        )
        val vm = BrowserViewModel(application)
        assertTrue(vm.setDolbyAtmosEnabled(true).allowed)
        assertTrue(vm.dolbyAtmosEnabled.value)
        assertFalse(vm.dolbyVisionEnabled.value)

        assertTrue(vm.setDolbyVisionEnabled(true).allowed)
        assertTrue(vm.dolbyAtmosEnabled.value)
        assertTrue(vm.dolbyVisionEnabled.value)

        assertTrue(vm.setDolbyAtmosEnabled(false).allowed)
        assertFalse(vm.dolbyAtmosEnabled.value)
        assertTrue(vm.dolbyVisionEnabled.value)
    }

    @Test
    fun scenario4_neitherAvailable_bothDisabledWithSpecificReasons() {
        val assessment = DolbyCapabilityAssessment()
        assertFalse(assessment.canEnableAtmos)
        assertFalse(assessment.canEnableDolbyVision)

        val atmosDecision = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment)
        val visionDecision = evaluateDolbyActivation(DolbyFeature.VISION, assessment)
        assertFalse(atmosDecision.allowed)
        assertFalse(visionDecision.allowed)
        assertTrue(atmosDecision.reason.contains("Dolby Atmos"))
        assertTrue(visionDecision.reason.contains("Dolby Vision"))
    }

    @Test
    fun scenario5_atmosCodecPresentWithoutNativeProcessing_cannotEnableAtmos() {
        val assessment = DolbyCapabilityAssessment(
            hasAtmosCodec = true,
            hasVerifiedAtmosProcessing = false,
            hasVerifiedAtmosPlaybackPath = false,
            canControlNativeAtmos = false
        )
        assertFalse(assessment.canEnableAtmos)
        val decision = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("OEM"))
    }

    @Test
    fun scenario6_genericSpatializerPresentWithoutAtmosProof_cannotEnableAtmos() {
        val assessment = DolbyCapabilityAssessment(
            hasSystemSpatializer = true,
            hasDynamicsProcessingDescriptor = true
        )
        assertFalse(assessment.canEnableAtmos)
        val decision = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("Spatializer"))
    }

    @Test
    fun scenario7_dolbyVisionDecoderPresentWithoutDolbyVisionDisplay_cannotEnableVision() {
        val assessment = DolbyCapabilityAssessment(
            hasDolbyVisionDecoder = true,
            hasDolbyVisionDisplay = false,
            hasHdr10OrHlgDisplay = true,
            hasWideColorGamutDisplay = true
        )
        assertFalse(assessment.canEnableDolbyVision)
        val decision = evaluateDolbyActivation(DolbyFeature.VISION, assessment)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("layar"))
    }

    @Test
    fun scenario8_hdr10OrWcgDisplayAvailableWithoutDolbyVision_cannotEnableVision() {
        val assessment = DolbyCapabilityAssessment(
            hasDolbyVisionDecoder = false,
            hasDolbyVisionDisplay = false,
            hasHdr10OrHlgDisplay = true,
            hasWideColorGamutDisplay = true
        )
        assertFalse(assessment.canEnableDolbyVision)
        val decision = evaluateDolbyActivation(DolbyFeature.VISION, assessment)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("HDR10"))
    }

    @Test
    fun scenario9_capabilityDetectionThrowsError_featureDisabledAndMarkedUnverified() {
        val assessment = DolbyCapabilityAssessment(
            hasAtmosCodec = true,
            hasVerifiedAtmosProcessing = true,
            hasVerifiedAtmosPlaybackPath = true,
            canControlNativeAtmos = true,
            hasDolbyVisionDecoder = true,
            hasDolbyVisionDisplay = true,
            hasVerifiedDolbyVisionPlaybackPath = true,
            canControlNativeDolbyVision = true,
            detectionError = "SecurityException: MediaCodecList query failed"
        )
        assertFalse(assessment.canEnableAtmos)
        assertFalse(assessment.canEnableDolbyVision)
        val atmosDecision = evaluateDolbyActivation(DolbyFeature.ATMOS, assessment)
        val visionDecision = evaluateDolbyActivation(DolbyFeature.VISION, assessment)
        assertFalse(atmosDecision.allowed)
        assertFalse(visionDecision.allowed)
        assertTrue(atmosDecision.reason.contains("diverifikasi"))
        assertTrue(visionDecision.reason.contains("diverifikasi"))
    }

    @Test
    fun scenario10_savedTruePreferenceOnUnsupportedDevice_reconciledToFalseOnStartup() {
        val settings = SettingsManager(application)
        settings.dolbyAtmosEnabled = true
        settings.dolbyVisionEnabled = true

        DolbyMediaEngine.setCapabilityProviderForTesting(
            DolbyCapabilityProvider { _, _ ->
                DolbyMediaEngine.fromAssessment(DolbyCapabilityAssessment())
            }
        )

        val vm = BrowserViewModel(application)
        assertFalse(vm.dolbyAtmosEnabled.value)
        assertFalse(vm.dolbyVisionEnabled.value)
        assertFalse(settings.dolbyAtmosEnabled)
        assertFalse(settings.dolbyVisionEnabled)
    }

    @Test
    fun scenario11_userPressesToggleWhenFeatureUnavailable_rejectedAndNotSaved() {
        val settings = SettingsManager(application)
        DolbyMediaEngine.setCapabilityProviderForTesting(
            DolbyCapabilityProvider { _, _ ->
                DolbyMediaEngine.fromAssessment(DolbyCapabilityAssessment())
            }
        )

        val vm = BrowserViewModel(application)
        val atmosDecision = vm.setDolbyAtmosEnabled(true)
        val visionDecision = vm.setDolbyVisionEnabled(true)

        assertFalse(atmosDecision.allowed)
        assertFalse(visionDecision.allowed)
        assertFalse(vm.dolbyAtmosEnabled.value)
        assertFalse(vm.dolbyVisionEnabled.value)
        assertFalse(settings.dolbyAtmosEnabled)
        assertFalse(settings.dolbyVisionEnabled)
        assertNotNull(vm.lastDolbyActivationMessage.value)
    }

    @Test
    fun scenario12_deviceLosesCapabilityAtRuntime_activeToggleResetsToFalse() {
        val settings = SettingsManager(application)
        val initialAssessment = verifiedAtmosAssessment().copy(
            hasDolbyVisionDecoder = true,
            hasDolbyVisionDisplay = true,
            hasVerifiedDolbyVisionPlaybackPath = true,
            canControlNativeDolbyVision = true
        )
        var currentCaps = DolbyMediaEngine.fromAssessment(initialAssessment)
        DolbyMediaEngine.setCapabilityProviderForTesting(
            DolbyCapabilityProvider { _, _ -> currentCaps }
        )

        val vm = BrowserViewModel(application)
        assertTrue(vm.setDolbyAtmosEnabled(true).allowed)
        assertTrue(vm.setDolbyVisionEnabled(true).allowed)
        assertTrue(vm.dolbyAtmosEnabled.value)
        assertTrue(vm.dolbyVisionEnabled.value)

        // Simulate display change or audio device change where native capability is lost
        currentCaps = DolbyMediaEngine.fromAssessment(DolbyCapabilityAssessment())
        vm.refreshDolbyDeviceCapabilities()

        assertFalse(vm.dolbyAtmosEnabled.value)
        assertFalse(vm.dolbyVisionEnabled.value)
        assertFalse(settings.dolbyAtmosEnabled)
        assertFalse(settings.dolbyVisionEnabled)
    }

    @Test
    fun scenario13_realDetectionInTestEnvironment_doesNotMakeFalseHardwareCompositorClaims() {
        val detected = DolbyMediaEngine.detectDeviceCapabilities(application)
        assertFalse(
            "Video capabilities must not claim an unverified hardware compositor",
            detected.detectedVideoCapabilities.any {
                it.contains("Dolby Vision PQ Tone-Mapping Hardware Compositor")
            }
        )
        assertEquals(detected.assessment.canEnableAtmos, detected.isNativeAtmosSupported)
        assertEquals(detected.assessment.canEnableDolbyVision, detected.isNativeVisionSupported)
    }
}
