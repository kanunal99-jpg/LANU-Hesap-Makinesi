package com.example

import com.example.features.communication.NativeCallPhase
import com.example.features.communication.PrivateCallHostDisclosurePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the separately authenticated private-area host can show private call UI. */
class PrivateCallHostDisclosurePolicyTest {
    @Test fun calculatorAndLockedPinScreenHideOutgoingConfirmationAndAllIdleDiagnostics() {
        assertFalse(PrivateCallHostDisclosurePolicy.mayShowOutgoingConfirmation(false))
        for (phase in NativeCallPhase.values()) {
            assertFalse(PrivateCallHostDisclosurePolicy.mayShowIdleNotice(false, phase))
        }
    }

    @Test fun authenticatedPrivateAreaCanShowItsOwnOutgoingDialogAndIdleFailureNotice() {
        assertTrue(PrivateCallHostDisclosurePolicy.mayShowOutgoingConfirmation(true))
        assertTrue(PrivateCallHostDisclosurePolicy.mayShowIdleNotice(true, NativeCallPhase.IDLE))
        for (phase in NativeCallPhase.values().filter { it != NativeCallPhase.IDLE }) {
            assertFalse(PrivateCallHostDisclosurePolicy.mayShowIdleNotice(true, phase))
        }
    }
}
