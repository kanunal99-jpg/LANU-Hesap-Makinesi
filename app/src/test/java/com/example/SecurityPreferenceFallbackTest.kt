package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.SecurityEventTracker
import com.example.core.security.LegacyInsecurePreferencesCleanup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityPreferenceFallbackTest {
    @Test fun `legacy insecure audit and biometric fallback files are cleared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bio = context.getSharedPreferences("secure_settings_prefs_fallback", Context.MODE_PRIVATE)
        val logs = context.getSharedPreferences("secure_security_events_fallback", Context.MODE_PRIVATE)
        assertTrue(bio.edit().putBoolean("biometric_enabled", true).commit())
        assertTrue(logs.edit().putString("events_list", "sensitive old event").commit())
        LegacyInsecurePreferencesCleanup.clean(context)
        assertFalse(bio.getBoolean("biometric_enabled", false))
        assertFalse(logs.contains("events_list"))
    }

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

    @Test fun `RAM only audit events remain visible and recover after encrypted writes resume`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("audit-recovery-" + System.nanoTime(), Context.MODE_PRIVATE)
        var failCommit = false
        val flaky = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = if (failCommit) false else delegate.commit()
                }
            }
        }
        try {
            val tracker = SecurityEventTracker(context) { flaky }
            tracker.logEvent("STORED", "Already encrypted")
            failCommit = true
            tracker.logEvent("RAM_FALLBACK", "Encrypted commit failed")
            assertEquals(listOf("RAM_FALLBACK", "STORED"),
                tracker.getLoggedEvents().map { it["type"] })
            val before = SecurityEventTracker(context) { prefs }
            assertEquals(listOf("STORED"), before.getLoggedEvents().map { it["type"] })

            failCommit = false
            tracker.logEvent("RECOVERED", "Encrypted store writable again")
            assertEquals(listOf("RECOVERED", "RAM_FALLBACK", "STORED"),
                tracker.getLoggedEvents().map { it["type"] })
            val restarted = SecurityEventTracker(context) { prefs }
            assertEquals(listOf("RECOVERED", "RAM_FALLBACK", "STORED"),
                restarted.getLoggedEvents().map { it["type"] })
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun `audit recovery respects 50 event limit without writing plaintext fallback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("audit-cap-" + System.nanoTime(), Context.MODE_PRIVATE)
        var failCommit = true
        val flaky = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean = if (failCommit) false else delegate.commit()
                }
            }
        }
        try {
            val tracker = SecurityEventTracker(context) { flaky }
            repeat(55) { tracker.logEvent("EVENT_$it", "Test") }
            assertEquals(50, tracker.getLoggedEvents().size)
            assertEquals("EVENT_54", tracker.getLoggedEvents().first()["type"])
            failCommit = false
            tracker.logEvent("RECOVERED", "Test")
            val restarted = SecurityEventTracker(context) { prefs }
            assertEquals(50, restarted.getLoggedEvents().size)
            assertEquals("RECOVERED", restarted.getLoggedEvents().first()["type"])
            assertEquals("EVENT_6", restarted.getLoggedEvents().last()["type"])
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
