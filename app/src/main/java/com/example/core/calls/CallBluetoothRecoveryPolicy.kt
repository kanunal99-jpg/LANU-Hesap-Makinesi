package com.example.core.calls

import com.example.core.calls.CallAudioRoutePolicy.Route

/**
 * One bounded, foreground-only headset retry budget. AudioManager can reject
 * an initial communication request while an OEM Bluetooth/HFP route is starting.
 * Never claim a route is connected, bypass Bluetooth permission, or retry forever.
 */
internal object CallBluetoothRecoveryPolicy {
    const val MAX_RETRIES = 2
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
