package com.example.core.calls

import com.example.core.network.TransientTransportFailure
import java.io.IOException

/**
 * Short, bounded authenticated encrypted signaling retry. Callers reuse the
 * exact signal UUID/envelope, and foreground cancellation/deadline wins.
 * Fail closed for auth, quota, keys, TLS, permission, OEM and cancellation.
 */
internal object CallSignalRetryPolicy {
    const val MAX_ATTEMPTS = 6

    /** Number of completed failures, starting at 1. Null means stop. */
    fun delayAfter(failure: IOException, failedAttempts: Int): Long? {
        if (failedAttempts !in 1 until MAX_ATTEMPTS ||
            !TransientTransportFailure.isRetryable(failure)) return null
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
