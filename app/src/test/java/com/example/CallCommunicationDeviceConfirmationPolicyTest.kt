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

    @Test fun timeoutIsBoundedAndRejectsInvalidClockSamples() {
        assertFalse(Policy.expired(1_000L, 30_999L))
        assertTrue(Policy.expired(1_000L, 31_000L))
        assertFalse(Policy.expired(1_000L, 999L))
        assertFalse(Policy.expired(-1L, 31_000L))
        assertFalse(Policy.expired(Long.MAX_VALUE, 31_000L))
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
