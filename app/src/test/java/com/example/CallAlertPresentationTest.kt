package com.example

import android.Manifest
import android.app.*
import android.content.*
import android.media.RingtoneManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.*
import com.example.core.notifications.PublicNotificationChannelSanitizer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class CallAlertPresentationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Before fun setup() {
        CallWakePolicy.privateAreaVisible = false
        CallWakePreferences.setEnabled(context, true)
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun clean() {
        CallWakePolicy.privateAreaVisible = false
        CallWakeNotification.cancel(context)
        CallWakePreferences.setEnabled(context, false)
    }
    @Test fun lockedPublicAlertKeepsAnswerAndDeclineWithoutCallerIdentity() {
        val now = System.currentTimeMillis()
        assertTrue(CallWakeNotification.show(context, mapOf("type" to CallWakePolicy.TYPE,"at" to now.toString()),now))
        val n = manager.activeNotifications.single { it.id == 8129 }.notification
        assertEquals(Notification.VISIBILITY_PUBLIC,n.visibility)
        assertNull("No action-less redacted version may replace the generic call",n.publicVersion)
        assertEquals("Gelen LANU araması",n.extras.getString(Notification.EXTRA_TITLE))
        val actions = n.actions.map { shadowOf(it.actionIntent).savedIntent.action }.toSet()
        assertTrue(actions.containsAll(setOf(CallWakeTapPolicy.ANSWER,CallWakeTapPolicy.DECLINE)))
        for (action in n.actions) assertEquals(IncomingCallActivity::class.java.name,
            shadowOf(action.actionIntent).savedIntent.component?.className)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
    }
    @Test fun verifiedBackgroundHandoffRearmsMissingAlertButOrdinaryDuplicateDoesNot() {
        val now = System.currentTimeMillis()
        val data=mapOf("type" to CallWakePolicy.TYPE,"at" to now.toString())
        assertTrue(CallWakeNotification.show(context,data,now))
        manager.cancel(8129)
        assertFalse(CallWakeNotification.show(context,data,now))
        assertTrue(CallWakeNotification.restoreVerified(context,now))
        val first=manager.activeNotifications.single { it.id==8129 }.postTime
        assertTrue(CallWakeNotification.restoreVerified(context,now))
        assertEquals(first,manager.activeNotifications.single { it.id==8129 }.postTime)
        assertFalse(CallWakeNotification.show(context,data,now))
    }
    @Test fun oldActionCannotBeReboundToANewerNotificationTimestamp() {
        val at=System.currentTimeMillis()
        fun answer()=manager.activeNotifications.single { it.id==8129 }.notification.actions
            .single { shadowOf(it.actionIntent).savedIntent.action==CallWakeTapPolicy.ANSWER }.actionIntent
        assertTrue(CallWakeNotification.show(context,mapOf("type" to CallWakePolicy.TYPE,"at" to at.toString()),at))
        val old=answer()
        assertTrue(CallWakeNotification.show(context,mapOf("type" to CallWakePolicy.TYPE,"at" to (at+1).toString()),at+1))
        val current=answer()
        assertNotEquals(old,current)
        assertEquals(at,shadowOf(old).savedIntent.getLongExtra(CallWakeTapPolicy.EXTRA_WAKE_AT,0L))
        assertEquals(at+1,shadowOf(current).savedIntent.getLongExtra(CallWakeTapPolicy.EXTRA_WAKE_AT,0L))
    }
    @Test fun staleRevokedOrVisibleHandoffCannotRing() {
        assertFalse(CallWakeNotification.restoreVerified(context,System.currentTimeMillis()-46_000))
        CallWakePolicy.privateAreaVisible=true
        assertFalse(CallWakeNotification.restoreVerified(context,System.currentTimeMillis()))
        CallWakePolicy.privateAreaVisible=false
        CallWakePreferences.setEnabled(context,false)
        assertFalse(CallWakeNotification.restoreVerified(context,System.currentTimeMillis()))
        assertTrue(manager.activeNotifications.none { it.id==8129 })
    }
    @Test fun migrationPreservesBlockedQuietChannelAndExplicitVibrationOff() {
        manager.createNotificationChannel(NotificationChannel("lanu_private_calls_ringing_v4","old",NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null,null);enableVibration(false)
        })
        CallWakeNotification.ensureChannel(manager)
        val n=manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW,n.importance)
        assertNull(n.sound);assertFalse(n.shouldVibrate())
    }
    @Test @Config(sdk=[34]) fun migrationRepairsOnlyAppCreatedSilentHighChannel() {
        manager.createNotificationChannel(NotificationChannel("lanu_private_calls_ringing_v4","old",NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null)
        })
        CallWakeNotification.ensureChannel(manager)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID).sound)
    }
    @Test @Config(sdk=[34]) fun migrationNeverRevivesUserSelectedSilenceInCurrentOrInheritedChannel() {
        val old=NotificationChannel("lanu_private_calls_ringing_v3","old",NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null)
            ReflectionHelpers.setField(this,"mUserLockedFields",32) // Android USER_LOCKED_SOUND
        }
        manager.createNotificationChannel(old)
        assertTrue(manager.getNotificationChannel(old.id).hasUserSetSound())
        manager.createNotificationChannel(NotificationChannel("lanu_private_calls_ringing_v4","copy",NotificationManager.IMPORTANCE_HIGH).apply {setSound(null,null)})
        CallWakeNotification.ensureChannel(manager)
        assertNull(manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID).sound)
    }
    @Test fun startupRenamePreservesPublicControlsChannelAndSoundChoices() {
        CallWakeNotification.ensureChannel(manager)
        val before=manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        PublicNotificationChannelSanitizer.sanitizeExisting(context)
        val after=manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertEquals(Notification.VISIBILITY_PUBLIC,after.lockscreenVisibility)
        assertEquals(before.sound,after.sound)
        assertEquals(before.importance,after.importance)
    }
    @Test @Config(sdk=[35,36]) fun modernCallPendingIntentRemainsExplicitImmutableAndCallOnly() {
        val intent=Intent(context,IncomingCallActivity::class.java).setAction(CallWakeTapPolicy.ACTION)
        val pending=CallWakeNotification.callActivityPending(context,0,intent)
        assertEquals(IncomingCallActivity::class.java.name,shadowOf(pending).savedIntent.component?.className)
        assertTrue(pending.isImmutable)
    }
}
