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
}
