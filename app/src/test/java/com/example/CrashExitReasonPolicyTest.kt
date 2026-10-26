package com.example

import android.app.ApplicationExitInfo
import com.example.core.diagnostics.CrashDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashExitReasonPolicyTest {
    @Test fun onlyDocumentedAbnormalOSReasonsHavePrivateSafeLabels() {
        val expected = mapOf(
            ApplicationExitInfo.REASON_CRASH to "Uygulama kodu hatası (Java/Kotlin)",
            ApplicationExitInfo.REASON_CRASH_NATIVE to "Android yerel kod hatası",
            ApplicationExitInfo.REASON_ANR to "Uygulama yanıt vermedi (ANR)",
            ApplicationExitInfo.REASON_LOW_MEMORY to "Düşük bellek nedeniyle kapanma",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "Aşırı kaynak kullanımı"
        )
        expected.forEach { (reason, label) ->
            assertEquals(label, CrashDiagnostics.safeReasonLabel(reason))
            assertFalse(label.contains("PIN"))
            assertFalse(label.contains("https:"))
        }
        assertNull(CrashDiagnostics.safeReasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertNull(CrashDiagnostics.safeReasonLabel(ApplicationExitInfo.REASON_EXIT_SELF))
        assertNull(CrashDiagnostics.safeReasonLabel(Int.MAX_VALUE))
    }

    @Test fun rejectStaleAndFutureExitsWithoutOverflowingClockArithmetic() {
        val now = 1_800_000_000_000L
        val sevenDays = TimeUnit.DAYS.toMillis(7)
        assertTrue(CrashDiagnostics.withinRetention(now, now))
        assertTrue(CrashDiagnostics.withinRetention(now - sevenDays, now))
        assertFalse(CrashDiagnostics.withinRetention(now - sevenDays - 1L, now))
        assertFalse(CrashDiagnostics.withinRetention(now + 1L, now))
        assertFalse(CrashDiagnostics.withinRetention(-1L, now))
        assertFalse(CrashDiagnostics.withinRetention(0L, now))
        assertFalse(CrashDiagnostics.withinRetention(1L, Long.MAX_VALUE))
    }
}
