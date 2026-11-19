package com.example

import android.Manifest
import android.app.*
import android.content.*
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,34])
class CallLockScreenSettingsTest {
    private val context=ApplicationProvider.getApplicationContext<Application>()
    private val manager get()=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Before fun before() {
        CallWakePreferences.setEnabled(context,true)
        if(Build.VERSION.SDK_INT>=33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun after() { CallWakePreferences.setEnabled(context,false) }
    @Test fun opensExactCallCategoryWithLockScreenHintWithoutChangingMute() {
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_LOW).apply { setSound(null,null) })
        var opened:Intent?=null
        assertEquals(CallLockScreenSettings.Destination.CHANNEL,CallLockScreenSettings.open(context){opened=it})
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,opened!!.action)
        assertEquals(CallWakeNotification.CHANNEL_ID,opened!!.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(context.packageName,opened!!.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(opened!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0)
        if(Build.VERSION.SDK_INT>=31) assertEquals(arrayListOf(NotificationChannel.EDIT_LOCKSCREEN),opened!!.getStringArrayListExtra(Settings.EXTRA_CHANNEL_FILTER_LIST))
        val channel=manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
        assertNull(channel.sound);assertEquals(NotificationManager.IMPORTANCE_LOW,channel.importance)
    }
    @Test fun optOutCanOpenRepairButCannotEnrollOrCreateCallChannel() {
        CallWakePreferences.setEnabled(context,false)
        val channels=manager.notificationChannels.map{it.id}.toSet()
        assertEquals(CallLockScreenSettings.State.CALLS_OFF,CallLockScreenSettings.inspect(context))
        val opened=mutableListOf<Intent>()
        assertEquals(CallLockScreenSettings.Destination.APP,CallLockScreenSettings.open(context){opened+=it})
        assertFalse(CallWakePreferences.isEnabled(context))
        assertEquals(channels,manager.notificationChannels.map{it.id}.toSet())
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,opened.single().action)
    }
    @Test fun hiddenChannelIsReportedAsHiddenWithoutUnhidingIt() {
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility=Notification.VISIBILITY_SECRET })
        assertEquals(CallLockScreenSettings.State.HIDDEN,CallLockScreenSettings.inspect(context))
        assertEquals(Notification.VISIBILITY_SECRET,manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID).lockscreenVisibility)
    }
    @Test fun privateCategoryIsNotClaimedToBeVisible() {
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
        assertEquals(CallLockScreenSettings.State.REDACTED,CallLockScreenSettings.inspect(context))
    }
    @Test fun publicCategoryReportsOnlyConditionalGenericVisibility() {
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility=Notification.VISIBILITY_PUBLIC })
        assertEquals(CallLockScreenSettings.State.GENERIC_ALLOWED,CallLockScreenSettings.inspect(context))
        assertTrue(CallLockScreenSettings.State.GENERIC_ALLOWED.description.contains("ayrıca"))
    }
    @Test fun blockedChannelAndMissingChannelNeverProduceReadyClaim() {
        assertEquals(CallLockScreenSettings.State.SYSTEM_CONTROLLED,CallLockScreenSettings.inspect(context))
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_NONE))
        assertEquals(CallLockScreenSettings.State.BLOCKED,CallLockScreenSettings.inspect(context))
    }
    @Test fun vendorChannelPageFailureFallsBackToAppThenDetails() {
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_HIGH))
        val attempts=mutableListOf<Intent>()
        assertEquals(CallLockScreenSettings.Destination.DETAILS,CallLockScreenSettings.open(context){
            attempts+=it
            if(it.action!=Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException()
        })
        assertEquals(listOf(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,Settings.ACTION_APP_NOTIFICATION_SETTINGS,Settings.ACTION_APPLICATION_DETAILS_SETTINGS),attempts.map{it.action})
        assertEquals("package:${context.packageName}",attempts.last().data.toString())
        assertEquals(CallLockScreenSettings.Destination.UNAVAILABLE,CallLockScreenSettings.open(context){throw SecurityException()})
    }
    @Test fun samsungAppContentRouteDoesNotAccidentallyOpenMessageOrCallCategory() {
        manager.createNotificationChannel(NotificationChannel(CallWakeNotification.CHANNEL_ID,"fixture",NotificationManager.IMPORTANCE_HIGH))
        var opened:Intent?=null
        assertEquals(CallLockScreenSettings.Destination.APP,CallLockScreenSettings.openApp(context){opened=it})
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,opened!!.action)
        assertEquals(context.packageName,opened!!.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(opened!!.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
    @Test @Config(sdk=[23]) fun olderAndroidUsesAppDetailsWithoutChannelApi() {
        var opened:Intent?=null
        assertEquals(CallLockScreenSettings.State.SYSTEM_CONTROLLED,CallLockScreenSettings.inspect(context))
        assertEquals(CallLockScreenSettings.Destination.DETAILS,CallLockScreenSettings.open(context){opened=it})
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,opened!!.action)
    }
}
