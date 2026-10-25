package com.example.core.calls

/**
 * WebRTC ADM owns the call's sole AEC/NS pipeline. Respect independently
 * reported built-in availability, leaving unsupported effects to WebRTC APM.
 * This is a capability decision, NOT a measured noise reduction guarantee.
 * ANC is headset hardware and is intentionally not represented as enabled.
 */
internal object CallAudioProcessingPolicy {
    data class Decision(val hardwareAec: Boolean, val hardwareNs: Boolean)
    fun choose(aecReported: Boolean, nsReported: Boolean): Decision =
        Decision(hardwareAec = aecReported, hardwareNs = nsReported)
}
