package com.example.core.network

import com.example.core.messaging.RelayException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Shared fail-closed classification for authenticated call signaling and E2EE
 * ACK transport. An IOException wrapper cannot hide security, cancellation,
 * invalid request or OEM failure. No peer data, SDP or ciphertext inspected.
 */
internal object TransientTransportFailure {
    private val RETRYABLE_HTTP = setOf(408, 500, 502, 503, 504)
    private const val MAX_CAUSES = 8

    fun isRetryable(error: IOException): Boolean {
        var current: Throwable? = error
        repeat(MAX_CAUSES) {
            val cause = current ?: return true
            when (cause) {
                is RelayException -> if (cause.status !in RETRYABLE_HTTP) return false
                is SSLException, is ProtocolException,
                is CancellationException, is InterruptedException,
                is SecurityException, is GeneralSecurityException,
                is IllegalArgumentException, is IllegalStateException,
                is LinkageError, is OutOfMemoryError,
                // Unknown RuntimeException/Error from an OEM or API contract
                // must never be retried as if it were an offline transport.
                is RuntimeException, is Error -> return false
                is InterruptedIOException -> if (cause !is SocketTimeoutException) return false
            }
            current = cause.cause
        }
        // Deep or cyclic cause chains are untrusted and fail closed.
        return current == null
    }
}
