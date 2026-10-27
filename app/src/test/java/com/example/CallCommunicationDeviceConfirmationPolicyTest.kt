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
        assertTrue(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, setOf(42, 15), routes))
        assertFalse(Policy.confirmed(42, 15))
        assertFalse(Policy.expired(1_000L, 30_999L))
        assertTrue(Policy.confirmed(42, 42))
    }

    @Test fun unplugAndManualRouteChangeInvalidateOldPendingSelection() {
        val priorities = listOf(Route.BLUETOOTH_HEADSET, Route.SPEAKER)
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, setOf(15), priorities))
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, setOf(42, 15),
            listOf(Route.SPEAKER, Route.BLUETOOTH_HEADSET)))
        assertFalse(Policy.stillPreferred(null, Route.BLUETOOTH_HEADSET, setOf(42), priorities))
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
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, emptySet(),
            listOf(Route.BLUETOOTH_HEADSET)))
        assertFalse(Policy.stillPreferred(42, null, setOf(42), listOf(Route.BLUETOOTH_HEADSET)))
        assertFalse(Policy.stillPreferred(42, Route.BLUETOOTH_HEADSET, setOf(42), emptyList()))
    }
}
