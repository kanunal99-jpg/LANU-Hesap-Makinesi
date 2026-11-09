package com.example
import com.example.core.diagnostics.PrivateConnectionMetrics as M
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
class PrivateConnectionMetricsTest {
    @After fun clean() { M.setEnabled(false) }
    @Test fun optInGenerationAndClearRejectLateCompletions() {
        M.setEnabled(false); M.record(M.Stage.SYNC, M.token(), 0, 100, true)
        assertEquals(0, M.summary(M.Stage.SYNC).samples)
        M.setEnabled(true); val old = M.token(); M.clear()
        M.record(M.Stage.SYNC, old, 0, 100, true)
        assertEquals(0, M.summary(M.Stage.SYNC).samples)
        M.record(M.Stage.SYNC, M.token(), 0, 100, true)
        assertEquals(100L, M.summary(M.Stage.SYNC).p95Ms)
        M.setEnabled(false); assertNull(M.token()); assertEquals(0, M.summary(M.Stage.SYNC).samples)
    }
    @Test fun boundedRealSamplesPercentilesFailureAndInvalidClocks() {
        M.setEnabled(true); val token = M.token()
        for (n in 1L..200L) M.record(M.Stage.CALL_SETUP, token, 0, n, true)
        val s = M.summary(M.Stage.CALL_SETUP)
        assertEquals(128, s.samples); assertEquals(136L, s.p50Ms); assertEquals(194L, s.p95Ms)
        M.record(M.Stage.CALL_SETUP, token, 20, 10, true)
        M.record(M.Stage.CALL_SETUP, token, -1, 10, true)
        M.record(M.Stage.CALL_SETUP, token, 0, Long.MAX_VALUE, true)
        M.record(M.Stage.CALL_SETUP, token, 0, 500, false)
        assertEquals(128, M.summary(M.Stage.CALL_SETUP).samples)
        assertEquals(1, M.summary(M.Stage.CALL_SETUP).failed)
        assertNull(M.summary(M.Stage.TURN_ALLOCATION).p50Ms)
        assertFalse(M.safeSummary().contains("500 ms"))
    }
}
