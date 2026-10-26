package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Synthetic Android exit codes only; private traces and real app data are never accessed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidRepeatedCrashRiskTest {
    private val now = 1_800_000_000_000L
    private val crash = ApplicationExitInfo.REASON_CRASH
    private val anr = ApplicationExitInfo.REASON_ANR

    @Test fun threeCrashesAndAnrsInsideHourAreReportedWithoutPrivateDetails() {
        val events = listOf(
            crash to now - 2_000L,
            anr to now - 20_000L,
            ApplicationExitInfo.REASON_CRASH_NATIVE to now - 90_000L
        )
        val state = CrashDiagnostics.assessRepeatedCrashRisk(events, now)
        assertEquals(CrashDiagnostics.RepeatedExitRisk.WITHIN_HOUR, state)
        assertFalse(state!!.label.contains("PIN"))
        assertFalse(state.label.contains("http"))
    }

    @Test fun repeatedDailyEventsRemainDistinctFromRecentCrashLoop() {
        val hour = TimeUnit.HOURS.toMillis(1)
        val events = listOf(crash to now - hour * 2,
            anr to now - hour * 4, crash to now - hour * 8)
        assertEquals(CrashDiagnostics.RepeatedExitRisk.WITHIN_DAY,
            CrashDiagnostics.assessRepeatedCrashRisk(events, now))
    }

    @Test fun duplicateOemEventsAndLowMemoryCannotFakeRepeatedCrash() {
        val one = crash to now - 100L
        assertNull(CrashDiagnostics.assessRepeatedCrashRisk(
            listOf(one, one, one,
                ApplicationExitInfo.REASON_LOW_MEMORY to now - 5L,
                ApplicationExitInfo.REASON_USER_REQUESTED to now - 8L), now))
    }

    @Test fun futureStaleAndUnsupportedEventsNeverTriggerWarnings() {
        assertNull(CrashDiagnostics.assessRepeatedCrashRisk(listOf(
            crash to now + 5L,
            anr to now - TimeUnit.DAYS.toMillis(9),
            Int.MAX_VALUE to now - 4L,
            crash to 0L
        ), now))
    }

    @Test fun seventyUntrustedHistoryItemsCannotOverrunFortyEventBudget() {
        val events = (0..70).map { i ->
            if (i < 40) ApplicationExitInfo.REASON_USER_REQUESTED to (now - i)
            else crash to (now - i)
        }
        assertNull(CrashDiagnostics.assessRepeatedCrashRisk(events, now))
    }

    @Test fun twoCrashesAreNotInferredToBeRecurringFailure() {
        assertNull(CrashDiagnostics.assessRepeatedCrashRisk(
            listOf(crash to now - 10L, anr to now - 20L), now))
    }
}
