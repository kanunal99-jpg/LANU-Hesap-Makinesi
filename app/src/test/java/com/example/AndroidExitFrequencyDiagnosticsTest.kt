package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Only synthetic timestamps and OS reason enums. No ANR traces or logcat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidExitFrequencyDiagnosticsTest {
    private val now = 1_800_000_000_000L

    @Test fun repeatedCrashAndAnrCategoriesAreCountedWithoutRawTraceMetadata() {
        val reasons = listOf(
            ApplicationExitInfo.REASON_CRASH to (now - 1_000),
            ApplicationExitInfo.REASON_CRASH to (now - 2_000),
            ApplicationExitInfo.REASON_ANR to (now - 3_000),
            ApplicationExitInfo.REASON_LOW_MEMORY to (now - 4_000)
        )
        val stats = CrashDiagnostics.summarizeReasons(reasons, now)
        assertEquals(4, stats.values.sum())
        assertEquals(2, stats[CrashDiagnostics.safeReasonLabel(ApplicationExitInfo.REASON_CRASH)])
        assertEquals(1, stats[CrashDiagnostics.safeReasonLabel(ApplicationExitInfo.REASON_ANR)])
        assertFalse(stats.toString().contains("PIN"))
        assertFalse(stats.toString().contains("stack"))
        assertFalse(stats.toString().contains("https"))
    }

    @Test fun duplicateFutureStaleNormalAndUnknownEventsCannotInflateDiagnosis() {
        val crash = ApplicationExitInfo.REASON_CRASH to (now - 100)
        val stats = CrashDiagnostics.summarizeReasons(listOf(
            crash, crash, // OEM duplicate historical row
            ApplicationExitInfo.REASON_CRASH to (now + 1),
            ApplicationExitInfo.REASON_CRASH to (now - TimeUnit.DAYS.toMillis(8)),
            ApplicationExitInfo.REASON_EXIT_SELF to (now - 50),
            Int.MAX_VALUE to (now - 40)
        ), now)
        assertEquals(1, stats.values.sum())
        assertEquals(1, stats.size)
    }

    @Test fun unexpectedOversizedHistoryIsBounded() {
        val entries = (0..100).map { ApplicationExitInfo.REASON_ANR to (now - it - 1L) }
        val counts = CrashDiagnostics.summarizeReasons(entries, now)
        assertEquals(40, counts.values.sum())
        assertEquals(1, counts.size)
    }

    @Test fun badTimeAndEmptyHistoryFailClosed() {
        assertTrue(CrashDiagnostics.summarizeReasons(emptyList(), now).isEmpty())
        assertTrue(CrashDiagnostics.summarizeReasons(
            listOf(ApplicationExitInfo.REASON_ANR to Long.MAX_VALUE), now
        ).isEmpty())
        assertTrue(CrashDiagnostics.summarizeReasons(
            listOf(ApplicationExitInfo.REASON_ANR to 0L), now
        ).isEmpty())
    }
}
