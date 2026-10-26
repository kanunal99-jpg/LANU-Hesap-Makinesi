package com.example

import com.example.core.calls.CallRtpEvidenceTracker
import org.junit.Assert.*
import org.junit.Test

/** Test only synthetic RTP counters. No camera/mic access or packet logging. */
class RtcRtpRecoveryMatrixTest {
    @Test fun staleAudioCannotBeReusedAfterClockExpiryAndReconnect() {
        val t = CallRtpEvidenceTracker()
        assertFalse(t.updateTransport(true).connected)
        val first = t.currentEpoch()
        assertFalse(t.observe(first, 10, 10, 1000L)!!.connected)
        assertTrue(t.observe(first, 11, 11, 1001L)!!.connected)
        val frozenAt = 1001L + CallRtpEvidenceTracker.MAX_AUDIO_EVIDENCE_AGE_MS + 1L
        assertFalse(t.observe(first, 11, 11, frozenAt)!!.connected)
        assertTrue(t.observe(first, 12, 12, frozenAt + 1L)!!.connected)
        assertFalse(t.updateTransport(false).connected)
        t.updateTransport(true)
        val second = t.currentEpoch()
        assertNotEquals(first, second)
        assertNull(t.observe(first, 10_000, 10_000, frozenAt + 2L))
        assertFalse(t.observe(second, 12, 12, frozenAt + 2L)!!.connected)
        assertTrue(t.observe(second, 13, 13, frozenAt + 3L)!!.connected)
    }

    @Test fun repeatedTransportUpAndDownDoesNotProduceSyntheticConnection() {
        val t = CallRtpEvidenceTracker()
        repeat(80) {
            t.updateTransport(true)
            val epoch = t.currentEpoch()
            assertFalse(t.observe(epoch, 100, 100, 1000L + it)!!.connected)
            assertFalse(t.updateTransport(false).connected)
            assertNull(t.observe(epoch, 101, 101, 2000L + it))
        }
    }
}
