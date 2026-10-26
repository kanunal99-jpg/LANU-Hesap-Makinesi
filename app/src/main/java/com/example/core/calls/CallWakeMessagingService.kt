package com.example.core.calls

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM is a wake-up hint, never proof of an authenticated caller or permission
 * to open the hidden area. Media and the E2EE INVITE are handled only after
 * the owner enters the existing private area.
 */
class CallWakeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Never enqueue a chat sync, activity launch or media capture here.
        CallWakeNotification.show(applicationContext, message.data)
    }

    override fun onNewToken(token: String) {
        // The service may be killed immediately after this callback. Persist
        // no token or identity in WorkManager; a network-constrained worker
        // always fetches the currently valid Firebase token when it runs.
        if (!CallWakePreferences.isEnabled(applicationContext)) return
        runCatching { CallWakeTokenRefreshWorker.enqueue(applicationContext) }
        // On scheduling failure the next consented application launch retries.
    }
}
