package com.example

import com.example.core.calls.VoiceFirstNetworkPolicy
import org.junit.Assert.*
import org.junit.Test

class VoiceFirstNetworkPolicyTest {
    @Test fun audioPacketLossOverridesHealthyVideoToProtectSpeech() {
        val s = VoiceFirstNetworkPolicy.select(0.01, 90.0, 0.18, 550.0)
        assertTrue(s.available)
        assertEquals(0.18, s.loss!!, 0.0)
        assertEquals(550.0, s.rttMs!!, 0.0)
    }

    @Test fun noStatsNeverTriggersCongestionGuess() {
        val s = VoiceFirstNetworkPolicy.select(null, null, null, null)
        assertFalse(s.available)
        assertNull(s.loss)
        assertNull(s.rttMs)
    }

    @Test fun oneMalformedStreamCannotPoisonValidOtherStream() {
        val s = VoiceFirstNetworkPolicy.select(0.12, 430.0,
            Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(0.12, s.loss!!, 0.0)
        assertEquals(430.0, s.rttMs!!, 0.0)
    }

    @Test fun maliciousAndNegativeStatsAreIgnoredRatherThanClampedToGood() {
        val s = VoiceFirstNetworkPolicy.select(-1.0, 80_000.0, 1.4, -100.0)
        assertFalse(s.available)
        val fallback = VoiceFirstNetworkPolicy.select(null, null, 0.1, 150.0)
        assertEquals(0.1, fallback.loss!!, 0.0)
    }
}
