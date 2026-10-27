package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
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
        assertTrue(MessageWakePreferences.enable(context))
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
        MessageWakePreferences.enable(context)
        CallWakePolicy.privateAreaVisible=true
        assertFalse(MessageWakeNotification.show(context,hint,now))
        CallWakePolicy.privateAreaVisible=false
        assertFalse(MessageWakeNotification.show(context,hint+("private" to "leak"),now))
    }

    @Test fun separateCallAndMessageConsentNeverImplicitlyTurnEachOtherOn() {
        assertTrue(CallWakePreferences.setEnabled(context,true))
        assertFalse(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.enable(context))
        assertTrue(CallWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(CallWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.isEnabled(context))
        assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
        assertFalse(MessageWakePreferences.isEnabled(context))
    }

    @Test fun pendingRevokeNeverFetchesTokenAndNewRegistrationsUseCurrentToken() = runBlocking {
        assertTrue(MessageWakePreferences.enable(context))
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
        MessageWakePreferences.enable(context)
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
}
