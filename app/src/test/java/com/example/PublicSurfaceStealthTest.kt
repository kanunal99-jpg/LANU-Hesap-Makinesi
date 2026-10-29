package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.CallWakeNotification
import com.example.core.messaging.MessageWakeNotification
import com.example.core.notifications.PublicNotificationText
import com.example.features.update.PublicUpdateText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Visible calculator and Android settings must not disclose private features. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicSurfaceStealthTest {
    private val sensitive = listOf(
        "gizli", "özel alan", "sohbet", "mesaj", "gelen arama",
        "görüntülü arama", "PIN", "E2EE", "WebRTC", "kimlik", "kriptografi"
    )

    private fun assertGeneric(value: String) {
        assertTrue("A visible label cannot be blank", value.isNotBlank())
        for (marker in sensitive) {
            assertFalse("Public UI label exposes a private feature: $marker",
                value.contains(marker, ignoreCase = true))
        }
    }

    @Test fun allPublicOtaStatusCopyIsFeatureNeutral() {
        listOf(
            PublicUpdateText.CHECK_FAILED,
            PublicUpdateText.DOWNLOAD_FAILED,
            PublicUpdateText.INSTALL_FAILED,
            PublicUpdateText.INCOMPATIBLE,
            PublicUpdateText.SIGNER_WARNING,
            PublicUpdateText.FOOTER
        ).forEach(::assertGeneric)
        assertTrue(PublicUpdateText.SIGNER_WARNING.contains("kaldırmayın"))
        assertTrue(PublicUpdateText.FOOTER.contains("Paket imzası"))
    }

    @Test fun notificationCopyAndVisibleChannelMetadataDiscloseNoPrivateFunction() {
        listOf(
            PublicNotificationText.TITLE,
            PublicNotificationText.CHANNEL_NAME,
            PublicNotificationText.CHANNEL_DESCRIPTION
        ).forEach(::assertGeneric)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        CallWakeNotification.ensureChannel(manager)
        MessageWakeNotification.ensureChannel(manager)

        for (id in listOf(CallWakeNotification.CHANNEL_ID, MessageWakeNotification.CHANNEL_ID)) {
            val channel = manager.getNotificationChannel(id)
            assertNotNull(channel)
            assertGeneric(channel.name.toString())
            assertGeneric(channel.description)
            assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)
        }
    }

    @Test fun previouslyVisibleCallChannelNameIsNeutralizedWithoutResettingMute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("lanu_private_calls_v1", "Gelen sesli/görüntülü aramalar",
                NotificationManager.IMPORTANCE_LOW)
        )
        CallWakeNotification.ensureChannel(manager)
        val previous = manager.getNotificationChannel("lanu_private_calls_v1")
        assertEquals(NotificationManager.IMPORTANCE_LOW, previous.importance)
        assertGeneric(previous.name.toString())
        assertGeneric(previous.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID).importance)
    }
}
