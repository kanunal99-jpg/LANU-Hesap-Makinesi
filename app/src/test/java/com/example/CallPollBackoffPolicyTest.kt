package com.example

import com.example.core.calls.CallPollBackoffPolicy as Policy
import com.example.core.messaging.RelayException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/** Pure call-control policy tests. No personal stats, network or credentials. */
class CallPollBackoffPolicyTest {
    @Test fun transientNetworkAndGatewayFailuresUseFiniteProgressiveBackoff() {
        val errors = listOf<Exception>(IOException("offline"),
            SocketTimeoutException("handover"), RelayException(503, "temporary"),
            RelayException(408, "slow"), RelayException(500, "temporary"))
        for (e in errors) {
            assertEquals(1_000L, Policy.transientDelayMs(e, 1))
            assertEquals(2_000L, Policy.transientDelayMs(e, 2))
            assertEquals(4_000L, Policy.transientDelayMs(e, 3))
            assertEquals(8_000L, Policy.transientDelayMs(e, 4))
            assertEquals(12_000L, Policy.transientDelayMs(e, 5))
            assertEquals(12_000L, Policy.transientDelayMs(e, Int.MAX_VALUE))
        }
        assertEquals(5, Policy.MAX_TRANSIENT_FAILURES)
    }

    @Test fun rateLimitsAuthFailuresAndTlsTrustCannotPretendToBeTransient() {
        val nonTransient = listOf<Exception>(
            RelayException(429, "quota", 90_000),
            RelayException(401, "identity"), RelayException(403, "blocked"),
            RelayException(409, "conflict"), RelayException(422, "protocol"),
            SSLHandshakeException("untrusted certificate"),
            ProtocolException("malformed HTTP"),
            IOException("wrapped", SSLHandshakeException("trust"))
        )
        nonTransient.forEach { error ->
            assertNull(error.javaClass.simpleName, Policy.transientDelayMs(error, 1))
        }
        assertTrue(Policy.isPermanentCallControlError(RelayException(401, "invalid")))
        assertTrue(Policy.isPermanentCallControlError(RelayException(422, "invalid")))
        assertFalse(Policy.isPermanentCallControlError(RelayException(429, "quota")))
        assertFalse(Policy.isPermanentCallControlError(RelayException(503, "temporary")))
        assertEquals(30_000L, Policy.PERMANENT_ERROR_COOLDOWN_MS)
    }
}
