package com.example

import com.example.core.calls.CallRtpEvidenceTracker
import org.junit.Assert.*
import org.junit.Test

class CallRtpClockRegressionTest {
    @Test fun clockRegressionCannotResurrectExpiredAudioOrRollbackCounter() {
        val tracker = CallRtpEvidenceTracker()
        tracker.updateTransport(true)
        val epoch = tracker.currentEpoch()
        assertFalse(tracker.observe(epoch, 100, 100, 20_000, 1)!!.connected)
        assertTrue(tracker.observe(epoch, 102, 102, 20_001, 2)!!.connected)
        assertNull(tracker.observe(epoch, 105, 105, 19_000, 3))
        // A rejected stale completion does not consume the newer stats sequence.
        assertTrue(tracker.observe(epoch, 104, 104, 20_002, 3)!!.connected)
        val stale = tracker.observe(epoch, 104, 104, 36_000, 4)!!
        assertFalse(stale.connected)
    }
    @Test fun freshIceEpochMayResetClockBaselineWithoutAcceptingOldEpoch() {
        val tracker = CallRtpEvidenceTracker()
        tracker.updateTransport(true)
        val old = tracker.currentEpoch()
        tracker.observe(old, 1, 1, 90_000, 1)
        tracker.updateTransport(false)
        tracker.updateTransport(true)
        val next = tracker.currentEpoch()
        assertNull(tracker.observe(old, 10, 10, 90_001, 2))
        assertFalse(tracker.observe(next, 10, 10, 100, 1)!!.connected)
        assertTrue(tracker.observe(next, 11, 11, 101, 2)!!.connected)
    }
}
