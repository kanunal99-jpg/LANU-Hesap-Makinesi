package com.example.core.calls

import com.example.core.calls.CallAudioRoutePolicy.Route

/**
 * Android 12+ may accept setCommunicationDevice() before the selected sink
 * is active. Do not show ROUTED until the platform reports that exact ID.
 * No device identifiers are logged, transmitted or persisted.
 */
internal object CallCommunicationDeviceConfirmationPolicy {
    const val CONFIRMATION_TIMEOUT_MS = 30_000L

    fun confirmed(requestedId: Int?, observedId: Int?): Boolean =
        requestedId != null && requestedId == observedId

    fun stillPreferred(
        pendingDeviceId: Int?,
        pendingRoute: Route?,
        availableDeviceRoutes: Map<Int, Route>,
        priorities: List<Route>
    ): Boolean =
        pendingDeviceId != null && pendingRoute != null &&
            availableDeviceRoutes[pendingDeviceId] == pendingRoute &&
            pendingRoute == priorities.firstOrNull()

    fun validCallCallback(running: Boolean, activeGeneration: Long, callbackGeneration: Long): Boolean =
        running && activeGeneration == callbackGeneration

    fun expired(startedAtMs: Long, nowMs: Long): Boolean =
        startedAtMs >= 0L && nowMs >= startedAtMs &&
            nowMs - startedAtMs >= CONFIRMATION_TIMEOUT_MS
}
