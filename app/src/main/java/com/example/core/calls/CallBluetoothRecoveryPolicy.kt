package com.example.core.calls

import com.example.core.calls.CallAudioRoutePolicy.Route

/**
 * One bounded, foreground-only headset retry budget. AudioManager can reject
 * an initial communication request while an OEM Bluetooth/HFP route is starting.
 * Never claim a route is connected, bypass Bluetooth permission, or retry forever.
 */
internal object CallBluetoothRecoveryPolicy {
    const val MAX_RETRIES = 2
    // Prevent indefinite OEM/HFP flapping even when a headset briefly
    // reconnects between failures. An explicit user action may restart
    // the small retry window, never the total automatic call budget.
    const val MAX_AUTOMATIC_RETRIES_PER_CALL = 6
    fun withinCallBudget(attempts: Int): Boolean =
        attempts in 0 until MAX_AUTOMATIC_RETRIES_PER_CALL

    // HFP/LE Audio is asynchronous. Do not hold the UI in a false pending
    // state for thirty seconds when a headset never activates.
    const val BLUETOOTH_CONFIRMATION_TIMEOUT_MS = 9_000L
    const val ROUTE_WATCHDOG_INTERVAL_MS = 4_000L

    fun confirmationTimeoutMs(route: Route?): Long =
        if (route == Route.BLUETOOTH_HEADSET) BLUETOOTH_CONFIRMATION_TIMEOUT_MS
        else CallCommunicationDeviceConfirmationPolicy.CONFIRMATION_TIMEOUT_MS

    fun pendingExpired(startedAtMs: Long, nowMs: Long, route: Route?): Boolean =
        startedAtMs >= 0L && nowMs >= startedAtMs &&
            nowMs - startedAtMs >= confirmationTimeoutMs(route)

    /** Only reconcile on an actual state contradiction or topology change.
     * Healthy media remains on the selected route without reapplying it. */
    fun shouldReconcile(
        topologyChanged: Boolean,
        permissionChanged: Boolean,
        hasPending: Boolean,
        pendingStillPreferred: Boolean,
        pendingConfirmed: Boolean,
        reportedRouted: Boolean,
        reportedRouteValid: Boolean
    ): Boolean =
        topologyChanged || permissionChanged ||
            (hasPending && (pendingConfirmed || !pendingStillPreferred)) ||
            (reportedRouted && !reportedRouteValid)

    fun retryDelayMs(completedRetries: Int): Long =
        if (completedRetries == 0) 1_500L else 3_500L

    fun mayRetry(
        running: Boolean,
        sameSession: Boolean,
        permissionGranted: Boolean,
        manualChoice: Route?,
        automaticFirst: Route?,
        alreadyConfirmed: Boolean,
        completedRetries: Int
    ): Boolean =
        running && sameSession && permissionGranted && !alreadyConfirmed &&
            completedRetries in 0 until MAX_RETRIES &&
            (manualChoice == Route.BLUETOOTH_HEADSET ||
                automaticFirst == Route.BLUETOOTH_HEADSET)
}
