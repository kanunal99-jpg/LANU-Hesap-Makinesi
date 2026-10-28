package com.example

import com.example.core.calls.CallSignalRetryPolicy
import com.example.core.messaging.RelayException
import java.io.IOException
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
}
