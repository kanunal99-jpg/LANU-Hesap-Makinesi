package com.example.features.communication

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/** One serial foreground owner. No peer/message/network metadata is stored here. */
internal object ForegroundSyncPump {
    data class Attempt(val successful: Boolean, val retryAfterMillis: Long = 0L)
    const val MIN_INTERVAL_MILLIS = 2500L

    suspend fun run(
        requests: ReceiveChannel<Unit>,
        activeChat: () -> Boolean,
        synchronize: suspend () -> Attempt
    ) {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            // Pending triggers are covered by THIS attempt. Triggers arriving
            // while it is in flight stay queued for a subsequent bounded pass.
            requests.tryReceive()
            val result = synchronize()
            failures = if (result.successful) 0 else (failures + 1).coerceAtMost(4)
            if (result.retryAfterMillis > 0L) {
                // Manual refresh and reconnect cannot bypass server throttling.
                delay(result.retryAfterMillis.coerceIn(5000L, 120000L))
                continue
            }
            delay(MIN_INTERVAL_MILLIS)
            val interval = if (result.successful) ForegroundSyncPolicy.intervalMillis(activeChat())
                else minOf(60_000L, 5000L * (1 shl failures))
            val remaining = (interval - MIN_INTERVAL_MILLIS).coerceAtLeast(0L)
            if (remaining > 0) withTimeoutOrNull(remaining) { requests.receive() }
        }
    }
}
