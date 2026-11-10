package com.example.core.calls

import com.example.core.messaging.RelayException
import java.io.IOException

/**
 * Session-independent, bounded signaling-poll pacing. Polling is NOT media:
 * an established WebRTC call keeps its native RTP path while HTTP is offline.
 *
 * Reuse the encrypted-signal transport classifier: no rapid automatic retry
 * of TLS trust, malformed protocol, identity/auth or quota failures.
 * Network recovery may clear only transient transport cooldown, never 429
 * or the identity/protocol backoff.
 */
internal object CallPollBackoffPolicy {
    const val MAX_TRANSIENT_FAILURES = 5
    const val PERMANENT_ERROR_COOLDOWN_MS = 30_000L

    fun transientDelayMs(error: Exception, consecutiveFailures: Int): Long? {
        if (error !is IOException ||
            CallSignalRetryPolicy.delayAfter(error, 1) == null) return null
        return when (consecutiveFailures.coerceIn(1, MAX_TRANSIENT_FAILURES)) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            4 -> 8_000L
            else -> 12_000L
        }
    }

    fun isPermanentCallControlError(error: Exception): Boolean =
        error is RelayException &&
            error.status in setOf(400, 401, 403, 404, 409, 413, 422)
}
