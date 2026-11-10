package com.example

import com.example.core.calls.CallAudioRoutePolicy.Route
import com.example.core.calls.CallBluetoothRecoveryPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

/** Deterministic routing decision tests; actual headset confirmation is device-only. */
class CallBluetoothRecoveryPolicyTest {
    private fun allowed(
        running: Boolean = true,
        sameSession: Boolean = true,
        permission: Boolean = true,
        user: Route? = null,
        automatic: Route? = Route.BLUETOOTH_HEADSET,
        confirmed: Boolean = false,
        attempts: Int = 0
    ): Boolean = Policy.mayRetry(
        running, sameSession, permission, user, automatic, confirmed, attempts
    )

    @Test fun initialOemRejectionGetsTwoDelayedRecoveriesOnly() {
        assertTrue(allowed(attempts = 0))
        assertTrue(allowed(attempts = 1))
        assertFalse(allowed(attempts = 2))
        assertFalse(allowed(attempts = 3))
        assertFalse(allowed(attempts = -1))
        assertEquals(2, Policy.MAX_RETRIES)
        assertEquals(1_500L, Policy.retryDelayMs(0))
        assertEquals(3_500L, Policy.retryDelayMs(1))
    }

    @Test fun noPermissionNeverAttemptsBluetooth() {
        assertFalse(allowed(permission = false))
        assertFalse(allowed(user = Route.BLUETOOTH_HEADSET,
            automatic = Route.BLUETOOTH_HEADSET, permission = false))
    }

    @Test fun noRetryAfterCallEndsOrOldDelayedCallback() {
        assertFalse(allowed(running = false))
        assertFalse(allowed(sameSession = false))
        assertFalse(allowed(confirmed = true))
    }

    @Test fun manuallyChosenBluetoothCanWaitForDelayedHardwareDiscovery() {
        assertTrue(allowed(user = Route.BLUETOOTH_HEADSET,
            automatic = Route.EARPIECE))
        assertTrue(allowed(user = Route.BLUETOOTH_HEADSET,
            automatic = null))
        assertFalse(allowed(user = Route.EARPIECE,
            automatic = Route.EARPIECE))
        assertFalse(allowed(user = Route.SPEAKER,
            automatic = Route.SPEAKER))
    }

    @Test fun noImplicitBluetoothRetryWhenWiredOrSpeakerChosen() {
        assertFalse(allowed(automatic = Route.WIRED_HEADSET))
        assertFalse(allowed(automatic = Route.SPEAKER))
        assertFalse(allowed(automatic = null))
        // An explicit Bluetooth choice is still authoritative over a wired
        // automatic default, until the user switches preference again.
        assertTrue(allowed(user = Route.BLUETOOTH_HEADSET,
            automatic = Route.WIRED_HEADSET))
    }

    @Test fun zeroOrOverBudgetDoesNotCauseUnboundedWork() {
        for (attempt in 2..1_000) assertFalse(allowed(attempts = attempt))
        assertFalse(allowed(attempts = Int.MAX_VALUE))
    }
}
