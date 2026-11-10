package com.example

import com.example.core.calls.CallStatsSamplingGate
import org.junit.Assert.*
import org.junit.Test

class CallStatsSamplingGateTest {
    @Test fun oneSlowNativeSampleDoesNotQueueFourRedundantProbes() {
        val gate = CallStatsSamplingGate()
        assertTrue(gate.begin(1, 1000L))
        for (i in 2L..5L)
            assertFalse(gate.begin(i, 1000L + i * 500L))
        assertTrue(gate.finish(1L))
        assertTrue(gate.begin(6, 4000L))
        assertFalse(gate.finish(1))
        assertTrue(gate.finish(6))
    }

    @Test fun aNeverReturningOemCallbackCanBeReplacedAfterBoundedDeadline() {
        val gate = CallStatsSamplingGate()
        assertEquals(5_000L, CallStatsSamplingGate.MAX_IN_FLIGHT_MS)
        assertTrue(gate.begin(11, 100L))
        assertFalse(gate.begin(12, 5099L))
        assertTrue(gate.begin(13, 5100L))
        assertFalse("Old report must never be accepted as new RTP proof", gate.finish(11))
        assertTrue(gate.finish(13))
        assertTrue(gate.begin(14, 5200L))
    }

    @Test fun staleCallbackAfterCallEndCannotReviveSamplingEpoch() {
        val gate = CallStatsSamplingGate()
        assertFalse(gate.begin(-1, 0))
        assertFalse(gate.begin(1, -1))
        assertTrue(gate.begin(1, 0))
        gate.invalidate()
        assertFalse(gate.finish(1))
        assertTrue(gate.begin(2, 100))
        assertFalse(gate.finish(1))
        assertTrue(gate.finish(2))
    }

    @Test fun clockRollbackNeverBlocksRecoveryForever() {
        val gate = CallStatsSamplingGate()
        assertTrue(gate.begin(1, 1_000L))
        assertTrue(gate.begin(2, 900L))
        assertFalse(gate.finish(1))
        assertTrue(gate.finish(2))
    }
}
