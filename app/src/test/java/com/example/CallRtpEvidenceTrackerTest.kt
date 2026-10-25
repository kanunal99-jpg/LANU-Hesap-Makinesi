package com.example

import com.example.core.calls.CallRtpEvidenceTracker
import org.junit.Assert.*
import org.junit.Test

/** No fake connection from cumulative counters, lost transport or delayed getStats callbacks. */
class CallRtpEvidenceTrackerTest {
    @Test fun firstSnapshotIsOnlyBaselineAndBothDirectionsMustProgress() {
        val t = CallRtpEvidenceTracker()
        assertFalse(t.updateTransport(true).connected)
        val epoch = t.currentEpoch()
        assertFalse(t.observe(epoch, 100, 500)!!.connected)
        assertFalse(t.observe(epoch, 105, 500)!!.connected)
        val actual = t.observe(epoch, 105, 507)!!
        assertTrue("Both fresh RX and TX must progress in the same transport epoch", actual.connected)
        assertEquals(5L, actual.receivedAudio)
        assertEquals(7L, actual.sentAudio)
    }

    @Test fun reconnectDoesNotReusePreviousAudioPackets() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val first = t.currentEpoch()
        t.observe(first, 70, 80)
        assertTrue(t.observe(first, 75, 85)!!.connected)
        assertFalse(t.updateTransport(false).connected)
        t.updateTransport(true)
        val second = t.currentEpoch()
        assertNotEquals(first, second)
        assertFalse("Old RTP counters cannot prove restored audio", t.observe(second, 75, 85)!!.connected)
        assertFalse(t.observe(second, 75, 85)!!.connected)
        assertFalse(t.observe(second, 76, 85)!!.connected)
        assertTrue(t.observe(second, 76, 86)!!.connected)
    }

    @Test fun staleAsyncCallbackFromPriorIceEpochIsRejected() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val old = t.currentEpoch()
        t.observe(old, 10, 20)
        t.updateTransport(false)
        assertNull("Late old stats must not be attributed to a new transport", t.observe(old, 15, 25))
        t.updateTransport(true)
        val next = t.currentEpoch()
        assertNull(t.observe(old, 500, 500))
        assertFalse(t.observe(next, 500, 500)!!.connected)
        assertTrue(t.observe(next, 501, 501)!!.connected)
    }

    @Test fun counterResetOrTrackReplacementRequiresFreshPacketProgress() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        t.observe(epoch, 100, 100)
        assertTrue(t.observe(epoch, 105, 105)!!.connected)
        assertFalse("Reset counters invalidate prior proof", t.observe(epoch, 0, 0)!!.connected)
        assertFalse(t.observe(epoch, 0, 1)!!.connected)
        assertTrue(t.observe(epoch, 1, 1)!!.connected)
    }

    @Test fun repeatedConnectedEventDoesNotResetValidEvidenceBaseline() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        t.observe(epoch, 10, 10)
        t.updateTransport(true)
        assertEquals(epoch, t.currentEpoch())
        assertTrue(t.observe(epoch, 11, 11)!!.connected)
    }

    @Test fun nonConnectedAndNegativeCountersNeverShowConnected() {
        val t = CallRtpEvidenceTracker()
        assertFalse(t.observe(t.currentEpoch(), 100, 100)!!.connected)
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        assertFalse(t.observe(epoch, -1, 20)!!.connected)
        assertFalse(t.observe(epoch, 0, 0)!!.connected)
        assertFalse(t.observe(epoch, 10, -1)!!.connected)
    }
}
