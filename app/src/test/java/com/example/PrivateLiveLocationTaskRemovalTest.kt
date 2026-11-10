package com.example

import android.app.Application
import android.content.ComponentName
import android.content.pm.ServiceInfo
import com.example.core.location.PrivateLiveLocationService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for Android Recents swipe: it removes the activity task, not an
 * already consented, notification-visible foreground location session.
 * This is not a physical OEM battery-management or force-stop test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivateLiveLocationTaskRemovalTest {
    @Test fun recentsSwipeNeverRevokesTimedSharingConsent() {
        val controller = Robolectric.buildService(PrivateLiveLocationService::class.java).create()
        try {
            val before = PrivateLiveLocationService.state.value
            controller.get().onTaskRemoved(null)
            // Previously onTaskRemoved invoked stopSharing() and immediately
            // overwrote the state with "Uygulama kapatıldı".
            assertEquals(before, PrivateLiveLocationService.state.value)
        } finally {
            controller.destroy()
        }
    }

    @Test fun manifestKeepsForegroundServiceIndependentOfActivityTask() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val service = app.packageManager.getServiceInfo(
            ComponentName(app, PrivateLiveLocationService::class.java), 0
        )
        assertFalse(service.exported)
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertTrue(service.foregroundServiceType and
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0)
    }
}
