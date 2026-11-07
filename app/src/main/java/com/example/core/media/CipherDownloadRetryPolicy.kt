package com.example.core.media

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** No error text, URL or credentials are retained by this decision. */
internal object CipherDownloadRetryPolicy {
    fun shouldRetry(failure: IOException, attempt: Int, networkReadFailed: Boolean): Boolean {
        if (!networkReadFailed || attempt !in 0..1) return false
        var current: Throwable? = failure
        // Wrappers must not hide permanent trust/protocol/cancellation errors.
        // Deep or cyclic causes fail closed, without unbounded traversal.
        repeat(8) {
            val cause = current ?: return true
            if (cause is SSLException || cause is ProtocolException || cause is SecurityException ||
                cause is CancellationException || cause is InterruptedException ||
                (cause is InterruptedIOException && cause !is SocketTimeoutException)) return false
            current = cause.cause
        }
        return current == null
    }
}
