package com.example.features.communication

/** Foreground chat only; hidden/closed screens still stop their owner jobs. */
internal object ForegroundSyncPolicy {
    // With a 30s registration lease, 24 syncs + up to 24 ACKs + 60 sends
    // + 2 registrations/minute stay below the 120/min ordinary relay budget.
    fun intervalMillis(activeConversation: Boolean): Long =
        if (activeConversation) 2500L else 5000L
}
