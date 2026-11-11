package com.example.core.calls

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local, privacy-safe OEM microphone capture recovery.
 *
 * A successful JavaAudioDeviceModule construction is not proof that its
 * AudioRecord/AudioFX will start. An asynchronous capture error on a
 * permission-approved hardware AEC/NS path selects WebRTC software APM for
 * subsequent calls in this process. Never reset active media, override user
 * mute, persist device identifiers, or log PCM/error text.
 *
 * This is a conservative fallback, not a measured echo/noise improvement.
 */
internal class CallAudioRecoveryPolicy {
    private val captureFailedWithHardware = AtomicBoolean(false)

    fun preferSoftware(userSelectedSoftware: Boolean): Boolean =
        userSelectedSoftware || captureFailedWithHardware.get()

    fun captureStartupFailed(
        usingHardware: Boolean,
        microphonePermissionGranted: Boolean
    ) {
        if (usingHardware && microphonePermissionGranted) {
            captureFailedWithHardware.set(true)
        }
    }

    fun hardwareQuarantined(): Boolean = captureFailedWithHardware.get()
}

internal object CallAudioRecoveryRegistry {
    val policy = CallAudioRecoveryPolicy()
}
