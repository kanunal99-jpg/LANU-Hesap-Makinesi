package com.example.core.calls

import com.example.core.messaging.RelayException
import java.io.IOException

/**
 * Short, bounded reconnect grace for an authenticated call signal. A retry
 * must reuse the exact encrypted envelope and signal UUID; relay/receiver
 * idempotency handles the case where the server accepted the first attempt
 * but its HTTP response was lost.
 *
 * Never retry account, identity, quota or malformed-request rejections.
 * No private SDP, peer identity, endpoint or error text is retained here.
 */
internal object CallSignalRetryPolicy {
    const val MAX_ATTEMPTS = 6

    /** Number of completed failures, starting at 1. Null means stop. */
    fun delayAfter(failure: IOException, failedAttempts: Int): Long? {
        if (failedAttempts !in 1 until MAX_ATTEMPTS) return null
        if (failure is RelayException &&
            failure.status !in setOf(408, 500, 502, 503, 504)) return null
        // ~10 seconds of controlled grace for short mobile/Wi-Fi handovers.
        // Native foreground call deadlines/cancellation still take precedence.
        return when (failedAttempts) {
            1 -> 350L
            2 -> 900L
            3 -> 1_700L
            4 -> 3_000L
            5 -> 4_500L
            else -> null
        }
    }
}
