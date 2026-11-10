package com.example

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.example.core.location.PrivateLiveLocationService
import com.example.core.location.PrivateLiveLocationStopPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Non-secret, user-visible notification action remains available on Android
 * 9 and 14. A stale notification must never revoke a different share.
 * This does not assert OEM collapsed-notification button placement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class PrivateLiveLocationNotificationStopTest {
    @Test fun activeNotificationAlwaysHasOneExplicitEarlyStopAction() {
        val controller = Robolectric.buildService(PrivateLiveLocationService::class.java).create()
        try {
            val notice = controller.get().notification("synthetic-not-secret-stop-token")
            assertEquals(Notification.VISIBILITY_SECRET, notice.visibility)
            assertEquals(1, notice.actions?.size ?: 0)
            assertEquals("Durdur", notice.actions[0].title.toString())
            assertNotNull(notice.actions[0].actionIntent)
            val body = notice.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertTrue(body.contains("Durdurmak"))
            assertFalse(body.contains("konum", ignoreCase = true))
            assertFalse(body.contains("sohbet", ignoreCase = true))
            assertFalse(body.contains("alıcı", ignoreCase = true))
        } finally {
            controller.destroy()
        }
    }

    @Test fun stoppingNotificationHasFeedbackButNoRestartOrDuplicateStopButton() {
        val controller = Robolectric.buildService(PrivateLiveLocationService::class.java).create()
        try {
            val notice = controller.get().notification(
                "synthetic-not-secret-stop-token", stopping = true)
            assertEquals(0, notice.actions?.size ?: 0)
            assertEquals("İşlem sonlandırılıyor",
                notice.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(Notification.VISIBILITY_SECRET, notice.visibility)
        } finally {
            controller.destroy()
        }
    }

    @Test fun oldNotificationCannotStopANewSessionAndMalformedTokenFailsClosed() {
        val old = "prior-opaque-uuid"
        val current = "current-opaque-uuid"
        assertTrue(PrivateLiveLocationStopPolicy.matchesCurrent(current, current))
        assertFalse(PrivateLiveLocationStopPolicy.matchesCurrent(current, old))
        assertFalse(PrivateLiveLocationStopPolicy.matchesCurrent(current, null))
        assertFalse(PrivateLiveLocationStopPolicy.matchesCurrent(current, ""))
        assertFalse(PrivateLiveLocationStopPolicy.matchesCurrent(null, current))
        assertFalse(PrivateLiveLocationStopPolicy.matchesCurrent("", ""))
        assertFalse(PrivateLiveLocationStopPolicy.matchesCurrent(" ", " "))
        assertEquals(6_000L, PrivateLiveLocationStopPolicy.MAX_STOP_DELIVERY_MS)
    }

    @Test fun notificationStopPendingIntentCarriesOnlyItsOwnOpaqueSessionToken() {
        val controller = Robolectric.buildService(PrivateLiveLocationService::class.java).create()
        try {
            val earlier = controller.get().notification("session-A-opaque-stop-token")
            val later = controller.get().notification("session-B-opaque-stop-token")
            val actionA = Shadows.shadowOf(earlier.actions.single().actionIntent)
            val actionB = Shadows.shadowOf(later.actions.single().actionIntent)
            val saved = actionA.savedIntent
            assertEquals(PrivateLiveLocationService.ACTION_STOP_NOTIFICATION, saved.action)
            assertEquals("session-A-opaque-stop-token",
                saved.getStringExtra(PrivateLiveLocationService.EXTRA_STOP_TOKEN))
            assertEquals(PrivateLiveLocationService::class.java.name,
                saved.component?.className)
            assertNull(saved.getStringExtra("conversation"))
            assertNull(saved.getStringExtra("precise"))
            assertTrue(actionA.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertNotEquals(actionA.requestCode, actionB.requestCode)
            assertEquals("session-B-opaque-stop-token",
                actionB.savedIntent.getStringExtra(PrivateLiveLocationService.EXTRA_STOP_TOKEN))
            assertFalse("An earlier PendingIntent must not match the later session",
                PrivateLiveLocationStopPolicy.matchesCurrent(
                    actionB.savedIntent.getStringExtra(PrivateLiveLocationService.EXTRA_STOP_TOKEN),
                    saved.getStringExtra(PrivateLiveLocationService.EXTRA_STOP_TOKEN)))
        } finally {
            controller.destroy()
        }
    }

    @Test fun manifestServiceRemainsNonExportedSoOnlyOwnNotificationCanStopIt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getServiceInfo(
            android.content.ComponentName(app, PrivateLiveLocationService::class.java), 0)
        assertFalse(info.exported)
    }
}
