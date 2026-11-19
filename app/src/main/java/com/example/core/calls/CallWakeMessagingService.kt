package com.example.core.calls

import com.example.core.messaging.MessageWakeNotification
import com.example.core.messaging.MessageWakePolicy
import com.example.core.messaging.MessageWakePreferences
import com.example.core.messaging.MessageWakeTokenRefreshWorker
import com.example.core.messaging.MessageWakeSyncWorker
import com.example.core.messaging.MessageWakeSyncPolicy
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM is a wake-up hint, never proof of an authenticated caller or permission
 * to open the hidden area. Bounded E2EE invitation verification is allowed
 * after an opted-in alert; media still requires explicit answer and permission.
 */
class CallWakeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        recordWakeDiagnostics(message)
        // Keep the FCM callback short; no activity launch or media capture here.
        when (message.data["type"]) {
            CallWakePolicy.TYPE -> {
                if (CallWakeNotification.show(applicationContext, message.data)) {
                    message.data["at"]?.toLongOrNull()?.let {
                        CallWakeVerifyWorker.enqueue(applicationContext, it)
                    }
                }
            }
            MessageWakePolicy.TYPE -> {
                // An opt-in FCM hint schedules only a bounded, metadata-free
                // encrypted sync. The hint never authenticates a peer, opens
                // private UI, creates an identity or carries message content.
                if (MessageWakeSyncPolicy.shouldSchedule(
                        MessageWakePreferences.isEnabled(applicationContext),
                        MessageWakePolicy.eligible(message.data, System.currentTimeMillis()))) {
                    MessageWakeSyncWorker.enqueue(applicationContext)
                }
                // No notification from the FCM timestamp alone. The Worker
                // posts only after authenticated NEW incoming Room commits.
            }
            else -> Unit // Never show an unrecognized notification.
        }
    }

    private fun recordWakeDiagnostics(message: RemoteMessage) {
        val metrics = com.example.core.diagnostics.PrivateConnectionMetrics
        if (!metrics.isEnabled()) return
        val now = System.currentTimeMillis()
        val kind = when (message.data["type"]) {
            CallWakePolicy.TYPE -> if (CallWakePreferences.isEnabled(applicationContext) && CallWakePolicy.eligible(message.data, now))
                com.example.core.diagnostics.PrivateConnectionMetrics.WakeKind.CALL else null
            MessageWakePolicy.TYPE -> if (MessageWakePreferences.isEnabled(applicationContext) && MessageWakePolicy.eligible(message.data, now))
                com.example.core.diagnostics.PrivateConnectionMetrics.WakeKind.MESSAGE else null
            else -> null
        } ?: return
        metrics.recordWake(kind, message.originalPriority == RemoteMessage.PRIORITY_HIGH,
            message.priority == RemoteMessage.PRIORITY_NORMAL, message.data["at"]?.toLongOrNull()?.let { now - it })
    }

    override fun onDeletedMessages() {
        // FCM may discard queued hints after a long offline interval. Recover
        // the durable encrypted inbox without generating a new identity.
        if (MessageWakePreferences.isEnabled(applicationContext)) {
            MessageWakeSyncWorker.enqueue(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        // The service may be killed immediately after this callback. Persist
        // no token or identity in WorkManager; a network-constrained worker
        // always fetches the currently valid Firebase token when it runs.
        if (CallWakePreferences.isEnabled(applicationContext) ||
            CallWakePreferences.hasPendingRemoteRevoke(applicationContext)) {
            runCatching { CallWakeTokenRefreshWorker.enqueue(applicationContext) }
        }
        if (MessageWakePreferences.isEnabled(applicationContext) ||
            MessageWakePreferences.hasPendingRemoteRevoke(applicationContext)) {
            runCatching { MessageWakeTokenRefreshWorker.enqueue(applicationContext) }
        }
        // On scheduling failure the next consented application launch retries.
    }
}
