package com.example

import com.example.core.calls.AdaptiveVideoEncodingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video downshift may NEVER disable or mute audio. */
class AdaptiveVideoEncodingPolicyTest {
    @Test fun poorNetworkHasLowerFpsAndLessVideoBandwidth() {
        val low = AdaptiveVideoEncodingPolicy.forBitrate(350_000)
        val medium = AdaptiveVideoEncodingPolicy.forBitrate(650_000)
        val high = AdaptiveVideoEncodingPolicy.forBitrate(1_100_000)
        assertEquals(15, low.maxFramerate)
        assertEquals(20, medium.maxFramerate)
        assertEquals(24, high.maxFramerate)
        assertTrue(low.maxBitrateBps < medium.maxBitrateBps)
        assertTrue(medium.maxBitrateBps < high.maxBitrateBps)
        assertEquals(2.0, low.scaleResolutionDownBy, 0.0)
        assertEquals(1.5, medium.scaleResolutionDownBy, 0.0)
        assertEquals(1.0, high.scaleResolutionDownBy, 0.0)
    }

    @Test fun boundaryValuesDoNotGiveHigherQualityToWeakerNetwork() {
        assertEquals(350_000, AdaptiveVideoEncodingPolicy.forBitrate(1).maxBitrateBps)
        assertEquals(650_000, AdaptiveVideoEncodingPolicy.forBitrate(350_001).maxBitrateBps)
        assertEquals(1_100_000, AdaptiveVideoEncodingPolicy.forBitrate(650_001).maxBitrateBps)
    }
}
