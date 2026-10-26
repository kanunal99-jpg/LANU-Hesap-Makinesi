package com.example.core.calls

import com.example.LanuApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM is a wake-up hint, never proof of an authenticated caller or permission
 * to open the hidden area. Media and the E2EE INVITE are handled only after
 * the owner enters the existing private area.
 */
class CallWakeMessagingService : FirebaseMessagingService() {
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        // Reject unrelated pushes, arbitrary payloads and disabled consent.
        // Never enqueue a chat sync, activity launch or media capture here.
        CallWakeNotification.show(applicationContext, message.data)
    }

    override fun onNewToken(token: String) {
        if (!CallWakePreferences.isEnabled(applicationContext)) return
        refreshScope.launch {
            try {
                val repo = (applicationContext as LanuApplication).container.communicationRepository
                repo.registerCallWakeToken(token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Token refresh is retried on next authenticated opt-in.
                // No tokens, peer identifiers or private error strings in logs.
            }
        }
    }
}
