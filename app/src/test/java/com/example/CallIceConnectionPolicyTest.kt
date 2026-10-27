package com.example

import com.example.core.calls.CallIceConnectionPolicy
import com.example.core.calls.CallIceConnectionPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.webrtc.PeerConnection

class CallIceConnectionPolicyTest {
    @Test fun failedAndDisconnectedIceAllowOwnerBoundedRecovery() {
        for (state in listOf(
            PeerConnection.PeerConnectionState.FAILED,
            PeerConnection.PeerConnectionState.DISCONNECTED
        )) {
            assertEquals(Action.RECOVER_WITH_DEADLINE,
                CallIceConnectionPolicy.classify(state))
            assertNotEquals(Action.TERMINATE, CallIceConnectionPolicy.classify(state))
        }
    }

    @Test fun nativeClosedConnectionIsTerminalButOnlyIfEngineWasNotAlreadyClosing() {
        assertEquals(Action.TERMINATE,
            CallIceConnectionPolicy.classify(PeerConnection.PeerConnectionState.CLOSED))
    }

    @Test fun connectingAndNewTransportCannotClaimMediaEvidence() {
        for (state in listOf(
            PeerConnection.PeerConnectionState.NEW,
            PeerConnection.PeerConnectionState.CONNECTING
        )) {
            assertEquals(Action.WAIT_FOR_CONNECTION, CallIceConnectionPolicy.classify(state))
        }
        assertEquals(Action.MEDIA_CONNECTED,
            CallIceConnectionPolicy.classify(PeerConnection.PeerConnectionState.CONNECTED))
    }
}
