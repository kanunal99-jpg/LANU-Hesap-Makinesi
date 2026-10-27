package com.example.core.calls

/**
 * Device-agnostic routing policy shared by API 23+ Android implementations.
 * Route selection is not evidence of a real connection or a microphone stream.
 */
object CallAudioRoutePolicy {
    enum class Route { WIRED_HEADSET, BLUETOOTH_HEADSET, EARPIECE, SPEAKER }

    /**
     * A connected headset is preferred to the built-in speaker/microphone.
     * User requests remain preferred only while that route is available.
     * For audio calls use the earpiece; for video calls prefer loudspeaker.
     */
    /**
     * API 31+ must not clear an already-correct communication device on every
     * callback/refresh: doing so temporarily tears down an active audio path.
     * Only keep it when the exact active device still appears in the platform
     * inventory and its route remains the highest available/user-selected one.
     */
    fun keepActiveCommunicationDevice(
        activeRoute: Route?,
        deviceStillAvailable: Boolean,
        priorities: List<Route>
    ): Boolean = deviceStillAvailable && activeRoute != null && priorities.firstOrNull() == activeRoute

    fun priorities(
        available: Set<Route>,
        isVideo: Boolean,
        userChoice: Route? = null
    ): List<Route> {
        val automatic = listOf(
            Route.WIRED_HEADSET,
            Route.BLUETOOTH_HEADSET,
            if (isVideo) Route.SPEAKER else Route.EARPIECE,
            if (isVideo) Route.EARPIECE else Route.SPEAKER
        ).filter { it in available }
        return (listOfNotNull(userChoice?.takeIf { it in available }) + automatic).distinct()
    }
}
