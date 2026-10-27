package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import com.example.core.calls.CallWakeNotification
import com.example.core.messaging.MessageWakeNotification
import com.example.core.messaging.MessageWakePolicy
import com.example.core.messaging.MessageWakePreferences
import com.example.core.messaging.MessageWakeTapPolicy
import com.example.core.messaging.MessageWakeTokenRefreshPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageWakeNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1710000000000L

    @After fun cleanup() {
        MessageWakePreferences.disableAndQueueRemoteRevoke(context)
        MessageWakePreferences.acknowledgeRemoteRevoke(context)
        CallWakePreferences.setEnabled(context, false)
        CallWakePreferences.acknowledgeRemoteRevoke(context)
        CallWakePolicy.privateAreaVisible = false
    }

    @Test fun optionalNotificationsDefaultOffAndRemoteOptOutIsDurable() {
        assertFalse(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
        assertTrue(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
        assertTrue(MessageWakePreferences.acknowledgeRemoteRevoke(context))
        assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
        assertFalse(MessageWakePreferences.isEnabled(context))
    }

    @Test fun onlyTwoNonsensitiveFieldsAndFreshEventAreAccepted() {
        val valid=mapOf("type" to MessageWakePolicy.TYPE,"at" to now.toString())
        assertTrue(MessageWakePolicy.eligible(valid, now))
        assertFalse(MessageWakePolicy.eligible(mapOf("type" to "lanu_call_wake_v1","at" to now.toString()), now))
        assertFalse(MessageWakePolicy.eligible(valid + ("sender" to "private"), now))
        assertFalse(MessageWakePolicy.eligible(valid + ("content" to "secret"), now))
        assertFalse(MessageWakePolicy.eligible(valid - "at", now))
        assertFalse(MessageWakePolicy.eligible(mapOf("type" to MessageWakePolicy.TYPE,
            "at" to (now-121000).toString()), now))
        assertFalse(MessageWakePolicy.eligible(mapOf("type" to MessageWakePolicy.TYPE,
            "at" to (now+16000).toString()), now))
    }

    @Test fun tapAlwaysUsesCalculatorGateNotPrivateDeepLink() {
        assertTrue(MessageWakeTapPolicy.requiresFreshEntry(MessageWakeTapPolicy.ACTION))
        assertFalse(MessageWakeTapPolicy.requiresFreshEntry(null))
        assertFalse(MessageWakeTapPolicy.requiresFreshEntry("lanu_chat_or_media"))
        assertFalse(MessageWakeTapPolicy.requiresFreshEntry("lanu_call_wake_v1"))
        assertTrue(MessageWakeTapPolicy.ACTION.startsWith(context.packageName))
    }

    @Test fun messageChannelIsIndependentAudibleAndHidesLockedContent() {
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        MessageWakeNotification.ensureChannel(manager)
        val channel=manager.getNotificationChannel(MessageWakeNotification.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,channel.importance)
        assertEquals(Notification.VISIBILITY_SECRET,channel.lockscreenVisibility)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION,channel.audioAttributes.usage)
        assertFalse(MessageWakeNotification.CHANNEL_ID == "lanu_private_calls_ringing_v2")
    }

    @Test fun privateForegroundOrNoOptInSuppressesHintsWithoutFetchingChats() {
        val hint=mapOf("type" to MessageWakePolicy.TYPE,"at" to now.toString())
        assertFalse(MessageWakeNotification.show(context,hint,now))
        MessageWakePreferences.beginEnrollment(context)
        MessageWakePreferences.finishEnrollment(context)
        CallWakePolicy.privateAreaVisible=true
        assertFalse(MessageWakeNotification.show(context,hint,now))
        CallWakePolicy.privateAreaVisible=false
        assertFalse(MessageWakeNotification.show(context,hint+("private" to "leak"),now))
    }

    @Test fun separateCallAndMessageConsentNeverImplicitlyTurnEachOtherOn() {
        assertTrue(CallWakePreferences.setEnabled(context,true))
        assertFalse(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(MessageWakePreferences.isEnabled(context))
    }

    @Test fun pendingRevokeNeverFetchesTokenAndNewRegistrationsUseCurrentToken() = runBlocking {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
        val calls=mutableListOf<String?>()
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { "new-device-token" }, { calls.add(it) }))
        assertEquals(listOf<String?>("new-device-token"),calls)
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        var fetched=false
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { fetched=true; "unused" }, { calls.add(it) }))
        assertFalse(fetched)
        assertEquals(listOf<String?>("new-device-token",null),calls)
        assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun optOutRacingInflightRefreshRevokesLateRegistration() = runBlocking {
        MessageWakePreferences.beginEnrollment(context)
        MessageWakePreferences.finishEnrollment(context)
        val tokens=mutableListOf<String?>()
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { "first-fcm-token" },
            { token ->
                tokens.add(token)
                if(token != null) {
                    MessageWakePreferences.disableAndQueueRemoteRevoke(context)
                    MessageWakePreferences.acknowledgeRemoteRevoke(context)
                }
            }))
        assertEquals(listOf<String?>("first-fcm-token",null),tokens)
        assertFalse(MessageWakePreferences.isEnabled(context))
    }
    @Test fun explicitConsentRequiresCommittedRecoveryBarrier() {
        assertFalse("Cannot acknowledge a registration which never began",
            MessageWakePreferences.finishEnrollment(context))
        assertFalse(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.isEnrollmentInProgress(context))
        assertFalse("Unacknowledged server write must not enable alerts",
            MessageWakePreferences.isEnabled(context))
        assertFalse("Concurrent second enrollment is rejected",
            MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
        assertTrue(MessageWakePreferences.isEnabled(context))
        assertFalse(MessageWakePreferences.isEnrollmentInProgress(context))
        assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun processDeathAfterServerAckButBeforeLocalConsentForcesSignedRevoke() = runBlocking {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        // Simulate successful server registration followed by process death:
        // the preference commit that enables alerts was never reached.
        assertTrue(MessageWakePreferences.recoverInterruptedEnrollment(context))
        assertFalse(MessageWakePreferences.isEnabled(context))
        assertFalse(MessageWakePreferences.isEnrollmentInProgress(context))
        assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))

        var fetched = false
        val remoteCalls = mutableListOf<String?>()
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { fetched = true; "should-not-fetch" }, { remoteCalls.add(it) }))
        assertFalse(fetched)
        assertEquals(listOf<String?>(null), remoteCalls)
        assertFalse(MessageWakePreferences.hasPendingRemoteRevoke(context))
    }

    @Test fun workerCannotDeleteDeviceRegistrationWhilePrivateSettingsAreEnrolling() = runBlocking {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        var tokenFetches = 0
        var remoteCalls = 0
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { tokenFetches++; "forbidden" }, { remoteCalls++ }))
        assertEquals(0, tokenFetches)
        assertEquals(0, remoteCalls)
        assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
        assertTrue(MessageWakePreferences.isEnrollmentInProgress(context))
    }

    @Test fun abortedEnrollmentKeepsDurableRevokeIfRemoteIsOffline() = runBlocking {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(MessageWakePreferences.isEnrollmentInProgress(context))
        try {
            MessageWakeTokenRefreshPolicy.reconcile(context,
                { throw AssertionError("Cannot fetch token while revoking") },
                { throw IllegalStateException("Offline simulated") })
            throw AssertionError("Revoke should throw on network failure")
        } catch (_: IllegalStateException) {
            assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
            assertFalse(MessageWakePreferences.isEnabled(context))
        }
    }

    @Test fun optOutRemovesExistingNotificationWithoutTouchingCallCategory() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        MessageWakeNotification.ensureChannel(manager)
        val shown = NotificationCompat.Builder(context, MessageWakeNotification.CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_lanu_monochrome)
            .setContentTitle("LANU Hesap Makinesi")
            .build()
        CallWakeNotification.ensureChannel(manager)
        manager.notify(8129, NotificationCompat.Builder(context, CallWakeNotification.CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_lanu_monochrome)
            .setContentTitle("Gelen arama")
            .build())
        manager.notify(8130, shown)
        assertTrue(manager.activeNotifications.any { it.id == 8130 })
        assertTrue(manager.activeNotifications.any { it.id == 8129 })
        assertTrue(MessageWakeNotification.cancel(context))
        assertFalse(manager.activeNotifications.any { it.id == 8130 })
        assertTrue("Independent call alerts are not revoked",
            manager.activeNotifications.any { it.id == 8129 })
        manager.cancel(8129)
    }

    @Test fun freshEnrollmentCannotBeRevokedByOlderInflightTokenRefresh() = runBlocking {
        assertTrue(MessageWakePreferences.beginEnrollment(context))
        assertTrue(MessageWakePreferences.finishEnrollment(context))
        val tokens = mutableListOf<String?>()
        assertTrue(MessageWakeTokenRefreshPolicy.reconcile(context,
            { "old-token" },
            { token ->
                tokens.add(token)
                if (token != null) {
                    assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
                    assertTrue(MessageWakePreferences.acknowledgeRemoteRevoke(context))
                    assertTrue(MessageWakePreferences.beginEnrollment(context))
                }
            }))
        assertEquals(listOf<String?>("old-token"), tokens)
        assertTrue(MessageWakePreferences.isEnrollmentInProgress(context))
        assertTrue(MessageWakePreferences.hasPendingRemoteRevoke(context))
    }

}
