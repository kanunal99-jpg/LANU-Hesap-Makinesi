package com.example

import com.example.core.calls.CallAudioRoutePolicy.Route
import com.example.core.calls.CallCommunicationDeviceConfirmationPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class CallCommunicationDeviceConfirmationPolicyTest {
    @Test fun acceptedRequestIsNotAnImmediatelyConfirmedRoute() {
        assertFalse(Policy.confirmed(42, null))
        assertFalse(Policy.confirmed(42, 15))
        assertTrue(Policy.confirmed(42, 42))
    }

    @Test fun delayedBluetoothConfirmationCanRemainPendingWithoutCompetingRequest() {
        val routes = listOf(Route.BLUETOOTH_HEADSET, Route.EARPIECE)
        assertTrue(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, mapOf(42 to Route.BLUETOOTH_HEADSET, 15 to Route.EARPIECE), routes))
        assertFalse(Policy.confirmed(42, 15))
        assertFalse(Policy.expired(1_000L, 30_999L))
        assertTrue(Policy.confirmed(42, 42))
    }

    @Test fun unplugAndManualRouteChangeInvalidateOldPendingSelection() {
        val priorities = listOf(Route.BLUETOOTH_HEADSET, Route.SPEAKER)
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, mapOf(15 to Route.SPEAKER), priorities))
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, mapOf(42 to Route.BLUETOOTH_HEADSET, 15 to Route.SPEAKER),
            listOf(Route.SPEAKER, Route.BLUETOOTH_HEADSET)))
        assertFalse(Policy.stillPreferred(null, Route.BLUETOOTH_HEADSET, mapOf(42 to Route.BLUETOOTH_HEADSET), priorities))
    }

    @Test fun reusedAndroidDeviceIdWithDifferentAudioRouteIsNotAccepted() {
        val oldBluetoothId = 42
        // A fast unplug/plug cycle may recycle an ephemeral Android device ID.
        // The newly attached SPEAKER must never prove an old Bluetooth route.
        assertFalse(Policy.stillPreferred(oldBluetoothId, Route.BLUETOOTH_HEADSET,
            mapOf(oldBluetoothId to Route.SPEAKER),
            listOf(Route.BLUETOOTH_HEADSET, Route.SPEAKER)))
        assertTrue(Policy.stillPreferred(oldBluetoothId, Route.BLUETOOTH_HEADSET,
            mapOf(oldBluetoothId to Route.BLUETOOTH_HEADSET),
            listOf(Route.BLUETOOTH_HEADSET, Route.SPEAKER)))
    }

    @Test fun exactRouteConfirmationRejectsRecycledIdAndRevokedBluetoothPermission() {
        val requestedId = 42
        assertFalse(Policy.confirmedAvailableRoute(
            requestedId, requestedId, Route.BLUETOOTH_HEADSET,
            mapOf(requestedId to Route.SPEAKER), true
        ))
        assertFalse(Policy.confirmedAvailableRoute(
            requestedId, requestedId, Route.BLUETOOTH_HEADSET,
            mapOf(requestedId to Route.BLUETOOTH_HEADSET), false
        ))
        assertFalse(Policy.confirmedAvailableRoute(
            requestedId, requestedId, Route.BLUETOOTH_HEADSET,
            emptyMap(), true
        ))
        assertFalse(Policy.confirmedAvailableRoute(
            requestedId, 99, Route.BLUETOOTH_HEADSET,
            mapOf(requestedId to Route.BLUETOOTH_HEADSET), true
        ))
        assertTrue(Policy.confirmedAvailableRoute(
            requestedId, requestedId, Route.BLUETOOTH_HEADSET,
            mapOf(requestedId to Route.BLUETOOTH_HEADSET), true
        ))
        assertTrue(Policy.confirmedAvailableRoute(
            10, 10, Route.EARPIECE, mapOf(10 to Route.EARPIECE), false
        ))
    }

    @Test fun delayedCallbackCannotConfirmAnAlreadyReplacedActiveOutput() {
        val routes = mapOf(42 to Route.BLUETOOTH_HEADSET, 15 to Route.EARPIECE)
        // Callback still mentions Bluetooth, but Android has switched to earpiece.
        assertFalse(Policy.confirmedCurrentCallbackRoute(
            42, 42, 15, Route.BLUETOOTH_HEADSET, routes, true
        ))
        // A lagging or failed current-device getter is not evidence of success.
        assertFalse(Policy.confirmedCurrentCallbackRoute(
            42, 42, null, Route.BLUETOOTH_HEADSET, routes, true
        ))
        // An unrelated callback cannot confirm a pending request.
        assertFalse(Policy.confirmedCurrentCallbackRoute(
            42, 15, 42, Route.BLUETOOTH_HEADSET, routes, true
        ))
        // The same route still needs an available device and permission.
        assertFalse(Policy.confirmedCurrentCallbackRoute(
            42, 42, 42, Route.BLUETOOTH_HEADSET, routes, false
        ))
        assertFalse(Policy.confirmedCurrentCallbackRoute(
            42, 42, 42, Route.BLUETOOTH_HEADSET,
            mapOf(42 to Route.SPEAKER), true
        ))
        assertTrue(Policy.confirmedCurrentCallbackRoute(
            42, 42, 42, Route.BLUETOOTH_HEADSET, routes, true
        ))
        assertTrue(Policy.confirmedCurrentCallbackRoute(
            15, 15, 15, Route.EARPIECE, routes, false
        ))
    }

    @Test fun activeRouteSwitchWithoutHotplugCannotKeepStaleBluetoothLabel() {
        // The old route may be gone even though the inventory is unchanged:
        // the OS switched from Bluetooth to a different active output.
        val inventory = mapOf(
            9 to Route.BLUETOOTH_HEADSET,
            10 to Route.SPEAKER
        )
        assertFalse(Policy.confirmedAvailableRoute(
            9, 10, Route.BLUETOOTH_HEADSET, inventory, true
        ))
        assertFalse(Policy.confirmedAvailableRoute(
            null, null, Route.BLUETOOTH_HEADSET, inventory, true
        ))
        assertTrue(Policy.confirmedAvailableRoute(
            10, 10, Route.SPEAKER, inventory, false
        ))
    }

    @Test fun activeRouteReuseRequiresExactCurrentDeviceTypeAndPermission() {
        val bluetooth = Route.BLUETOOTH_HEADSET
        val priorities = listOf(bluetooth, Route.SPEAKER)
        val inventory = mapOf(42 to bluetooth, 10 to Route.SPEAKER)
        assertTrue(Policy.reusableActiveRoute(
            42, bluetooth, inventory, true, emptySet(), priorities
        ))
        // OEM hotplug can recycle ID 42 for a different output type.
        assertFalse(Policy.reusableActiveRoute(
            42, bluetooth, mapOf(42 to Route.SPEAKER, 99 to bluetooth),
            true, emptySet(), priorities
        ))
        assertFalse(Policy.reusableActiveRoute(
            42, bluetooth, inventory, false, emptySet(), priorities
        ))
        assertFalse(Policy.reusableActiveRoute(
            42, bluetooth, inventory, true, setOf(42), priorities
        ))
    }

    @Test fun activeRouteReuseRejectsMissingOrNonPreferredDevice() {
        val priorities = listOf(Route.WIRED_HEADSET, Route.SPEAKER)
        val inventory = mapOf(10 to Route.SPEAKER, 11 to Route.WIRED_HEADSET)
        assertFalse(Policy.reusableActiveRoute(
            10, Route.SPEAKER, inventory, true, emptySet(), priorities
        ))
        assertFalse(Policy.reusableActiveRoute(
            99, Route.WIRED_HEADSET, inventory, true, emptySet(), priorities
        ))
        assertFalse(Policy.reusableActiveRoute(
            null, Route.WIRED_HEADSET, inventory, true, emptySet(), priorities
        ))
        assertTrue(Policy.reusableActiveRoute(
            11, Route.WIRED_HEADSET, inventory, false, emptySet(), priorities
        ))
    }

    @Test fun timeoutIsBoundedAndRejectsInvalidClockSamples() {
        assertFalse(Policy.expired(1_000L, 30_999L))
        assertTrue(Policy.expired(1_000L, 31_000L))
        assertFalse(Policy.expired(1_000L, 999L))
        assertFalse(Policy.expired(-1L, 31_000L))
        assertFalse(Policy.expired(Long.MAX_VALUE, 31_000L))
    }

    @Test fun pendingSpeakerConfirmationCannotOverrideNewlyAttachedWiredHeadset() {
        val inventory = mapOf(7 to Route.SPEAKER, 8 to Route.WIRED_HEADSET)
        assertTrue(Policy.confirmedAvailableRoute(
            7, 7, Route.SPEAKER, inventory, true
        ))
        assertFalse(Policy.confirmedPreferredPendingRoute(
            7, 7, Route.SPEAKER, inventory, true,
            listOf(Route.WIRED_HEADSET, Route.SPEAKER)
        ))
        // An explicit user preference for speaker is still respected.
        assertTrue(Policy.confirmedPreferredPendingRoute(
            7, 7, Route.SPEAKER, inventory, true,
            listOf(Route.SPEAKER, Route.WIRED_HEADSET)
        ))
    }

    @Test fun expiredOemRouteCannotConfirmOldSpeakerWhenWiredHeadsetArrived() {
        val pendingId = 7
        val newInventory = mapOf(
            pendingId to Route.SPEAKER,
            8 to Route.WIRED_HEADSET
        )
        assertTrue(Policy.expired(1_000L, 31_000L))
        // Android accepted the old request and eventually reports that same
        // speaker ID; a new wired output is now the real preferred choice.
        assertTrue(Policy.confirmedAvailableRoute(
            pendingId, pendingId, Route.SPEAKER, newInventory, true
        ))
        assertFalse("The deadline cannot label a superseded output ROUTED",
            Policy.confirmedPreferredPendingRoute(
                pendingId, pendingId, Route.SPEAKER, newInventory, true,
                listOf(Route.WIRED_HEADSET, Route.SPEAKER)
            ))
        // An explicit manual speaker selection remains authoritative.
        assertTrue(Policy.confirmedPreferredPendingRoute(
            pendingId, pendingId, Route.SPEAKER, newInventory, true,
            listOf(Route.SPEAKER, Route.WIRED_HEADSET)
        ))
    }

    @Test fun expiredOemBluetoothConfirmationRequiresLiveTypeAndPermission() {
        val pendingId = 41
        assertTrue(Policy.expired(0L, Policy.CONFIRMATION_TIMEOUT_MS))
        val preference = listOf(Route.BLUETOOTH_HEADSET, Route.EARPIECE)
        assertFalse(Policy.confirmedPreferredPendingRoute(
            pendingId, pendingId, Route.BLUETOOTH_HEADSET,
            mapOf(pendingId to Route.EARPIECE), true, preference
        ))
        assertFalse(Policy.confirmedPreferredPendingRoute(
            pendingId, pendingId, Route.BLUETOOTH_HEADSET,
            mapOf(pendingId to Route.BLUETOOTH_HEADSET), false, preference
        ))
        assertTrue(Policy.confirmedPreferredPendingRoute(
            pendingId, pendingId, Route.BLUETOOTH_HEADSET,
            mapOf(pendingId to Route.BLUETOOTH_HEADSET), true, preference
        ))
    }

    @Test fun staleCallbackOrPermissionLossCannotConfirmPendingRoute() {
        val inventory = mapOf(7 to Route.BLUETOOTH_HEADSET, 8 to Route.EARPIECE)
        val priorities = listOf(Route.BLUETOOTH_HEADSET, Route.EARPIECE)
        assertFalse(Policy.confirmedPreferredPendingRoute(
            7, 8, Route.BLUETOOTH_HEADSET, inventory, true, priorities
        ))
        assertFalse(Policy.confirmedPreferredPendingRoute(
            7, null, Route.BLUETOOTH_HEADSET, inventory, true, priorities
        ))
        assertFalse(Policy.confirmedPreferredPendingRoute(
            7, 7, Route.BLUETOOTH_HEADSET, inventory, false, priorities
        ))
        assertFalse(Policy.confirmedPreferredPendingRoute(
            7, 7, Route.BLUETOOTH_HEADSET,
            mapOf(7 to Route.EARPIECE), true, priorities
        ))
        assertTrue(Policy.confirmedPreferredPendingRoute(
            7, 7, Route.BLUETOOTH_HEADSET, inventory, true, priorities
        ))
    }

    @Test fun lateCallbackFromClosedOrEarlierCallIsIgnored() {
        assertTrue(Policy.validCallCallback(true, 4L, 4L))
        assertFalse(Policy.validCallCallback(false, 4L, 4L))
        assertFalse(Policy.validCallCallback(true, 5L, 4L))
        assertFalse(Policy.confirmed(15, 42))
    }

    @Test fun missingInventoryOrInvalidPriorityCannotKeepRoutePending() {
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, emptyMap(),
            listOf(Route.BLUETOOTH_HEADSET)))
        assertFalse(Policy.stillPreferred(42, null, mapOf(42 to Route.BLUETOOTH_HEADSET), listOf(Route.BLUETOOTH_HEADSET)))
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, mapOf(42 to Route.BLUETOOTH_HEADSET), emptyList()))
    }
}
