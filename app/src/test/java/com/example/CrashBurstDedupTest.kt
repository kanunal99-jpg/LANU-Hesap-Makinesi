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
class CrashBurstDedupTest {
    @Test fun multipleReasonsFromOneOsExitCannotFabricateThreeCrashes() {
        val now = 1_800_000_000_000L
        val sameMoment = listOf(
            ApplicationExitInfo.REASON_CRASH to (now - 10_000),
            ApplicationExitInfo.REASON_CRASH_NATIVE to (now - 10_000),
            ApplicationExitInfo.REASON_ANR to (now - 10_000)
        )
        assertNull(CrashDiagnostics.assessRepeatedCrashRisk(sameMoment, now))
    }
    @Test fun threeDistinctExitsStillTriggerPrivateCategory() {
        val now = 1_800_000_000_000L
        val crashes = (1..3).map {
            ApplicationExitInfo.REASON_CRASH to (now - it * 1000L)
        }
        assertEquals(CrashDiagnostics.RepeatedExitRisk.WITHIN_HOUR,
            CrashDiagnostics.assessRepeatedCrashRisk(crashes, now))
    }
    @Test fun futureAndStaleEntriesCannotMakeFalseBurst() {
        val now = 1_800_000_000_000L
        val cases = listOf(
            ApplicationExitInfo.REASON_CRASH to (now + 10),
            ApplicationExitInfo.REASON_ANR to (now - 8L * 86_400_000),
            ApplicationExitInfo.REASON_CRASH_NATIVE to 0L
        )
        assertNull(CrashDiagnostics.assessRepeatedCrashRisk(cases, now))
    }
}
