package com.example

import com.example.core.calls.CallPollBackoffPolicy
import com.example.core.calls.CallSignalRetryPolicy
import com.example.core.calls.CallWeakNetworkRecoveryPolicy
import com.example.core.messaging.MessageAckFailurePolicy
import com.example.core.messaging.RelayException
import com.example.core.network.TransientTransportFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import org.junit.Assert.*
import org.junit.Test

class TransientTransportFailureTest {
    @Test fun wrappedPermissionCryptoAndOemFailuresNeverRetryInAnyConsumer() {
        val causes: List<Throwable> = listOf(
            SecurityException("revoked permission"),
            GeneralSecurityException("keystore failure"),
            IllegalArgumentException("malformed envelope"),
            IllegalStateException("closed session"),
            LinkageError("missing OEM module"),
            OutOfMemoryError("synthetic test marker")
        )
        for (cause in causes) {
            val failure = IOException("transport", IOException("wrapped", cause))
            assertFalse(cause.javaClass.simpleName, TransientTransportFailure.isRetryable(failure))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, 1))
            assertNull(CallPollBackoffPolicy.transientDelayMs(failure, 1))
            assertFalse(MessageAckFailurePolicy.mayDrainIndependentOutbox(failure))
            assertFalse(CallWeakNetworkRecoveryPolicy.canHoldAfterRestartSignalError(
                outgoing = true, sameSession = true, sameRecoveryGeneration = true,
                reconnecting = true, failure = failure
            ))
        }
    }

    @Test fun transientHandoverAndGatewayErrorsStillUseBoundedExistingRetry() {
        for (failure in listOf<IOException>(
            IOException("offline"),
            IOException("wrapped", SocketTimeoutException("handover")),
            IOException("wrapped", RelayException(503, "transient"))
        )) {
            assertTrue(TransientTransportFailure.isRetryable(failure))
            assertEquals(350L, CallSignalRetryPolicy.delayAfter(failure, 1))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, 6))
            assertTrue(MessageAckFailurePolicy.mayDrainIndependentOutbox(failure))
        }
    }

    @Test fun wrappedAuthAndQuotaStillFailClosed() {
        for (status in listOf(400, 401, 403, 409, 422, 429)) {
            val failure = IOException("wrapped", RelayException(status, "deny"))
            assertFalse(TransientTransportFailure.isRetryable(failure))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, 1))
            assertFalse(MessageAckFailurePolicy.mayDrainIndependentOutbox(failure))
        }
    }

    @Test fun cyclesAndDeepCauseChainsCannotBypassSecurityPolicy() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertFalse(TransientTransportFailure.isRetryable(first))
        var deep: IOException = IOException("network")
        repeat(9) { deep = IOException("wrapped", deep) }
        assertFalse(TransientTransportFailure.isRetryable(deep))
    }
}
