package com.example

import com.example.core.calls.CallRtpEvidenceTracker
import org.junit.Assert.*
import org.junit.Test

/** Synthetic WebRTC stats counters: no mic/camera and no private SDP metadata. */
class RtcAsyncStatsOrderingTest {
    @Test fun olderAsyncCompletionInSameIceEpochCannotRollbackRtpProof() {
        val tracker = CallRtpEvidenceTracker()
        tracker.updateTransport(true)
        val epoch = tracker.currentEpoch()
        assertFalse(tracker.observe(epoch, 100, 100, 1000L, 1L)!!.connected)
        assertTrue(tracker.observe(epoch, 105, 105, 1002L, 3L)!!.connected)
        assertNull("A delayed request #2 cannot reset counters after request #3",
            tracker.observe(epoch, 101, 101, 1003L, 2L))
        assertTrue(tracker.observe(epoch, 106, 106, 1004L, 4L)!!.connected)
    }

    @Test fun duplicateSnapshotSequenceCannotFabricateProgress() {
        val tracker = CallRtpEvidenceTracker()
        tracker.updateTransport(true)
        val epoch = tracker.currentEpoch()
        assertFalse(tracker.observe(epoch, 1, 1, 1000L, 20L)!!.connected)
        assertNull(tracker.observe(epoch, 100, 100, 1001L, 20L))
        assertTrue(tracker.observe(epoch, 2, 2, 1002L, 21L)!!.connected)
    }

    @Test fun oldEpochStaysRejectedAfterIceRecovery() {
        val tracker = CallRtpEvidenceTracker()
        tracker.updateTransport(true)
        val old = tracker.currentEpoch()
        tracker.observe(old, 3, 3, 1000L, 5L)
        tracker.updateTransport(false)
        tracker.updateTransport(true)
        val fresh = tracker.currentEpoch()
        assertNull(tracker.observe(old, 99, 99, 1001L, 8L))
        assertFalse(tracker.observe(fresh, 99, 99, 1002L, 9L)!!.connected)
        assertTrue(tracker.observe(fresh, 100, 100, 1003L, 10L)!!.connected)
    }
}
