package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakeNotification
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import com.example.core.messaging.MessageWakeNotification
import com.example.core.messaging.MessageWakePolicy
import com.example.core.messaging.MessageWakePreferences
import com.example.core.notifications.PublicNotificationIds
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Android Notification fields, not just an isolated time formula. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WakeNotificationRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_720_000_000_000L
    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After fun cleanup() {
        CallWakePreferences.setEnabled(context, false)
        CallWakePreferences.acknowledgeRemoteRevoke(context)
        MessageWakePreferences.disableAndQueueRemoteRevoke(context)
        MessageWakePreferences.acknowledgeRemoteRevoke(context)
        CallWakePolicy.privateAreaVisible = false
        manager.cancel(PublicNotificationIds.INCOMING_CALL)
        manager.cancel(PublicNotificationIds.MESSAGE_WAKE)
    }

    @Test fun delayedCallPostsOnlyRemainingRingtoneLifetime() {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        val at = now - 44_000L
        assertTrue(CallWakeNotification.show(context,
            mapOf("type" to CallWakePolicy.TYPE, "at" to at.toString()), now))
        val shown = manager.activeNotifications.single {
            it.id == PublicNotificationIds.INCOMING_CALL
        }.notification
        assertEquals(1_000L, shown.timeoutAfter)
        // A consenting user sees a generic redacted call, not a hidden chat.
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertFalse(CallWakeNotification.show(context,
            mapOf("type" to CallWakePolicy.TYPE, "at" to (now-45_000L).toString()), now))
    }

    @Test fun delayedMessageHasBoundedVisibleLifetimeAndCannotDismissCall() {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
        val at = now - 119_000L
        assertTrue(MessageWakeNotification.show(context,
            mapOf("type" to MessageWakePolicy.TYPE, "at" to at.toString()), now))
        val shown = manager.activeNotifications.single {
            it.id == PublicNotificationIds.MESSAGE_WAKE
        }.notification
        assertEquals(1_000L, shown.timeoutAfter)
        assertEquals(Notification.VISIBILITY_SECRET, shown.visibility)
        assertFalse(MessageWakeNotification.show(context,
            mapOf("type" to MessageWakePolicy.TYPE, "at" to (now - 120_000L).toString()), now))
    }
}
