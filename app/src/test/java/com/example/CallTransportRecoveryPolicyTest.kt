package com.example

import com.example.core.calls.CallTransportRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.PeerConnection

class CallTransportRecoveryPolicyTest {
    @Test fun lostIceTransportRequiresSignedRecoveryNotImmediateFatalCallCleanup() {
        val policy = CallTransportRecoveryPolicy
        for (state in listOf(PeerConnection.PeerConnectionState.FAILED,
            PeerConnection.PeerConnectionState.DISCONNECTED)) {
            assertTrue(policy.isRecoverableTransportLoss(state))
            assertFalse(policy.isConnected(state))
        }
    }
    @Test fun onlyActualConnectedStateCanContributeToFreshRtpEvidence() {
        val policy = CallTransportRecoveryPolicy
        for (state in PeerConnection.PeerConnectionState.values()) {
            assertEquals(state == PeerConnection.PeerConnectionState.CONNECTED,
                policy.isConnected(state))
        }
        assertFalse(policy.isRecoverableTransportLoss(PeerConnection.PeerConnectionState.CLOSED))
        assertFalse(policy.isRecoverableTransportLoss(PeerConnection.PeerConnectionState.NEW))
    }
}
