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

    @Test fun staleCumulativePacketsCannotPretendAudioContinues() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        val start = 100_000L
        assertFalse(t.observe(epoch, 10, 20, start)!!.connected)
        assertTrue(t.observe(epoch, 11, 21, start + 1)!!.connected)
        val expiry = start + CallRtpEvidenceTracker.MAX_AUDIO_EVIDENCE_AGE_MS + 2
        val frozen = t.observe(epoch, 11, 21, expiry)!!
        assertTrue("ICE is still connected; do not forcibly terminate a silent call",
            frozen.transportConnected)
        assertFalse("Cumulative totals are not proof of ongoing media", frozen.connected)
        assertEquals(0L, frozen.receivedAudio)
        assertEquals(0L, frozen.sentAudio)
        assertFalse(t.observe(epoch, 12, 21, expiry + 1)!!.connected)
        assertTrue(t.observe(epoch, 12, 22, expiry + 2)!!.connected)
    }

    @Test fun delayedClockSampleCannotOverwriteLiveAudioBaselines() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        assertFalse(t.observe(epoch, 100, 100, 10_000L, 1)!!.connected)
        assertTrue(t.observe(epoch, 105, 105, 10_100L, 2)!!.connected)
        assertNull(t.observe(epoch, 50, 50, 10_050L, 3))
        assertNull(t.observe(epoch, 200, 200, -1L, 4))
        assertTrue(t.observe(epoch, 106, 106, 10_101L, 5)!!.connected)
        t.updateTransport(false)
        t.updateTransport(true)
        val newEpoch = t.currentEpoch()
        assertFalse(t.observe(newEpoch, 300, 300, 1L, 1)!!.connected)
    }

    @Test fun oldClockValuesCannotReviveMediaProofOrOtherIceEpoch() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val first = t.currentEpoch()
        t.observe(first, 40, 40, 5000L)
        assertTrue(t.observe(first, 41, 41, 5001L)!!.connected)
        assertNull("Stale clock samples must be rejected",
            t.observe(first, 41, 41, 4000L))
        t.updateTransport(false)
        t.updateTransport(true)
        val second = t.currentEpoch()
        assertNull(t.observe(first, 99, 99, 7000L))
        assertFalse(t.observe(second, 41, 41, 7001L)!!.connected)
    }

    @Test fun malformedNativeStatsRevokePreviousRtpProofUntilBothDirectionsAdvanceAgain() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        assertFalse(t.observe(epoch, 100, 100, 1_000L, 1)!!.connected)
        assertTrue(t.observe(epoch, 101, 101, 1_100L, 2)!!.connected)

        val invalid = t.observe(epoch, -1, 101, 1_200L, 3)!!
        assertTrue("Invalid stats must not terminate an otherwise connected ICE transport",
            invalid.transportConnected)
        assertFalse("Malformed native stats must revoke old two-way RTP proof", invalid.connected)

        // Neither a delayed callback nor the same cumulative counters can
        // restore an old proof; both audio directions must actually progress.
        assertNull(t.observe(epoch, 999, 999, 1_190L, 4))
        assertNull(t.observe(epoch, 999, 999, 1_300L, 2))
        assertFalse(t.observe(epoch, 101, 101, 1_300L, 5)!!.connected)
        assertFalse(t.observe(epoch, 102, 101, 1_400L, 6)!!.connected)
        assertTrue(t.observe(epoch, 102, 102, 1_500L, 7)!!.connected)
    }

    @Test fun invalidSentDirectionAlsoInvalidatesReceiveProofWithoutChangingIceEpoch() {
        val t = CallRtpEvidenceTracker()
        t.updateTransport(true)
        val epoch = t.currentEpoch()
        t.observe(epoch, 20, 30, 2_000L, 1)
        assertTrue(t.observe(epoch, 21, 31, 2_100L, 2)!!.connected)
        assertFalse(t.observe(epoch, 21, -1, 2_200L, 3)!!.connected)
        assertEquals(epoch, t.currentEpoch())
        assertFalse(t.observe(epoch, 21, 31, 2_300L, 4)!!.connected)
        assertFalse(t.observe(epoch, 22, 31, 2_400L, 5)!!.connected)
        assertTrue(t.observe(epoch, 22, 32, 2_500L, 6)!!.connected)
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
