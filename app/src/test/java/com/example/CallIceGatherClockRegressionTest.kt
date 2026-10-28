package com.example

import com.example.core.calls.CallIceGatherPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection

class CallIceGatherClockRegressionTest {
    private val sdp = "v=0\r\na=candidate:2 1 udp 1677729535 198.51.100.12 43333 typ srflx raddr 192.168.1.12 rport 50524\r\n"
    @Test fun impossibleClockSamplesCannotSignalPrematurely() {
        val gathering = PeerConnection.IceGatheringState.GATHERING
        assertFalse(CallIceGatherPolicy.ready(sdp, gathering, -1, 650))
        assertFalse(CallIceGatherPolicy.ready(sdp, gathering, 1400, 1401))
        assertFalse(CallIceGatherPolicy.ready(sdp, PeerConnection.IceGatheringState.COMPLETE, 0, 250))
    }
    @Test fun missingNegativeAndOverflowScaleSamplesFailClosedInEveryIceState() {
        for (state in PeerConnection.IceGatheringState.values()) {
            assertFalse(CallIceGatherPolicy.ready(sdp, state, 1400, null))
            assertFalse(CallIceGatherPolicy.ready(sdp, state, 1400, -1))
            assertFalse(CallIceGatherPolicy.ready(sdp, state, Long.MIN_VALUE, 650))
            assertFalse(CallIceGatherPolicy.ready(sdp, state, 1400, Long.MAX_VALUE))
        }
    }
    @Test fun legitimateFastPathRemainsAvailable() {
        assertTrue(CallIceGatherPolicy.ready(sdp, PeerConnection.IceGatheringState.GATHERING, 1400, 650))
        assertTrue(CallIceGatherPolicy.ready(sdp, PeerConnection.IceGatheringState.COMPLETE, 300, 250))
    }
}
