package com.example.core.calls

import com.example.core.messaging.RelayException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Short, bounded reconnect grace for an authenticated call signal. A retry
 * must reuse the exact encrypted envelope and signal UUID; relay/receiver
 * idempotency handles the case where the server accepted the first attempt
 * but its HTTP response was lost.
 *
 * Never retry account, identity, quota, malformed-request, TLS trust,
 * protocol, or thread-interruption failures. No private SDP, peer identity,
 * endpoint or error text is retained here.
 */
internal object CallSignalRetryPolicy {
    const val MAX_ATTEMPTS = 6

    private fun nonTransient(failure: IOException): Boolean {
        var current: Throwable? = failure
        // A wrapper must not hide a permanent failure. Bound traversal so
        // cyclic or unexpectedly deep exception chains fail closed.
        repeat(8) {
            val cause = current ?: return false
            if (cause is RelayException &&
                cause.status !in setOf(408, 500, 502, 503, 504)) return true
            if (cause is SSLException || cause is ProtocolException) return true
            if (cause is InterruptedIOException && cause !is SocketTimeoutException) return true
            current = cause.cause
        }
        return current != null
    }

    /** Number of completed failures, starting at 1. Null means stop. */
    fun delayAfter(failure: IOException, failedAttempts: Int): Long? {
        if (failedAttempts !in 1 until MAX_ATTEMPTS || nonTransient(failure)) return null
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
