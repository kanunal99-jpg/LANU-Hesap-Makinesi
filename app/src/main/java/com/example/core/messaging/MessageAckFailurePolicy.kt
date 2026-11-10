package com.example.core.messaging

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * A transient ACK request may fail after E2EE events have already been
 * authenticated and durably written to Room. The relay keeps those event
 * IDs unacknowledged for replay; a failure of that optional ACK transport
 * must not stall unrelated encrypted outgoing messages.
 *
 * Never bypass identity, TLS/protocol, 429 rate limits, interruption or
 * cancellation. Does not acknowledge, discard, or mark any message sent.
 */
internal object MessageAckFailurePolicy {
    fun mayDrainIndependentOutbox(error: IOException): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val cause = current ?: return true
            if (cause is RelayException && cause.status !in setOf(408, 500, 502, 503, 504))
                return false
            if (cause is SSLException || cause is ProtocolException ||
                cause is CancellationException || cause is InterruptedException)
                return false
            if (cause is InterruptedIOException && cause !is SocketTimeoutException)
                return false
            current = cause.cause
        }
        return current == null
    }
}
