package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakeNotification
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CallWakeNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        CallWakePreferences.setEnabled(context, false)
        CallWakePolicy.privateAreaVisible = false
    }

    @Test fun defaultIsPrivateAndRequiresExplicitConsent() {
        CallWakePreferences.setEnabled(context, false)
        assertFalse(CallWakePreferences.isEnabled(context))
        assertFalse(CallWakeNotification.show(context, mapOf(
            "type" to CallWakePolicy.TYPE, "at" to "1710000000000"), 1710000000000))
        assertTrue(CallWakePreferences.setEnabled(context, true))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.setEnabled(context, false))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test fun arbitraryOrExpiredChatAndMediaEventsNeverWake() {
        val now = 1710000000000L
        assertFalse(CallWakePolicy.eligible(mapOf("type" to "chat", "at" to now.toString()), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to "media", "at" to now.toString()), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to now.toString(), "peer" to "secret"), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to (now-66000).toString()), now))
        assertFalse(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to (now+16000).toString()), now))
        assertTrue(CallWakePolicy.eligible(mapOf("type" to CallWakePolicy.TYPE,
            "at" to (now-60000).toString()), now))
    }

    @Test fun identicalWakeCannotRepeatAndPrivateForegroundSuppressesNotification() {
        val timestamp = 1710000000700L
        assertTrue(CallWakePolicy.claim(timestamp))
        assertFalse(CallWakePolicy.claim(timestamp))
        CallWakePreferences.setEnabled(context, true)
        CallWakePolicy.privateAreaVisible = true
        assertFalse(CallWakeNotification.show(context, mapOf(
            "type" to CallWakePolicy.TYPE, "at" to timestamp.toString()), timestamp))
    }
}
