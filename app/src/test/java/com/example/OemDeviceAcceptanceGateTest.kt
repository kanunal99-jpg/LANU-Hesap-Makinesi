package com.example

import com.example.core.calls.OemDeviceAcceptanceGate
import com.example.core.calls.OemDeviceAcceptanceGate.*
import org.junit.Assert.*
import org.junit.Test

class OemDeviceAcceptanceGateTest {
    private fun device(m: Manufacturer) = Observation(m, 36, true, true, true, true, true)

    @Test fun onePhoneOrSyntheticEmulatorCannotCertifyProductionCallQuality() {
        assertEquals(Decision.INSUFFICIENT_REAL_DEVICES, OemDeviceAcceptanceGate.assess(emptyList()))
        assertEquals(Decision.INSUFFICIENT_REAL_DEVICES, OemDeviceAcceptanceGate.assess(
            listOf(device(Manufacturer.SAMSUNG))))
        assertEquals(Decision.INSUFFICIENT_REAL_DEVICES, OemDeviceAcceptanceGate.assess(
            listOf(device(Manufacturer.SAMSUNG), device(Manufacturer.XIAOMI).copy(physicallyTested = false))))
    }
    @Test fun realPhonesWithoutNetworkHandoffOrLockscreenCannotPass() {
        val d1 = device(Manufacturer.SAMSUNG)
        val d2 = device(Manufacturer.PIXEL).copy(networkHandoffPassed = false)
        assertEquals(Decision.INCOMPLETE_DEVICE_ACCEPTANCE,
            OemDeviceAcceptanceGate.assess(listOf(d1, d2)))
        assertEquals(Decision.INCOMPLETE_DEVICE_ACCEPTANCE,
            OemDeviceAcceptanceGate.assess(listOf(d1, d2.copy(networkHandoffPassed = true,
                otaKeptIdentityAndPin = false))))
    }
    @Test fun completeSelfReportedEvidenceStillRequiresHumanReview() {
        assertEquals(Decision.HUMAN_REVIEW_REQUIRED, OemDeviceAcceptanceGate.assess(
            listOf(device(Manufacturer.SAMSUNG), device(Manufacturer.XIAOMI))))
    }
}
