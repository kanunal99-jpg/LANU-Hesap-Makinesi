package com.example

import com.example.core.calls.CallSignalRetryPolicy
import com.example.core.messaging.RelayException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.*
import org.junit.Test

class CallSignalRetryPolicyTest {
    @Test fun networkDropAndRetryableGatewayFailuresAreBounded() {
        val cases = listOf(IOException("offline")) +
            listOf(408, 500, 502, 503, 504).map { RelayException(it, "offline") }
        for (failure in cases) {
            assertEquals(350L, CallSignalRetryPolicy.delayAfter(failure, 1))
            assertEquals(900L, CallSignalRetryPolicy.delayAfter(failure, 2))
            assertEquals(1_700L, CallSignalRetryPolicy.delayAfter(failure, 3))
            assertEquals(3_000L, CallSignalRetryPolicy.delayAfter(failure, 4))
            assertEquals(4_500L, CallSignalRetryPolicy.delayAfter(failure, 5))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, 6))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, Int.MAX_VALUE))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, -1))
        }
        assertEquals(6, CallSignalRetryPolicy.MAX_ATTEMPTS)
    }

    @Test fun authQuotaAndProtocolFailuresMustNeverRetry() {
        for (status in listOf(0, 400, 401, 403, 404, 409, 413, 422, 429)) {
            assertNull("HTTP $status should fail fast",
                CallSignalRetryPolicy.delayAfter(RelayException(status, "no"), 1))
        }
    }

    @Test fun tlsTrustProtocolAndThreadInterruptionNeverRetryEvenWhenWrapped() {
        val permanent = listOf<IOException>(
            SSLHandshakeException("untrusted certificate"),
            SSLPeerUnverifiedException("peer mismatch"),
            SSLProtocolException("invalid TLS record"),
            ProtocolException("invalid HTTP framing"),
            InterruptedIOException("interrupted operation")
        )
        for (failure in permanent) {
            for (attempt in 1 until CallSignalRetryPolicy.MAX_ATTEMPTS) {
                assertNull(failure.javaClass.simpleName,
                    CallSignalRetryPolicy.delayAfter(failure, attempt))
            }
            assertNull("IOException wrapper must not hide permanent transport failure",
                CallSignalRetryPolicy.delayAfter(IOException("wrapped", failure), 1))
        }
        assertNull("A wrapped authentication rejection is still permanent",
            CallSignalRetryPolicy.delayAfter(IOException("wrapped", RelayException(401, "auth")), 1))
    }

    @Test fun realSocketTimeoutAndWrappedTransientRelayErrorsStillRetry() {
        assertEquals(350L,
            CallSignalRetryPolicy.delayAfter(SocketTimeoutException("handover"), 1))
        assertEquals(900L,
            CallSignalRetryPolicy.delayAfter(IOException("wrapped", SocketTimeoutException("timeout")), 2))
        assertEquals(4_500L,
            CallSignalRetryPolicy.delayAfter(IOException("wrapped", RelayException(503, "busy")), 5))
    }

    @Test fun cyclicAndUnreasonablyDeepCauseChainsFailClosed() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertNull(CallSignalRetryPolicy.delayAfter(first, 1))

        var deep: IOException = IOException("offline")
        repeat(9) { deep = IOException("wrapped", deep) }
        assertNull(CallSignalRetryPolicy.delayAfter(deep, 1))
    }
}
