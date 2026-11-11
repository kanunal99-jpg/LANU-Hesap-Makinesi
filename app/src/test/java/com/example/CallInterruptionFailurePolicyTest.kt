package com.example

import com.example.core.calls.CallInterruptionFailurePolicy
import org.junit.Assert.*
import org.junit.Test

class CallInterruptionFailurePolicyTest {
    @Test fun deliberateExternalAudioOwnershipCannotBeMistakenForFatalOemCaptureFailure() {
        assertFalse(CallInterruptionFailurePolicy.shouldReportAudioError(closing = false, focusHeld = true))
        assertFalse(CallInterruptionFailurePolicy.shouldQuarantineOemCapture(
            closing = false, focusHeld = true, usingHardware = true, hasPermission = true))
        assertFalse(CallInterruptionFailurePolicy.shouldReportAudioError(closing = true, focusHeld = false))
        assertFalse(CallInterruptionFailurePolicy.shouldQuarantineOemCapture(
            closing = true, focusHeld = false, usingHardware = true, hasPermission = true))
    }

    @Test fun genuineLiveUninterruptedDeviceErrorStillFollowsSafeFailurePath() {
        assertTrue(CallInterruptionFailurePolicy.shouldReportAudioError(closing = false, focusHeld = false))
        assertTrue(CallInterruptionFailurePolicy.shouldQuarantineOemCapture(
            closing = false, focusHeld = false, usingHardware = true, hasPermission = true))
        assertFalse(CallInterruptionFailurePolicy.shouldQuarantineOemCapture(
            closing = false, focusHeld = false, usingHardware = false, hasPermission = true))
        assertFalse(CallInterruptionFailurePolicy.shouldQuarantineOemCapture(
            closing = false, focusHeld = false, usingHardware = true, hasPermission = false))
    }
}
