package com.example.core.calls

/**
 * Device-independent decision logic for an active two-way voice/video call.
 *
 * Built-in microphones work without accessories. A Bluetooth A2DP-only speaker
 * is intentionally NOT a two-way headset: duplex BT requires SCO/HFP or LE
 * communication support. On Android 12+ AUTOMATIC defers to the platform's
 * selected communication endpoint and follows plug/unplug transitions.
 */
enum class CallOutputPreference { AUTOMATIC, SPEAKER, EARPIECE }

enum class CallAudioEndpoint {
    BLUETOOTH_CALL,
    WIRED_HEADSET,
    USB_HEADSET,
    SPEAKER,
    EARPIECE,
    UNKNOWN
}

object CallAudioRoutePolicy {
    /**
     * Explicit speaker/earpiece choices are respected only while connected.
     * A missing choice falls back to the best available two-way endpoint;
     * never turn an A2DP-only output into a call microphone.
     */
    fun select(
        available: Set<CallAudioEndpoint>,
        preference: CallOutputPreference
    ): CallAudioEndpoint {
        if (preference == CallOutputPreference.SPEAKER &&
            CallAudioEndpoint.SPEAKER in available) return CallAudioEndpoint.SPEAKER
        if (preference == CallOutputPreference.EARPIECE &&
            CallAudioEndpoint.EARPIECE in available) return CallAudioEndpoint.EARPIECE

        return listOf(
            CallAudioEndpoint.WIRED_HEADSET,
            CallAudioEndpoint.BLUETOOTH_CALL,
            CallAudioEndpoint.USB_HEADSET,
            CallAudioEndpoint.EARPIECE,
            CallAudioEndpoint.SPEAKER
        ).firstOrNull { it in available } ?: CallAudioEndpoint.UNKNOWN
    }

    /** A call without a microphone grant is not allowed to seize global routing. */
    fun mayBeginVoiceSession(microphonePermissionGranted: Boolean): Boolean =
        microphonePermissionGranted
}
