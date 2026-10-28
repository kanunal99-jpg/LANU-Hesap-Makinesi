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
            assertEquals(400L, CallSignalRetryPolicy.delayAfter(failure, 1))
            assertEquals(1_000L, CallSignalRetryPolicy.delayAfter(failure, 2))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, 3))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, Int.MAX_VALUE))
            assertNull(CallSignalRetryPolicy.delayAfter(failure, -1))
        }
        assertEquals(3, CallSignalRetryPolicy.MAX_ATTEMPTS)
    }

    @Test fun authQuotaAndProtocolFailuresMustNeverRetry() {
        for (status in listOf(0, 400, 401, 403, 404, 409, 413, 422, 429)) {
            assertNull("HTTP $status should fail fast",
                CallSignalRetryPolicy.delayAfter(RelayException(status, "no"), 1))
        }
    }
}
