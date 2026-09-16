package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationAndDspTest {

    @Test
    fun testAudioDspProcessor_fallbackToSoftwareWhenNoHardware() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dsp = AudioDspProcessor(context)

        val telemetry = dsp.telemetry.value
        // In emulator/robolectric without hardware DSP chip, software DSP must automatically be active!
        assertTrue("Software DSP must be active or supported", telemetry.isSoftwareActive || telemetry.isHardwareActive)

        // Verify buffer filtering works
        val testBuffer = ShortArray(256) { (it % 100).toShort() }
        val filtered = dsp.processPcmBuffer(testBuffer, isAncEnabled = true, suppressionStrength = 0.8f)
        assertEquals(testBuffer.size, filtered.size)

        // Switching mode
        dsp.setDspMode(DspProcessingMode.SOFTWARE_DSP)
        assertEquals(DspProcessingMode.SOFTWARE_DSP, dsp.telemetry.value.mode)
        assertTrue(dsp.telemetry.value.isSoftwareActive)
    }

    @Test
    fun testMediaSyncEngine_latencyAndDriftCompensator() = runTest {
        val syncEngine = MediaSyncEngine()
        val telemetry = syncEngine.telemetry.value

        assertTrue(telemetry.rttMs > 0)
        assertTrue(telemetry.jitterMs > 0)
        assertTrue(telemetry.lipSyncSkewMs >= 0f)

        // Test network degradation
        syncEngine.onNetworkDegraded()
        val degraded = syncEngine.telemetry.value
        assertTrue(degraded.rttMs >= 100)
        assertTrue(degraded.videoResolution.contains("480p"))

        // Test network recovery
        syncEngine.onNetworkRecovered()
        val recovered = syncEngine.telemetry.value
        assertEquals("720p HD (60 fps)", recovered.videoResolution)
    }

    @Test
    fun testBusinessArchitectureTiers() {
        val tiers = LanuBusinessArchitecture.tiers
        assertEquals(3, tiers.size)

        val core = tiers.find { it.id == "core_free" }
        assertNotNull(core)
        assertEquals("Ücretsiz", core?.price)

        val pro = tiers.find { it.id == "privacy_pro" }
        assertNotNull(pro)
        assertTrue(pro?.price?.contains("49") == true)

        val enterprise = tiers.find { it.id == "enterprise_mesh" }
        assertNotNull(enterprise)
        assertEquals("Kurumsal B2B", enterprise?.badge)
    }
}
