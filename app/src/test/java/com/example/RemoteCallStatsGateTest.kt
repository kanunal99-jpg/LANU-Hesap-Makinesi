package com.example

import com.example.core.calls.RemoteCallStatsGate
import com.example.core.calls.AdaptiveVideoBitratePolicy
import org.junit.Assert.*
import org.junit.Test

class RemoteCallStatsGateTest {
    @Test fun oldEpochDelayedAndReorderedSnapshotsNeverDriveEncoder() {
        val gate = RemoteCallStatsGate()
        assertFalse(gate.begin(2, 1, 10, 0, 1))
        assertFalse(gate.begin(2, 2, 10, 0, 5001))
        assertFalse(gate.begin(2, 2, 10, 5, 4))
        assertTrue(gate.begin(2, 2, 2, 10, 11))
        assertFalse(gate.begin(2, 2, 1, 10, 12))
        assertFalse(gate.begin(2, 2, 2, 10, 12))
        assertTrue(gate.begin(3, 3, 3, 20, 21))
    }
    @Test fun repeatedRtcpIsNotIndependentEvidenceAndKindsRemainIndependent() {
        val gate = RemoteCallStatsGate()
        assertTrue(gate.begin(1, 1, 1, 0, 1))
        assertTrue(gate.fresh("audio", 100.0))
        repeat(1000) { assertFalse(gate.fresh("audio", 100.0)) }
        assertFalse(gate.fresh("audio", 99.0))
        assertTrue(gate.fresh("video", 100.0))
        assertFalse(gate.fresh("video", Double.NaN))
        assertFalse(gate.fresh("audio", Double.POSITIVE_INFINITY))
        assertFalse(gate.fresh("unknown", 100.0))
        assertTrue(gate.fresh("audio", 101.0))
        assertTrue(gate.begin(2, 2, 2, 10, 11))
        assertTrue(gate.fresh("audio", 1.0))
    }
    @Test fun repeatedPoorReportCannotSupplyTwoCongestionSamples() {
        val gate = RemoteCallStatsGate(); val policy = AdaptiveVideoBitratePolicy()
        repeat(1000) { i ->
            assertTrue(gate.begin(1, 1, i + 1L, i.toLong(), i.toLong()))
            if (gate.fresh("audio", 100.0)) assertNull(policy.recommend(0.3, 1300.0, i.toLong()))
        }
        assertTrue(gate.fresh("audio", 101.0))
        assertEquals(350000, policy.recommend(0.3, 1300.0, 1001))
        policy.attemptFinished(350000, 1001, true)
        repeat(4) { assertNull(policy.recommend(0.0, 100.0, 12000 + it.toLong())) }
        policy.resetObservations()
        assertNull(policy.recommend(0.0, 100.0, 12010))
        repeat(3) { assertNull(policy.recommend(0.0, 100.0, 12011 + it.toLong())) }
        assertEquals(650000, policy.recommend(0.0, 100.0, 12020))
    }
}
