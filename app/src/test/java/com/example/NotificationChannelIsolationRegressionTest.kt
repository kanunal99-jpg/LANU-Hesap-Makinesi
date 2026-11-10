package com.example

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.core.calls.CallForegroundService
import com.example.core.calls.CallWakeNotification
import com.example.core.messaging.MessageWakeNotification
import com.example.core.messaging.MessageWakePolicy
import com.example.core.notifications.PublicNotificationIds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NotificationManager IDs are app-global, NOT scoped to a channel. Regresses
 * the legacy 8130 collision between neutral message alerts and the ongoing
 * microphone/camera FGS. No microphone or private-area access is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationChannelIsolationRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun post(id: Int, channel: String) {
        manager.notify(id, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_lanu_monochrome)
            .setContentTitle("LANU Hesap Makinesi")
            .setOngoing(id == PublicNotificationIds.ACTIVE_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build())
    }

    @Test fun callMessageAndLiveLocationHaveFourIndependentNotificationIds() {
        val ids = listOf(
            PublicNotificationIds.INCOMING_CALL,
            PublicNotificationIds.MESSAGE_WAKE,
            PublicNotificationIds.ACTIVE_CALL,
            PublicNotificationIds.LIVE_LOCATION
        )
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(8129, PublicNotificationIds.INCOMING_CALL)
        // Preserve old message ID so existing opt-out can still retract it.
        assertEquals(8130, PublicNotificationIds.MESSAGE_WAKE)
        assertNotEquals(8130, PublicNotificationIds.ACTIVE_CALL)
        assertEquals(4137, PublicNotificationIds.LIVE_LOCATION)
    }

    @Test fun messageOptOutCannotCancelOngoingVoiceOrVideoCallNotification() {
        MessageWakeNotification.ensureChannel(manager)
        post(PublicNotificationIds.ACTIVE_CALL, CallForegroundService.CHANNEL)
        post(PublicNotificationIds.MESSAGE_WAKE, MessageWakeNotification.CHANNEL_ID)
        assertTrue(manager.activeNotifications.any { it.id == PublicNotificationIds.ACTIVE_CALL })
        assertTrue(manager.activeNotifications.any { it.id == PublicNotificationIds.MESSAGE_WAKE })
        assertTrue(MessageWakeNotification.cancel(context))
        assertFalse(manager.activeNotifications.any { it.id == PublicNotificationIds.MESSAGE_WAKE })
        assertTrue("A message opt-out must never erase the microphone/camera FGS notification",
            manager.activeNotifications.any { it.id == PublicNotificationIds.ACTIVE_CALL })
        manager.cancel(PublicNotificationIds.ACTIVE_CALL)
    }

    @Test fun incomingCallDismissalCannotCancelMessageOrActiveCall() {
        MessageWakeNotification.ensureChannel(manager)
        CallWakeNotification.ensureChannel(manager)
        post(PublicNotificationIds.INCOMING_CALL, CallWakeNotification.CHANNEL_ID)
        post(PublicNotificationIds.MESSAGE_WAKE, MessageWakeNotification.CHANNEL_ID)
        post(PublicNotificationIds.ACTIVE_CALL, CallForegroundService.CHANNEL)
        assertTrue(CallWakeNotification.cancel(context))
        assertFalse(manager.activeNotifications.any { it.id == PublicNotificationIds.INCOMING_CALL })
        assertTrue(manager.activeNotifications.any { it.id == PublicNotificationIds.MESSAGE_WAKE })
        assertTrue(manager.activeNotifications.any { it.id == PublicNotificationIds.ACTIVE_CALL })
        manager.cancel(PublicNotificationIds.MESSAGE_WAKE)
        manager.cancel(PublicNotificationIds.ACTIVE_CALL)
    }

    @Test fun userMutedChannelStillDeliversSilentNotificationButBlockedNeverDoes() {
        assertTrue(MessageWakePolicy.channelCanDisplay(25, null))
        assertFalse(MessageWakePolicy.channelCanDisplay(28, null))
        assertFalse(MessageWakePolicy.channelCanDisplay(28, NotificationManager.IMPORTANCE_NONE))
        for (importance in listOf(NotificationManager.IMPORTANCE_MIN,
                NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_DEFAULT,
                NotificationManager.IMPORTANCE_HIGH)) {
            assertTrue(MessageWakePolicy.channelCanDisplay(28, importance))
        }
    }

    @Test fun brokenMessageSyncSchedulerCannotSuppressIndependentNeutralAlert() {
        for (failure in listOf<Throwable>(
            IllegalStateException("WorkManager unavailable"),
            SecurityException("OEM restricted"),
            NoSuchMethodError("OEM ABI mismatch")
        )) {
            var posted = 0
            assertTrue(MessageWakePolicy.dispatchIndependent(
                allowSync = true,
                enqueueSync = { throw failure },
                showAlert = { posted++; true }
            ))
            assertEquals(1, posted)
        }
        var scheduled = 0
        var shown = 0
        assertTrue(MessageWakePolicy.dispatchIndependent(
            allowSync = false,
            enqueueSync = { scheduled++ },
            showAlert = { shown++; true }
        ))
        assertEquals(0, scheduled)
        assertEquals(1, shown)
        assertFalse(MessageWakePolicy.dispatchIndependent(
            allowSync = true,
            enqueueSync = { throw IllegalStateException("cannot sync") },
            showAlert = { false }
        ))
    }
}
