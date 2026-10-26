package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure Android exit-metadata classification, never reads user messages or traces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashExitClassifierTest {
    private val now = 1_780_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun identifiesRealCrashAnrMemoryAndNativeExitWithoutRawTrace() {
        val cases = mapOf(
            ApplicationExitInfo.REASON_CRASH to "Java/Kotlin",
            ApplicationExitInfo.REASON_CRASH_NATIVE to "yerel kod",
            ApplicationExitInfo.REASON_ANR to "ANR",
            ApplicationExitInfo.REASON_LOW_MEMORY to "Düşük bellek",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "Aşırı kaynak"
        )
        cases.forEach { (reason, label) ->
            val result = CrashDiagnostics.classify(reason, now - 3000, now)
            assertNotNull(result)
            assertTrue(result!!.label.contains(label))
            assertEquals(now - 3000, result.timestamp)
            assertFalse(result.label.contains("PIN"))
        }
    }

    @Test fun futureOrInvalidTimestampCannotMasqueradeAsANewCrash() {
        for (stamp in listOf(-1L, 0L, now + 1, now + 60_000, now - 7 * day - 1)) {
            assertNull(CrashDiagnostics.classify(ApplicationExitInfo.REASON_ANR, stamp, now))
        }
        assertNotNull(CrashDiagnostics.classify(ApplicationExitInfo.REASON_ANR, now - 7 * day, now))
        assertNotNull(CrashDiagnostics.classify(ApplicationExitInfo.REASON_CRASH, now, now))
    }

    @Test fun normalExitOrUnknownCauseDoesNotCreateFalseCrashEvidence() {
        assertNull(CrashDiagnostics.classify(ApplicationExitInfo.REASON_USER_REQUESTED, now - 1000, now))
        assertNull(CrashDiagnostics.classify(Int.MAX_VALUE, now - 1000, now))
    }
}
