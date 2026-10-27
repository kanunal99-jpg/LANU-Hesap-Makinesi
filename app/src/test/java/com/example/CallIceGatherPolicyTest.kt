package com.example

import com.example.core.calls.CallIceGatherPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection

class CallIceGatherPolicyTest {
    private val host = "v=0\\r\\na=candidate:1 1 udp 2113937151 192.168.1.12 50524 typ host generation 0\\r\\n"
    private val reflexive = host +
        "a=candidate:2 1 udp 1677729535 198.51.100.12 43333 typ srflx raddr 192.168.1.12 rport 50524\\r\\n"

    private fun ready(
        sdp: String?, state: PeerConnection.IceGatheringState,
        elapsed: Long, quiet: Long?
    ) = CallIceGatherPolicy.ready(sdp, state, elapsed, quiet)

    @Test fun stableServerReflexiveCandidateCanFastSignalBeforeSlowSecondaryStunCompletes() {
        assertTrue(ready(reflexive, PeerConnection.IceGatheringState.GATHERING, 1400, 650))
        assertTrue(ready(reflexive, PeerConnection.IceGatheringState.GATHERING, 2200, 1200))
    }

    @Test fun noCandidateOrOnlyPrivateHostNeverTriggersEarlySignal() {
        assertFalse(ready(null, PeerConnection.IceGatheringState.COMPLETE, 8000, 8000))
        assertFalse(ready("v=0", PeerConnection.IceGatheringState.COMPLETE, 8000, 8000))
        assertFalse(ready(host, PeerConnection.IceGatheringState.GATHERING, 7000, 7000))
        assertFalse(ready(host, PeerConnection.IceGatheringState.GATHERING, 800, 800))
    }

    @Test fun noStalePriorIceEpochAndNoTooEagerFastPath() {
        assertFalse(ready(reflexive, PeerConnection.IceGatheringState.GATHERING, 3000, null))
        assertFalse(ready(reflexive, PeerConnection.IceGatheringState.GATHERING, 3000, -1))
        assertFalse(ready(reflexive, PeerConnection.IceGatheringState.GATHERING, 1399, 800))
        assertFalse(ready(reflexive, PeerConnection.IceGatheringState.GATHERING, 1400, 649))
    }

    @Test fun completedIceCanSignalWithoutSrflxButOnlyAfterCurrentCandidateQuietTime() {
        assertTrue(ready(host, PeerConnection.IceGatheringState.COMPLETE, 400, 250))
        assertFalse(ready(host, PeerConnection.IceGatheringState.COMPLETE, 800, 249))
        assertFalse(ready(host, PeerConnection.IceGatheringState.COMPLETE, 800, null))
    }

    @Test fun literalLookalikesOutsideCandidateLinesNeverCountAsPublicRoute() {
        val malicious = "v=0\\r\\na=x-fake: typ srflx\\r\\n" + host
        assertFalse(ready(malicious, PeerConnection.IceGatheringState.GATHERING, 3000, 1000))
    }
}
