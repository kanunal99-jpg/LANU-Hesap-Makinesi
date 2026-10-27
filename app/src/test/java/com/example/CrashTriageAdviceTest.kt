package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashTriageAdviceTest {
    @Test fun noDataCannotBeCalledDiagnosed() {
        assertNull(CrashDiagnostics.triageHint(emptyList(), 100_000L))
        assertNull(CrashDiagnostics.triageHint(
            listOf(ApplicationExitInfo.REASON_ANR to 0L), 100_000L))
    }
    @Test fun onlyRecentRelevantSystemReasonsInformInvestigation() {
        val now = 200_000_000L
        val records = listOf(
            ApplicationExitInfo.REASON_ANR to (now - 1000L),
            ApplicationExitInfo.REASON_ANR to (now - 2000L),
            ApplicationExitInfo.REASON_CRASH to (now - 3000L),
            ApplicationExitInfo.REASON_LOW_MEMORY to (now - 4000L),
            ApplicationExitInfo.REASON_CRASH_NATIVE to (now + 5000L))
        val message = CrashDiagnostics.triageHint(records, now)!!
        assertTrue(message.startsWith("ANR"))
        assertFalse(message.contains("PIN"))
        assertFalse(message.contains("SDP"))
        assertFalse(message.contains("https://"))
    }
    @Test fun duplicateOldAndFutureEntriesCannotInflateIncidentCounts() {
        val now = 500_000_000L
        val records = listOf(ApplicationExitInfo.REASON_CRASH_NATIVE to (now - 100L))
        assertTrue(CrashDiagnostics.triageHint(List(50) { records[0] }, now)!!
            .startsWith("Yerel çökme"))
        assertNull(CrashDiagnostics.triageHint(listOf(
            ApplicationExitInfo.REASON_CRASH to (now - 8L * 24 * 60 * 60 * 1000)), now))
    }
}
