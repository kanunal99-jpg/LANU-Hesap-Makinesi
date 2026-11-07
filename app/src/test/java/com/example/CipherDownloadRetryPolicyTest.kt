package com.example

import com.example.core.media.CipherDownloadRetryPolicy
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class CipherDownloadRetryPolicyTest {
    @Test fun wrappedPermanentFailuresNeverBecomeTransientNetworkErrors() {
        val permanent = listOf(
            SSLHandshakeException("certificate rejected"), ProtocolException("invalid framing"),
            InterruptedIOException("stopped"), InterruptedException("interrupted"),
            CancellationException("cancelled"), SecurityException("untrusted")
        )
        for (cause in permanent) {
            val wrapped = IOException("transport", IOException("adapter", cause))
            assertFalse(cause.javaClass.simpleName,
                CipherDownloadRetryPolicy.shouldRetry(wrapped, 0, true))
        }
    }

    @Test fun networkTimeoutsHaveOnlyTwoRetriesWhileDiskErrorsHaveNone() {
        val failure = IOException("adapter", SocketTimeoutException("timeout"))
        assertTrue(CipherDownloadRetryPolicy.shouldRetry(failure, 0, true))
        assertTrue(CipherDownloadRetryPolicy.shouldRetry(failure, 1, true))
        assertFalse(CipherDownloadRetryPolicy.shouldRetry(failure, 2, true))
        assertFalse(CipherDownloadRetryPolicy.shouldRetry(failure, -1, true))
        assertFalse(CipherDownloadRetryPolicy.shouldRetry(failure, 0, false))
    }

    @Test fun cyclicAndExcessivelyDeepCauseChainsFailClosed() {
        val a = IOException("a"); val b = IOException("b")
        a.initCause(b); b.initCause(a)
        assertFalse(CipherDownloadRetryPolicy.shouldRetry(a, 0, true))
        var deep = IOException("root")
        repeat(8) { deep = IOException("wrapper", deep) }
        assertFalse(CipherDownloadRetryPolicy.shouldRetry(deep, 0, true))
    }
}
