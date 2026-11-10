package com.example

import com.example.core.calls.CallAudioRoutePolicy.Route
import com.example.core.calls.CallBluetoothRecoveryPolicy as Policy
import com.example.core.calls.CallCommunicationDeviceConfirmationPolicy
import org.junit.Assert.*
import org.junit.Test

/** Pure contract tests: hardware and Android OEM callbacks remain device acceptance. */
class CallBluetoothRouteWatchdogTest {
    private fun reconcile(
        topology: Boolean = false,
        permission: Boolean = false,
        hasPending: Boolean = false,
        pendingPreferred: Boolean = true,
        pendingConfirmed: Boolean = false,
        routed: Boolean = false,
        routeValid: Boolean = true
    ): Boolean = Policy.shouldReconcile(
        topology, permission, hasPending, pendingPreferred, pendingConfirmed,
        routed, routeValid
    )

    @Test fun bluetoothTimeoutRecoversFasterThanUnrelatedAudio() {
        assertEquals(15_000L, Policy.confirmationTimeoutMs(Route.BLUETOOTH_HEADSET))
        assertEquals(CallCommunicationDeviceConfirmationPolicy.CONFIRMATION_TIMEOUT_MS,
            Policy.confirmationTimeoutMs(Route.SPEAKER))
        assertEquals(CallCommunicationDeviceConfirmationPolicy.CONFIRMATION_TIMEOUT_MS,
            Policy.confirmationTimeoutMs(Route.EARPIECE))
        assertFalse(Policy.pendingExpired(5_000L, 19_999L, Route.BLUETOOTH_HEADSET))
        assertTrue(Policy.pendingExpired(5_000L, 20_000L, Route.BLUETOOTH_HEADSET))
        assertFalse(Policy.pendingExpired(5_000L, 14_000L, Route.SPEAKER))
        assertTrue(Policy.pendingExpired(5_000L, 35_000L, Route.SPEAKER))
        assertFalse(Policy.pendingExpired(-1L, 200_000L, Route.BLUETOOTH_HEADSET))
        assertFalse(Policy.pendingExpired(20_000L, 19_999L, Route.BLUETOOTH_HEADSET))
    }

    @Test fun transientOemGetterMismatchDoesNotInterruptWorkingHeadset() {
        assertEquals(2, Policy.STALE_ROUTE_CONFIRMATION_SAMPLES)
        assertFalse(Policy.confirmedStaleRoute(0))
        assertFalse(Policy.confirmedStaleRoute(1))
        assertTrue(Policy.confirmedStaleRoute(2))
        assertTrue(Policy.confirmedStaleRoute(3))
        assertFalse(reconcile(routed = true, routeValid = true))
        // Watchdog passes a first transient mismatch as effectively valid;
        // only a second contradictory sample triggers recovery.
        assertFalse(reconcile(routed = true,
            routeValid = !Policy.confirmedStaleRoute(1)))
        assertTrue(reconcile(routed = true,
            routeValid = !Policy.confirmedStaleRoute(2)))
    }

    @Test fun normalHealthyCallNeverResetsAudioEveryWatchdogTick() {
        assertEquals(4_000L, Policy.ROUTE_WATCHDOG_INTERVAL_MS)
        assertFalse(reconcile())
        assertFalse(reconcile(routed = true))
        assertFalse(reconcile(hasPending = true, pendingPreferred = true))
        assertFalse(reconcile(hasPending = false, pendingConfirmed = true))
    }

    @Test fun missedAndroidEventsAndPermissionRevocationNeedReconciliation() {
        assertTrue(reconcile(topology = true))
        assertTrue(reconcile(permission = true))
        assertTrue(reconcile(routed = true, routeValid = false))
        assertTrue(reconcile(hasPending = true, pendingPreferred = false))
        assertTrue(reconcile(hasPending = true, pendingConfirmed = true))
        assertFalse(reconcile(hasPending = true, pendingConfirmed = false,
            pendingPreferred = true, routed = true))
    }

    @Test fun rejectedHeadsetNeverStealsHealthyFallbackOnRefresh() {
        val inventory = mapOf(
            17 to Route.BLUETOOTH_HEADSET,
            18 to Route.EARPIECE,
            19 to Route.SPEAKER
        )
        val rejected = Policy.eligibleRoutes(inventory, setOf(17), true)
        assertEquals(setOf(Route.EARPIECE, Route.SPEAKER), rejected)
        assertEquals(Route.EARPIECE,
            com.example.core.calls.CallAudioRoutePolicy.priorities(
                rejected, false, Route.BLUETOOTH_HEADSET
            ).first())
        assertEquals(setOf(Route.EARPIECE, Route.SPEAKER),
            Policy.eligibleRoutes(inventory, emptySet(), false))
        assertTrue(Route.BLUETOOTH_HEADSET in Policy.eligibleRoutes(
            inventory + (20 to Route.BLUETOOTH_HEADSET), setOf(17), true))
        assertEquals(emptySet<Route>(),
            Policy.eligibleRoutes(emptyMap(), setOf(17), true))
    }

    @Test fun bothRetryBudgetsRemainFiniteAcrossRepeatedOemEvents() {
        assertEquals(2, Policy.MAX_RETRIES)
        assertEquals(6, Policy.MAX_AUTOMATIC_RETRIES_PER_CALL)
        assertTrue(Policy.withinCallBudget(0))
        assertTrue(Policy.withinCallBudget(5))
        for (attempts in listOf(-2, -1, 6, 7, Int.MAX_VALUE)) {
            assertFalse(Policy.withinCallBudget(attempts))
        }
        // The per-episode retry counter may reset on confirmed Bluetooth;
        // the per-call total MUST remain exhausted without an explicit new call.
        assertTrue(Policy.mayRetry(
            true, true, true, Route.BLUETOOTH_HEADSET, Route.SPEAKER, false, 0
        ))
        assertFalse(Policy.withinCallBudget(6))
    }
}
