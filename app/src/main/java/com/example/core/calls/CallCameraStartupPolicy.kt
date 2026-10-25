package com.example.core.calls

/**
 * A camera handle is accepted only once its native initialize + start both
 * succeed. If any OEM HAL step fails synchronously, release *that* handle and
 * let the next real Camera2/Camera1 device run. Never fabricate a video track.
 */
internal object CallCameraStartupPolicy {
    fun <T : Any> tryStart(
        candidate: T?,
        start: (T) -> Unit,
        releaseFailed: (T) -> Unit
    ): T? {
        if (candidate == null) return null
        return try {
            start(candidate)
            candidate
        } catch (_: Exception) {
            // A broken OEM cleanup must not prevent trying the next HAL.
            try { releaseFailed(candidate) } catch (_: Exception) {}
            null
        }
    }
}
