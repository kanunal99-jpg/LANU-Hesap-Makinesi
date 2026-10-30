package com.example.core.calls

/**
 * Distinguishes a transient radio handover from a confirmed WebRTC FAILED
 * state. A brief transient grace avoids tearing down a naturally recovering
 * Wi-Fi/mobile call; FAILED can expedite the same strictly bounded ICE restart.
 *
 * No new retry allowance, background wake, TURN server or user identifier.
 */
internal object CallWeakNetworkRecoveryPolicy {
    const val TRANSIENT_GRACE_MS = 2_250L
    const val FAILED_GRACE_MS = 300L

    fun graceMs(iceFailed: Boolean): Long =
        if (iceFailed) FAILED_GRACE_MS else TRANSIENT_GRACE_MS

    fun mayFastTrack(
        outgoing: Boolean,
        reconnecting: Boolean,
        iceFailed: Boolean,
        restartAlreadyStarted: Boolean,
        fastTrackAlreadyUsed: Boolean
    ): Boolean = outgoing && reconnecting && iceFailed &&
        !restartAlreadyStarted && !fastTrackAlreadyUsed
}
