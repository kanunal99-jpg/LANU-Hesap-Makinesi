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

    /**
     * Android can recycle an ephemeral device ID after unplug/replug.
     * An ID match alone is not confirmation of the requested route.
     */
    fun confirmedAvailableRoute(
        requestedId: Int?,
        observedId: Int?,
        requestedRoute: Route?,
        availableDeviceRoutes: Map<Int, Route>,
        bluetoothAllowed: Boolean
    ): Boolean =
        requestedId != null && confirmed(requestedId, observedId) && requestedRoute != null &&
            availableDeviceRoutes[requestedId] == requestedRoute &&
            (requestedRoute != Route.BLUETOOTH_HEADSET || bluetoothAllowed)

    /**
     * A delayed listener event is not proof of the CURRENT output. OEMs can
     * dispatch an earlier callback after switching away from that device.
     * Require the event, live AudioManager selection and inventory to agree.
     * If the live getter lags, remain pending for the bounded timeout poll.
     */
    fun confirmedCurrentCallbackRoute(
        requestedId: Int?,
        callbackId: Int?,
        currentDeviceId: Int?,
        requestedRoute: Route?,
        availableDeviceRoutes: Map<Int, Route>,
        bluetoothAllowed: Boolean
    ): Boolean =
        confirmed(requestedId, callbackId) &&
            confirmedAvailableRoute(
                requestedId, currentDeviceId, requestedRoute,
                availableDeviceRoutes, bluetoothAllowed
            )

    /**
     * An active Android device ID can be recycled across hotplug. Reuse is
     * safe only when the current inventory still classifies that exact ID
     * as the active route, permission remains granted, the device was not
     * rejected and it is still the preferred output.
     */
    fun reusableActiveRoute(
        activeDeviceId: Int?,
        activeRoute: Route?,
        availableDeviceRoutes: Map<Int, Route>,
        bluetoothAllowed: Boolean,
        rejectedDeviceIds: Set<Int>,
        priorities: List<Route>
    ): Boolean =
        activeDeviceId != null && activeDeviceId !in rejectedDeviceIds &&
            confirmedAvailableRoute(
                activeDeviceId, activeDeviceId, activeRoute,
                availableDeviceRoutes, bluetoothAllowed
            ) && CallAudioRoutePolicy.keepActiveCommunicationDevice(
                activeRoute, true, priorities
            )

    fun stillPreferred(
        pendingDeviceId: Int?,
        pendingRoute: Route?,
        availableDeviceRoutes: Map<Int, Route>,
        priorities: List<Route>
    ): Boolean =
        pendingDeviceId != null && pendingRoute != null &&
            availableDeviceRoutes[pendingDeviceId] == pendingRoute &&
            pendingRoute == priorities.firstOrNull()

    /** An already-selected device is not a valid pending result if a new,
     * higher-priority route appeared before the confirmation arrived. */
    fun confirmedPreferredPendingRoute(
        requestedId: Int?,
        observedId: Int?,
        requestedRoute: Route?,
        availableDeviceRoutes: Map<Int, Route>,
        bluetoothAllowed: Boolean,
        priorities: List<Route>
    ): Boolean =
        stillPreferred(requestedId, requestedRoute, availableDeviceRoutes, priorities) &&
            confirmedAvailableRoute(
                requestedId, observedId, requestedRoute,
                availableDeviceRoutes, bluetoothAllowed
            )

    fun validCallCallback(running: Boolean, activeGeneration: Long, callbackGeneration: Long): Boolean =
        running && activeGeneration == callbackGeneration

    fun expired(startedAtMs: Long, nowMs: Long): Boolean =
        startedAtMs >= 0L && nowMs >= startedAtMs &&
            nowMs - startedAtMs >= CONFIRMATION_TIMEOUT_MS
}
