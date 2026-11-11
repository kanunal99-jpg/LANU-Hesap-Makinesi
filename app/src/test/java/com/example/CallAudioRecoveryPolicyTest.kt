package com.example

import com.example.core.calls.CallAudioRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test

class CallAudioRecoveryPolicyTest {
    @Test fun healthyHardwareRemainsPreferredUntilRealAuthorizedCaptureFailure() {
        val recovery = CallAudioRecoveryPolicy()
        assertFalse(recovery.preferSoftware(false))
        assertTrue(recovery.preferSoftware(true))
        assertFalse(recovery.hardwareQuarantined())
        recovery.captureStartupFailed(usingHardware = false, microphonePermissionGranted = true)
        assertFalse(recovery.preferSoftware(false))
        recovery.captureStartupFailed(usingHardware = true, microphonePermissionGranted = false)
        assertFalse("Revoked mic permission is not an OEM fault", recovery.preferSoftware(false))
    }

    @Test fun asynchronousOemCaptureFailureSelectsSoftwareWithoutChangingStoredPreference() {
        val recovery = CallAudioRecoveryPolicy()
        recovery.captureStartupFailed(usingHardware = true, microphonePermissionGranted = true)
        assertTrue(recovery.hardwareQuarantined())
        assertTrue(recovery.preferSoftware(false))
        assertTrue(recovery.preferSoftware(true))
        // Another call failing on software never re-enables broken hardware.
        recovery.captureStartupFailed(usingHardware = false, microphonePermissionGranted = true)
        assertTrue(recovery.preferSoftware(false))
    }

    @Test fun freshProcessPolicyHasNoPersistentDeviceOrPeerState() {
        val failedProcess = CallAudioRecoveryPolicy()
        failedProcess.captureStartupFailed(usingHardware = true, microphonePermissionGranted = true)
        assertTrue(failedProcess.hardwareQuarantined())
        assertFalse(CallAudioRecoveryPolicy().hardwareQuarantined())
    }
}
