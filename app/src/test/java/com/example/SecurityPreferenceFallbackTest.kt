package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.SecurityEventTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityPreferenceFallbackTest {
    @Test fun `unavailable keystore leaves audit events only in volatile memory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failedStore = { _: Context ->
            throw SecurityException("Simulated encrypted preferences failure")
        }
        val tracker = SecurityEventTracker(context, failedStore)
        tracker.logEvent("FAILED_PIN_ATTEMPT", "Test-only detail")
        tracker.logEvent("BRUTE_FORCE_LOCKOUT", "Another detail")
        assertEquals(2, tracker.getLoggedEvents().size)
        assertEquals("BRUTE_FORCE_LOCKOUT", tracker.getLoggedEvents().first()["type"])
        val restarted = SecurityEventTracker(context, failedStore)
        assertTrue("Volatile fallback must not persist through a new instance", restarted.getLoggedEvents().isEmpty())
        val exposed = context.getSharedPreferences("secure_security_events_fallback", Context.MODE_PRIVATE)
        assertTrue("No security event data may enter plaintext preferences", exposed.all.isEmpty())
        tracker.clearEvents()
        assertTrue(tracker.getLoggedEvents().isEmpty())
    }
}
