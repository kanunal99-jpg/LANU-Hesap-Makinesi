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
    const val MAX_ATTEMPTS = 3

    /** Number of completed failures, starting at 1. Null means stop. */
    fun delayAfter(failure: IOException, failedAttempts: Int): Long? {
        if (failedAttempts !in 1 until MAX_ATTEMPTS) return null
        if (failure is RelayException &&
            failure.status !in setOf(408, 500, 502, 503, 504)) return null
        // About 1.4s of additional backoff, not an unbounded reconnect loop.
        return when (failedAttempts) {
            1 -> 400L
            2 -> 1_000L
            else -> null
        }
    }
}
