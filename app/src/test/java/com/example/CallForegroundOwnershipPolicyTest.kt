package com.example

import com.example.core.calls.CallForegroundOwnershipPolicy
import com.example.features.communication.NativeCallPhase
import org.junit.Assert.*
import org.junit.Test

class CallForegroundOwnershipPolicyTest {
    @Test fun staleServiceDestroyCannotEndNewOutgoingOrIncomingCall() {
        for (phase in listOf(NativeCallPhase.DIALING, NativeCallPhase.RINGING,
            NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING)) {
            assertFalse(CallForegroundOwnershipPolicy.shouldEndOnDestroy("old-call", "new-call", phase))
            assertFalse(CallForegroundOwnershipPolicy.shouldEndOnDestroy(null, "new-call", phase))
            assertTrue(CallForegroundOwnershipPolicy.shouldEndOnDestroy("new-call", "new-call", phase))
        }
    }

    @Test fun staleEndedStateMustNotCancelNewServiceBeforeNewCallAssigned() {
        assertFalse(CallForegroundOwnershipPolicy.shouldStopForTerminal(null, "old-call", NativeCallPhase.ENDED))
        assertFalse(CallForegroundOwnershipPolicy.shouldStopForTerminal("new-call", "old-call", NativeCallPhase.ENDED))
        assertTrue(CallForegroundOwnershipPolicy.shouldStopForTerminal("old-call", "old-call", NativeCallPhase.ENDED))
        assertTrue(CallForegroundOwnershipPolicy.shouldStopForTerminal("old-call", "old-call", NativeCallPhase.FAILED))
    }

    @Test fun oldNotificationHangupAndEmptyTokensCannotKillNewCall() {
        val active = NativeCallPhase.CONNECTED
        assertFalse(CallForegroundOwnershipPolicy.shouldAcceptEndAction("new-token", "old-token",
            "new-call", "new-call", active))
        assertFalse(CallForegroundOwnershipPolicy.shouldAcceptEndAction(null, "new-token",
            "new-call", "new-call", active))
        assertFalse(CallForegroundOwnershipPolicy.shouldAcceptEndAction("new-token", "new-token",
            "old-call", "new-call", active))
        assertTrue(CallForegroundOwnershipPolicy.shouldAcceptEndAction("new-token", "new-token",
            "new-call", "new-call", active))
        assertFalse(CallForegroundOwnershipPolicy.shouldAcceptEndAction("new-token", "new-token",
            "new-call", "new-call", NativeCallPhase.ENDED))
    }
}
