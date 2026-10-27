package com.example.core.calls

/**
 * Native WebRTC media controls have two independent state owners:
 * AudioDeviceModule/CameraVideoCapturer and MediaStreamTrack.
 *
 * If either step fails, restore both to the previous state best-effort,
 * then surface the ORIGINAL error. Never report the requested state as
 * enabled after only one half of an operation succeeded.
 *
 * The calling engine executes this from its single serialized RTC dispatcher.
 * This does not change permissions, access private media, or open sockets.
 */
internal object CallMediaControlTransaction {
    fun apply(
        previous: Boolean,
        requested: Boolean,
        setDevice: (Boolean) -> Unit,
        setTrack: (Boolean) -> Boolean
    ) {
        if (previous == requested) return
        try {
            setDevice(requested)
            check(setTrack(requested)) { "WebRTC medya kanalı yeni durumu kabul etmedi." }
        } catch (error: Exception) {
            // Both changes can have been partially applied. Best-effort
            // compensation is required even if the first operation threw.
            try { setTrack(previous) } catch (_: Exception) { }
            try { setDevice(previous) } catch (_: Exception) { }
            throw error
        }
    }
}
