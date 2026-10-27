package com.example.core.calls

import com.example.core.messaging.MessageWakeNotification
import com.example.core.messaging.MessageWakePolicy
import com.example.core.messaging.MessageWakePreferences
import com.example.core.messaging.MessageWakeTokenRefreshWorker
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
        when (message.data["type"]) {
            CallWakePolicy.TYPE -> CallWakeNotification.show(applicationContext, message.data)
            MessageWakePolicy.TYPE -> MessageWakeNotification.show(applicationContext, message.data)
            else -> Unit // Never show an unrecognized notification.
        }
    }

    override fun onNewToken(token: String) {
        // The service may be killed immediately after this callback. Persist
        // no token or identity in WorkManager; a network-constrained worker
        // always fetches the currently valid Firebase token when it runs.
        if (CallWakePreferences.isEnabled(applicationContext)) {
            runCatching { CallWakeTokenRefreshWorker.enqueue(applicationContext) }
        }
        if (MessageWakePreferences.isEnabled(applicationContext) ||
            MessageWakePreferences.hasPendingRemoteRevoke(applicationContext)) {
            runCatching { MessageWakeTokenRefreshWorker.enqueue(applicationContext) }
        }
        // On scheduling failure the next consented application launch retries.
    }
}
