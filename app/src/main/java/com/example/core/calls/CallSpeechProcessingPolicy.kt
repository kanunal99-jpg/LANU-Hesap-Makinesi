package com.example.core.calls

/**
 * Existing WebRTC APM only. No second PCM filter, vendor effects or cloud
 * capture are attached. Optional MediaConstraints are hints to native WebRTC:
 * unsupported keys may be ignored without changing call identity or media.
 *
 * Mono is a deliberate speech-call preference, not a codec downsample; Opus
 * remains negotiated by the existing PeerConnection and SDP FEC policy.
 */
internal object CallSpeechProcessingPolicy {
    const val stereoMicrophone = false
    const val stereoSpeaker = false
    const val volumeLogger = false

    fun audioSourceConstraints(): List<Pair<String, String>> = listOf(
        "googEchoCancellation" to "true",
        "googNoiseSuppression" to "true",
        "googAutoGainControl" to "true",
        // Low-frequency rumble/handling noise on the supported software APM.
        // This is not a guaranteed measured noise reduction; the native
        // WebRTC version decides whether it supports this optional setting.
        "googHighpassFilter" to "true"
    )
}
