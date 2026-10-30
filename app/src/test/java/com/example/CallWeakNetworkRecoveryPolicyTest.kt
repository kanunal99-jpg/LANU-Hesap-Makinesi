package com.example

import com.example.core.calls.CallWeakNetworkRecoveryPolicy
import com.example.core.calls.CallMediaEvidence
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun rtpSilenceAloneIsNeverNativeIceFailureOrLiveCallProof() {
        val evidence=CallMediaEvidence(transportConnected=true)
        assertFalse(evidence.iceFailed)
        assertFalse(evidence.connected)
        val failed=CallMediaEvidence(transportConnected=false, iceFailed=true)
        assertFalse(failed.connected)
        assertTrue(failed.iceFailed)
    }
}
