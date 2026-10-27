package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One sampled OS history must produce coherent, minimized private diagnosis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashSingleSnapshotTest {
    @Test fun oneSampleYieldsMatchingCountsRiskAndRecentExit() {
        val now = 500_000_000L
        val reasons = listOf(
            ApplicationExitInfo.REASON_ANR to (now - 9_000L),
            ApplicationExitInfo.REASON_ANR to (now - 8_000L),
            ApplicationExitInfo.REASON_ANR to (now - 7_000L),
            ApplicationExitInfo.REASON_CRASH to (now - 6_000L),
            ApplicationExitInfo.REASON_LOW_MEMORY to (now - 5_000L),
            ApplicationExitInfo.REASON_CRASH to (now + 500L),
            ApplicationExitInfo.REASON_ANR to (now - 9_000L)
        )
        val s = CrashDiagnostics.summarizeSnapshot(reasons, now)
        assertEquals(now - 5_000L, s.recent!!.timestamp)
        assertEquals(3, s.counts["Uygulama yanıt vermedi (ANR)"])
        assertEquals(1, s.counts["Uygulama kodu hatası (Java/Kotlin)"])
        assertEquals(CrashDiagnostics.RepeatedExitRisk.WITHIN_HOUR, s.repeatRisk)
        assertTrue(s.triage.orEmpty().startsWith("ANR"))
        assertFalse(s.toString().contains("PIN="))
        assertFalse(s.toString().contains("https://"))
    }

    @Test fun emptyFutureOrStaleHistoryRemainsFailClosed() {
        val now = 1_000_000_000L
        val none = CrashDiagnostics.summarizeSnapshot(emptyList(), now)
        assertNull(none.recent)
        assertEquals(emptyMap<String, Int>(), none.counts)
        assertNull(none.repeatRisk)
        assertNull(none.triage)
        val invalid = CrashDiagnostics.summarizeSnapshot(listOf(
            ApplicationExitInfo.REASON_ANR to (now + 1L),
            ApplicationExitInfo.REASON_CRASH to 0L,
            ApplicationExitInfo.REASON_CRASH_NATIVE to (now - 8L * 24 * 60 * 60 * 1000)
        ), now)
        assertEquals(none, invalid)
    }

    @Test fun onlyFirstFortyOsRecordsAreUsedForSnapshot() {
        val now = 500_000_000L
        val event = ApplicationExitInfo.REASON_CRASH to (now - 100L)
        val s = CrashDiagnostics.summarizeSnapshot(List(50) { event }, now)
        assertEquals(1, s.counts.values.sum())
        assertNull(s.repeatRisk)
    }
}
