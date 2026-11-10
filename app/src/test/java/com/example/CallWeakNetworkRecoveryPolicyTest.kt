package com.example

import com.example.core.calls.CallWeakNetworkRecoveryPolicy
import com.example.core.calls.CallMediaEvidence
import org.junit.Assert.*
import org.junit.Test
import com.example.core.messaging.RelayException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class CallWeakNetworkRecoveryPolicyTest {
    @Test fun ordinaryRadioHandoverKeepsExistingBoundedGrace() {
        assertEquals(2250L, CallWeakNetworkRecoveryPolicy.graceMs(false))
        assertFalse(CallWeakNetworkRecoveryPolicy.mayFastTrack(
            outgoing=true, reconnecting=true, iceFailed=false,
            restartAlreadyStarted=false, fastTrackAlreadyUsed=false))
    }

    @Test fun nativeFailedExpeditesRecoveryWithoutExtraRetryBudget() {
        assertEquals(300L, CallWeakNetworkRecoveryPolicy.graceMs(true))
        assertTrue(CallWeakNetworkRecoveryPolicy.mayFastTrack(
            outgoing=true, reconnecting=true, iceFailed=true,
            restartAlreadyStarted=false, fastTrackAlreadyUsed=false))
    }

    @Test fun noFastTrackForReceiverClosedCallOrAlreadyStartedRestart() {
        for (v in listOf(
            listOf(false,true,true,false,false),
            listOf(true,false,true,false,false),
            listOf(true,true,false,false,false),
            listOf(true,true,true,true,false),
            listOf(true,true,true,false,true)
        )) assertFalse(CallWeakNetworkRecoveryPolicy.mayFastTrack(
            outgoing=v[0], reconnecting=v[1], iceFailed=v[2],
            restartAlreadyStarted=v[3], fastTrackAlreadyUsed=v[4]))
    }

    @Test fun onlyCurrentOutgoingRecoveryMayHoldTransientSignalingErrors() {
        val errors = listOf<IOException>(
            IOException("offline"), SocketTimeoutException("radio handover"),
            RelayException(408, "request timeout"), RelayException(503, "gateway unavailable")
        )
        errors.forEach { error ->
            assertTrue(CallWeakNetworkRecoveryPolicy.canHoldAfterRestartSignalError(
                true, true, true, true, error))
            for (conditions in listOf(
                listOf(false, true, true, true),
                listOf(true, false, true, true),
                listOf(true, true, false, true),
                listOf(true, true, true, false)
            )) assertFalse(CallWeakNetworkRecoveryPolicy.canHoldAfterRestartSignalError(
                conditions[0], conditions[1], conditions[2], conditions[3], error))
        }
        assertEquals(1_750L, CallWeakNetworkRecoveryPolicy.FAILED_RESTART_SIGNAL_RETRY_MS)
    }

    @Test fun authTlsQuotaProtocolAndCancelledRecoveryFailClosed() {
        val permanent = listOf<IOException>(
            RelayException(401, "identity"), RelayException(403, "forbidden"),
            RelayException(409, "stale peer"), RelayException(429, "quota", 60_000L),
            SSLHandshakeException("untrusted certificate"),
            ProtocolException("invalid framing"),
            IOException("wrapped TLS", SSLHandshakeException("bad certificate")),
            IOException("wrapped cancellation", kotlinx.coroutines.CancellationException("call ended"))
        )
        permanent.forEach { error ->
            assertFalse(error.javaClass.simpleName,
                CallWeakNetworkRecoveryPolicy.canHoldAfterRestartSignalError(
                    true, true, true, true, error))
        }
    }

    @Test fun rtpSilenceAloneIsNeverNativeIceFailureOrLiveCallProof() {
        val evidence=CallMediaEvidence(transportConnected=true)
        assertFalse(evidence.iceFailed)
        assertFalse(evidence.connected)
        val failed=CallMediaEvidence(transportConnected=false, iceFailed=true)
        assertFalse(failed.connected)
        assertTrue(failed.iceFailed)
    }
}
