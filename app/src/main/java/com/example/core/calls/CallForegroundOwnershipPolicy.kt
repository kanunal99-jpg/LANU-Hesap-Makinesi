package com.example.core.calls

import com.example.features.communication.NativeCallPhase

/** Only the service that owns THIS verified call may end/stop its media. */
internal object CallForegroundOwnershipPolicy {
    fun active(phase: NativeCallPhase): Boolean = phase in setOf(
        NativeCallPhase.DIALING, NativeCallPhase.RINGING,
        NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING
    )

    fun shouldStopForTerminal(
        ownedCallId: String?, observedCallId: String, phase: NativeCallPhase
    ): Boolean = !ownedCallId.isNullOrEmpty() && observedCallId == ownedCallId &&
        phase in setOf(NativeCallPhase.ENDED, NativeCallPhase.FAILED)

    fun shouldEndOnDestroy(
        ownedCallId: String?, observedCallId: String, phase: NativeCallPhase
    ): Boolean = !ownedCallId.isNullOrEmpty() && observedCallId == ownedCallId && active(phase)

    fun shouldAcceptEndAction(
        ownerToken: String?, pendingToken: String?,
        ownedCallId: String?, liveCallId: String, phase: NativeCallPhase
    ): Boolean = !ownerToken.isNullOrEmpty() && ownerToken == pendingToken &&
        shouldEndOnDestroy(ownedCallId, liveCallId, phase)
}
