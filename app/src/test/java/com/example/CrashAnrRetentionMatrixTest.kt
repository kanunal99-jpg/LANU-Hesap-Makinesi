package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Pure synthetic OS reason/time matrix; never reads device logs or ANR traces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashAnrRetentionMatrixTest {
    private val now = 1_800_000_000_000L

    @Test fun retentionWindowAndClockRollbackAreStrict() {
        val week = TimeUnit.DAYS.toMillis(7)
        assertTrue(CrashDiagnostics.withinRetention(now - week, now))
        assertFalse(CrashDiagnostics.withinRetention(now - week - 1, now))
        assertFalse(CrashDiagnostics.withinRetention(now + 1, now))
        assertFalse(CrashDiagnostics.withinRetention(0, now))
        assertFalse(CrashDiagnostics.withinRetention(Long.MAX_VALUE, now))
    }

    @Test fun frequencyCannotLeakUnknownReasonsOrCountDuplicateEntries() {
        val entries = listOf(
            ApplicationExitInfo.REASON_ANR to (now - 100),
            ApplicationExitInfo.REASON_ANR to (now - 100),
            ApplicationExitInfo.REASON_CRASH to (now - 101),
            ApplicationExitInfo.REASON_EXIT_SELF to (now - 102),
            Int.MAX_VALUE to (now - 103)
        )
        val summary = CrashDiagnostics.summarizeReasons(entries, now)
        assertEquals(2, summary.values.sum())
        assertEquals(1, summary[CrashDiagnostics.safeReasonLabel(ApplicationExitInfo.REASON_ANR)])
        assertEquals(1, summary[CrashDiagnostics.safeReasonLabel(ApplicationExitInfo.REASON_CRASH)])
        assertEquals(null, CrashDiagnostics.safeReasonLabel(Int.MAX_VALUE))
    }

    @Test fun oversizedSyntheticHistoryDoesNotExtendBeyondFortyEvents() {
        val entries = (1..1000).map { ApplicationExitInfo.REASON_ANR to (now - it.toLong()) }
        val summary = CrashDiagnostics.summarizeReasons(entries, now)
        assertEquals(40, summary.values.sum())
    }
}
