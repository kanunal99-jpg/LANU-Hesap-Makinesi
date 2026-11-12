package com.example.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.core.calls.CallWakeNotification
import com.example.core.messaging.MessageWakeNotification

/**
 * Repair previously displayed Android Settings channel metadata during an
 * ordinary app upgrade/restart. A hidden notification channel must not leak
 * its old call/message name until another push arrives.
 *
 * Do not create a new channel for users without notification consent. Android
 * retains existing importance, vibration and sound preferences on updates.
 */
object PublicNotificationChannelSanitizer {
    private const val LEGACY_CALL_ID = "lanu_private_calls_v1"
    private const val PREVIOUS_CALL_ID = "lanu_private_calls_ringing_v2"

    fun sanitizeExisting(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            for (id in listOf(
                LEGACY_CALL_ID,
                PREVIOUS_CALL_ID,
                CallWakeNotification.CHANNEL_ID,
                MessageWakeNotification.CHANNEL_ID
            )) {
                try {
                    val existing = manager.getNotificationChannel(id) ?: continue
                    manager.createNotificationChannel(
                        NotificationChannel(id, PublicNotificationText.CHANNEL_NAME, existing.importance).apply {
                            description = PublicNotificationText.CHANNEL_DESCRIPTION
                            // Opted-in call v3 intentionally shows ONLY a redacted call
                            // on the lock screen. Older calls and all message hints
                            // remain SECRET. Do not undo v3's explicit opt-in UX.
                            lockscreenVisibility = if (id == CallWakeNotification.CHANNEL_ID)
                                Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET
                        }
                    )
                } catch (_: Exception) {
                    // Notification services on OEM devices are optional and can
                    // fail. Never block ordinary calculator startup.
                }
            }
        } catch (_: Exception) {
            // The privacy-safe default is no notification work, not a crash.
        }
    }
}
