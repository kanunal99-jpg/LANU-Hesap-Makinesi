package com.example.core.calls

/**
 * Local acceptance criteria for reviewing an OEM call-device test report.
 * Never probes hardware, requests permissions, identifies a device or sends
 * telemetry. A synthetic CI result is never physical-device certification.
 */
object OemDeviceAcceptanceGate {
    enum class Manufacturer { SAMSUNG, XIAOMI, PIXEL, OTHER }
    data class Observation(
        val manufacturer: Manufacturer,
        val apiLevel: Int,
        val physicallyTested: Boolean,
        val voiceAndVideoPassed: Boolean,
        val backgroundAndLockscreenPassed: Boolean,
        val networkHandoffPassed: Boolean,
        val otaKeptIdentityAndPin: Boolean
    )
    enum class Decision { INSUFFICIENT_REAL_DEVICES, INCOMPLETE_DEVICE_ACCEPTANCE, HUMAN_REVIEW_REQUIRED }

    fun assess(reports: List<Observation>): Decision {
        if (reports.size < 2 || reports.map { it.manufacturer }.toSet().size < 2 ||
            reports.any { !it.physicallyTested || it.apiLevel < 23 }) {
            return Decision.INSUFFICIENT_REAL_DEVICES
        }
        if (reports.any {
            !it.voiceAndVideoPassed || !it.backgroundAndLockscreenPassed ||
                !it.networkHandoffPassed || !it.otaKeptIdentityAndPin
        }) return Decision.INCOMPLETE_DEVICE_ACCEPTANCE
        // A reviewer must still inspect the test evidence and installed signer.
        return Decision.HUMAN_REVIEW_REQUIRED
    }
}
