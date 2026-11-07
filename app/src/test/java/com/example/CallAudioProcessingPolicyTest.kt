package com.example

import com.example.core.calls.CallAudioProcessingPolicy
import org.junit.Assert.*
import org.junit.Test

class CallAudioProcessingPolicyTest {
    @Test fun independentHardwareCapabilitiesAreNotConflated() {
        val none = CallAudioProcessingPolicy.choose(false, false)
        assertFalse(none.hardwareAec)
        assertFalse(none.hardwareNs)
        val aecOnly = CallAudioProcessingPolicy.choose(true, false)
        assertTrue(aecOnly.hardwareAec)
        assertFalse(aecOnly.hardwareNs)
        val nsOnly = CallAudioProcessingPolicy.choose(false, true)
        assertFalse(nsOnly.hardwareAec)
        assertTrue(nsOnly.hardwareNs)
        val both = CallAudioProcessingPolicy.choose(true, true)
        assertTrue(both.hardwareAec)
        assertTrue(both.hardwareNs)
    }

    @org.junit.Test fun oemAudioFxFailureFallsBackToSingleSoftwareApm() {
        val hardware = com.example.core.calls.CallAudioProcessingPolicy.choose(true, true)
        val fallback = com.example.core.calls.CallAudioProcessingPolicy.softwareFallback(hardware)
        org.junit.Assert.assertFalse(fallback.hardwareAec)
        org.junit.Assert.assertFalse(fallback.hardwareNs)
        org.junit.Assert.assertEquals(fallback,
            com.example.core.calls.CallAudioProcessingPolicy.softwareFallback(fallback))
    }
    @Test fun userSoftwareModeNeverStacksHardwareEffectsRegardlessOfReportedSupport() {
        for (aec in listOf(false, true)) for (ns in listOf(false, true)) {
            val chosen = CallAudioProcessingPolicy.choose(aec, ns, preferSoftware = true)
            assertFalse(chosen.hardwareAec)
            assertFalse(chosen.hardwareNs)
            assertEquals(chosen, CallAudioProcessingPolicy.softwareFallback(chosen))
        }
    }

}
