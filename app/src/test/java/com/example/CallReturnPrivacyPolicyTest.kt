package com.example

import com.example.features.communication.CallReturnPrivacyPolicy
import com.example.features.communication.NativeCallPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReturnPrivacyPolicyTest {
    @Test fun hangupReLocksPrivateAreaForNormalAndFailedCalls() {
        assertTrue(CallReturnPrivacyPolicy.requiresLockedCalculator(NativeCallPhase.ENDED, "verified-call"))
        assertTrue(CallReturnPrivacyPolicy.requiresLockedCalculator(NativeCallPhase.FAILED, "verified-call"))
    }

    @Test fun answeringAndActiveCallDoNotUnlockPrivateAreaOrForceNavigation() {
        for (phase in listOf(
            NativeCallPhase.IDLE, NativeCallPhase.RINGING, NativeCallPhase.DIALING,
            NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED,
            NativeCallPhase.RECONNECTING
        )) assertFalse(CallReturnPrivacyPolicy.requiresLockedCalculator(phase, "verified-call"))
    }

    @Test fun staleOrEmptyPushCannotForcePrivateNavigation() {
        for (phase in NativeCallPhase.values()) {
            assertFalse(CallReturnPrivacyPolicy.requiresLockedCalculator(phase, ""))
        }
    }
}
