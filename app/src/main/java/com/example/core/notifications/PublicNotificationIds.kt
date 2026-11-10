package com.example.core.notifications

/**
 * NotificationManager IDs share one namespace across every channel in the app.
 * A message hint must NEVER replace/cancel the foreground notification that
 * Android requires while the microphone or camera is active.
 *
 * Keep existing public message/call IDs stable for OTA upgrades; migrate only
 * the ongoing-call service away from the legacy ID collision (8130).
 * No private message or call metadata is stored here.
 */
object PublicNotificationIds {
    const val INCOMING_CALL = 8129
    const val MESSAGE_WAKE = 8130
    const val ACTIVE_CALL = 8132
    const val LIVE_LOCATION = 4137
}
