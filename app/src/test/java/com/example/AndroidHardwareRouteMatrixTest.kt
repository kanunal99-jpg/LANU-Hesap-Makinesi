package com.example

import com.example.core.calls.CallAudioRoutePolicy
import com.example.core.calls.CallAudioRoutePolicy.Route
import org.junit.Assert.*
import org.junit.Test

/** 160 synthetic hardware/choice cases; no false OEM physical certification. */
class AndroidHardwareRouteMatrixTest {
    @Test fun everyRouteSubsetAndUserChoiceStaysWithinConnectedHardware() {
        val choices = Route.values().toList()
        var scenarios = 0
        for (mask in 0 until (1 shl choices.size)) {
            val connected = choices.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (video in listOf(false, true)) {
                for (userChoice in listOf<Route?>(null) + choices) {
                    val routes = CallAudioRoutePolicy.priorities(connected, video, userChoice)
                    assertEquals(routes.size, routes.distinct().size)
                    assertEquals(connected, routes.toSet())
                    if (userChoice != null && userChoice in connected)
                        assertEquals(userChoice, routes.first())
                    if (connected.isEmpty()) assertTrue(routes.isEmpty())
                    scenarios++
                }
            }
        }
        assertEquals(160, scenarios)
    }

    @Test fun headsetDisconnectFallsBackToEarOrSpeakerWithoutStaleRoute() {
        val all = Route.values().toSet()
        assertEquals(Route.WIRED_HEADSET,
            CallAudioRoutePolicy.priorities(all, false).first())
        val mobileOnly = setOf(Route.EARPIECE, Route.SPEAKER)
        assertEquals(Route.EARPIECE,
            CallAudioRoutePolicy.priorities(mobileOnly, false).first())
        assertEquals(Route.SPEAKER,
            CallAudioRoutePolicy.priorities(mobileOnly, true).first())
    }
}
