package com.example

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakeNotification
import com.example.core.calls.CallWakeTapPolicy
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        CallWakePreferences.acknowledgeRemoteRevoke(context)
        CallWakePolicy.privateAreaVisible = false
    }

    @Test fun notificationTapIsReentryOnlyAndNeverAChatDeepLink() {
        assertTrue(CallWakeTapPolicy.requiresFreshEntry(CallWakeTapPolicy.ACTION))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry(null))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry(android.content.Intent.ACTION_MAIN))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry("lanu_chat_or_media"))
        assertFalse(CallWakeTapPolicy.requiresFreshEntry("LANU_CALL_WAKE"))
        assertTrue(CallWakeTapPolicy.ACTION.startsWith(context.packageName))
    }

    @Test fun AndroidSixThroughSixteenPermissionBehaviorIsExplicit() {
        for (api in 23..32) {
            assertFalse(CallWakePolicy.requiresNotificationPermission(api))
        }
        for (api in 33..36) {
            assertTrue(CallWakePolicy.requiresNotificationPermission(api))
        }
        assertFalse(CallWakePreferences.isEnabled(context))
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

    @Test fun disabledConsentPersistsPendingRemoteRevokeUntilAcknowledged() {
        assertTrue(CallWakePreferences.setEnabled(context, true))
        assertTrue(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        // An unrelated preference write must never erase unconfirmed revoke.
        assertTrue(CallWakePreferences.setEnabled(context, false))
        assertTrue(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertTrue(CallWakePreferences.acknowledgeRemoteRevoke(context))
        assertFalse(CallWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
    }

    @Test @Config(sdk = [29]) fun callOnlyChannelUsesSystemRingtoneHighImportanceAndHidesLockedContent() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        CallWakeNotification.ensureChannel(manager)
        val channel = manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), channel.sound)
        assertNotNull(channel.audioAttributes)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, channel.audioAttributes.usage)
    }

    @Test fun oldCallChannelExplicitlyMutedDoesNotBecomeLoudOnUpgrade() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            "lanu_private_calls_v1", "Old muted call channel", NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) })
        CallWakeNotification.ensureChannel(manager)
        val channel = manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(null, channel.sound)
    }

    @Test fun failedAndroidNotificationAllowsRetryButNotDuplicateSuccess() {
        val timestamp = 1710000000901L
        var deliveries = 0

        // An OEM NotificationManager throw must not consume the only ring.
        assertFalse(CallWakePolicy.publishOnce(timestamp) {
            deliveries++
            throw SecurityException("simulated revoked notification permission")
        })
        assertEquals(1, deliveries)

        assertTrue(CallWakePolicy.publishOnce(timestamp) { deliveries++ })
        assertEquals(2, deliveries)

        // The successfully delivered wake remains deduplicated.
        assertFalse(CallWakePolicy.publishOnce(timestamp) { deliveries++ })
        assertEquals(2, deliveries)
    }

    @Test fun runtimeNotificationFailureAlsoReleasesOnlyItsOwnTimestamp() {
        val failedTimestamp = 1710000000902L
        val deliveredTimestamp = 1710000000903L
        assertTrue(CallWakePolicy.publishOnce(deliveredTimestamp) { })
        assertFalse(CallWakePolicy.publishOnce(failedTimestamp) {
            throw IllegalStateException("simulated OEM binder failure")
        })
        assertFalse("Another call's committed wake must remain deduped",
            CallWakePolicy.publishOnce(deliveredTimestamp) { })
        assertTrue("Only the undelivered wake can retry",
            CallWakePolicy.publishOnce(failedTimestamp) { })
    }

    @Test fun failureBeforeNotificationDispatchNeverCrashesOrConsumesCallWake() {
        val timestamp = 1710000000911L

        // Some Android/OEM services throw before NotificationManager.notify,
        // e.g. during permission lookup or notification channel setup.
        assertFalse(CallWakePolicy.safePlatformDelivery {
            throw SecurityException("simulated channel permission denial")
        })
        assertFalse(CallWakePolicy.safePlatformDelivery {
            throw IllegalStateException("simulated OEM channel binder failure")
        })

        var attempted = 0
        assertTrue(CallWakePolicy.safePlatformDelivery {
            CallWakePolicy.publishOnce(timestamp) { attempted++ }
        })
        assertEquals(1, attempted)
        assertFalse(CallWakePolicy.safePlatformDelivery {
            CallWakePolicy.publishOnce(timestamp) { attempted++ }
        })
        assertEquals(1, attempted)
    }

    @Test fun ordinaryNotificationResultsPassThroughFailClosedBoundary() {
        assertTrue(CallWakePolicy.safePlatformDelivery { true })
        assertFalse(CallWakePolicy.safePlatformDelivery { false })
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
