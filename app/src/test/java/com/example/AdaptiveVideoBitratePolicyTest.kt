package com.example

import com.example.core.calls.AdaptiveVideoBitratePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveVideoBitratePolicyTest {
    @Test fun repeatedLossReducesOnlyVideoBitrateAndRespectsCooldown() {
        val policy = AdaptiveVideoBitratePolicy()
        assertNull(policy.recommend(0.12, 480.0, 1000L))
        assertEquals(650_000, policy.recommend(0.12, 480.0, 2000L))
        policy.attemptFinished(650_000, 2000L, applied = true)
        assertNull(policy.recommend(0.20, 700.0, 3000L))
        assertNull(policy.recommend(0.20, 700.0, 12000L))
        assertEquals(350_000, policy.recommend(0.20, 700.0, 13000L))
    }

    @Test fun missingOrInvalidStatsNeverTriggerAdaptation() {
        val policy = AdaptiveVideoBitratePolicy()
        assertNull(policy.recommend(null, null, 1000L))
        assertNull(policy.recommend(Double.NaN, 500.0, 2000L))
        assertNull(policy.recommend(-0.1, 500.0, 3000L))
        assertNull(policy.recommend(0.1, Double.POSITIVE_INFINITY, 4000L))
        assertNull(policy.recommend(0.1, -1.0, 5000L))
        assertNull(policy.recommend(0.1, 200.0, -1L))
    }

    @Test fun recoveryIsGradualAndFailedNativeSetKeepsCurrentLevel() {
        val policy = AdaptiveVideoBitratePolicy()
        policy.recommend(0.15, 600.0, 1000L)
        assertEquals(650_000, policy.recommend(0.15, 600.0, 2000L))
        policy.attemptFinished(650_000, 2000L, applied = false)
        // Failure cannot be mistaken for a bitrate change.
        assertNull(policy.recommend(0.15, 600.0, 12000L))
        assertEquals(650_000, policy.recommend(0.15, 600.0, 13000L))
        policy.attemptFinished(650_000, 13000L, applied = true)
        for (i in 0..3) assertNull(policy.recommend(0.0, 80.0, 24000L + i))
        assertEquals(1_100_000, policy.recommend(0.0, 80.0, 24004L))
    }
}
