package com.example

import com.example.core.calls.CallAudioRoutePolicy
import com.example.core.calls.CallAudioRoutePolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioRoutePolicyTest {
    @Test fun automaticallyUsesConnectedHeadsetsBeforePhoneAudio() {
        val routes = setOf(
            Route.WIRED_HEADSET, Route.BLUETOOTH_HEADSET,
            Route.EARPIECE, Route.SPEAKER
        )
        assertEquals(Route.WIRED_HEADSET,
            CallAudioRoutePolicy.priorities(routes, false).first())
        assertEquals(Route.WIRED_HEADSET,
            CallAudioRoutePolicy.priorities(routes, true).first())
        val btOnly = routes - Route.WIRED_HEADSET
        assertEquals(Route.BLUETOOTH_HEADSET,
            CallAudioRoutePolicy.priorities(btOnly, false).first())
        assertEquals(Route.BLUETOOTH_HEADSET,
            CallAudioRoutePolicy.priorities(btOnly, true).first())
    }

    @Test fun withoutAccessoriesVoiceUsesEarpieceAndVideoUsesSpeaker() {
        val phone = setOf(Route.EARPIECE, Route.SPEAKER)
        assertEquals(Route.EARPIECE,
            CallAudioRoutePolicy.priorities(phone, false).first())
        assertEquals(Route.SPEAKER,
            CallAudioRoutePolicy.priorities(phone, true).first())
    }

    @Test fun headsetDisconnectionFallsBackWithoutStaleRoute() {
        val bt = setOf(Route.BLUETOOTH_HEADSET, Route.EARPIECE, Route.SPEAKER)
        assertEquals(Route.BLUETOOTH_HEADSET,
            CallAudioRoutePolicy.priorities(bt, false).first())
        val disconnected = bt - Route.BLUETOOTH_HEADSET
        assertEquals(Route.EARPIECE,
            CallAudioRoutePolicy.priorities(disconnected, false).first())
        assertEquals(Route.SPEAKER,
            CallAudioRoutePolicy.priorities(disconnected, true).first())
    }

    @Test fun userOverrideIsRespectedOnlyWhenAvailable() {
        val routes = setOf(Route.BLUETOOTH_HEADSET, Route.EARPIECE, Route.SPEAKER)
        assertEquals(Route.SPEAKER,
            CallAudioRoutePolicy.priorities(routes, false, Route.SPEAKER).first())
        assertEquals(Route.BLUETOOTH_HEADSET,
            CallAudioRoutePolicy.priorities(routes, false, Route.WIRED_HEADSET).first())
        assertEquals(Route.EARPIECE,
            CallAudioRoutePolicy.priorities(
                routes - Route.BLUETOOTH_HEADSET, false, Route.BLUETOOTH_HEADSET
            ).first())
    }

    @Test fun unchangedAudioRouteIsReusedWithoutBreakingActiveMedia() {
        val available = setOf(Route.EARPIECE, Route.SPEAKER)
        val voice = CallAudioRoutePolicy.priorities(available, false)
        assertTrue(CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.EARPIECE, true, voice
        ))
        val video = CallAudioRoutePolicy.priorities(available, true)
        assertTrue(CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.SPEAKER, true, video
        ))
    }

    @Test fun newlyAttachedHeadsetTakesPriorityOverOldSpeaker() {
        val priorities = CallAudioRoutePolicy.priorities(
            setOf(Route.SPEAKER, Route.EARPIECE, Route.WIRED_HEADSET), true
        )
        assertEquals(Route.WIRED_HEADSET, priorities.first())
        assertTrue(!CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.SPEAKER, true, priorities
        ))
    }

    @Test fun explicitUserRoutePreservesSelectionAndRespectsNewChoice() {
        val available = setOf(Route.EARPIECE, Route.SPEAKER, Route.BLUETOOTH_HEADSET)
        val speaker = CallAudioRoutePolicy.priorities(available, false, Route.SPEAKER)
        assertTrue(CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.SPEAKER, true, speaker
        ))
        val bluetooth = CallAudioRoutePolicy.priorities(available, false, Route.BLUETOOTH_HEADSET)
        assertTrue(!CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.SPEAKER, true, bluetooth
        ))
    }

    @Test fun disconnectedOrUnknownDeviceNeverClaimsStableRouting() {
        val priorities = listOf(Route.BLUETOOTH_HEADSET, Route.EARPIECE)
        assertTrue(!CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.BLUETOOTH_HEADSET, false, priorities
        ))
        assertTrue(!CallAudioRoutePolicy.keepActiveCommunicationDevice(
            null, true, priorities
        ))
        assertTrue(!CallAudioRoutePolicy.keepActiveCommunicationDevice(
            Route.BLUETOOTH_HEADSET, true, emptyList()
        ))
    }

    @Test fun automaticHeadsetSwitchDoesNotClearBeforeSelectingNewDevice() {
        // A newly attached headset should be selected directly, avoiding an
        // unnecessary gap from clearing the still-working speaker first.
        assertTrue(!CallAudioRoutePolicy.needsExplicitRouteReset(
            false, Route.SPEAKER, Route.WIRED_HEADSET
        ))
        assertTrue(!CallAudioRoutePolicy.needsExplicitRouteReset(
            false, Route.BLUETOOTH_HEADSET, Route.EARPIECE
        ))
    }

    @Test fun manualSwitchToDifferentRouteKeepsPreviousAudioUntilNewDeviceAccepts() {
        assertTrue(!CallAudioRoutePolicy.needsExplicitRouteReset(
            true, Route.EARPIECE, Route.SPEAKER
        ))
        assertTrue(!CallAudioRoutePolicy.needsExplicitRouteReset(
            true, null, Route.SPEAKER
        ))
    }

    @Test fun manualRetryOfSameSelectedRouteCanRecoverStuckOemAudio() {
        assertTrue(CallAudioRoutePolicy.needsExplicitRouteReset(
            true, Route.BLUETOOTH_HEADSET, Route.BLUETOOTH_HEADSET
        ))
        assertTrue(CallAudioRoutePolicy.needsExplicitRouteReset(
            true, Route.EARPIECE, Route.EARPIECE
        ))
        assertTrue(!CallAudioRoutePolicy.needsExplicitRouteReset(
            false, Route.EARPIECE, Route.EARPIECE
        ))
    }

    @Test fun absenceOfDeviceFailsClosedWithoutInventingBluetoothSupport() {
        assertTrue(CallAudioRoutePolicy.priorities(emptySet(), false).isEmpty())
        assertEquals(listOf(Route.SPEAKER),
            CallAudioRoutePolicy.priorities(setOf(Route.SPEAKER), false))
        assertEquals(listOf(Route.EARPIECE),
            CallAudioRoutePolicy.priorities(setOf(Route.EARPIECE), true))
    }
}
