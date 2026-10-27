package com.example

import com.example.core.messaging.RatchetOfflineActivationGate
import org.junit.Assert.*
import org.junit.Test

class RatchetOfflineActivationGateTest {
    private fun ready() = RatchetOfflineActivationGate.Evidence(
        authenticatedPeerIdentity = true, signedHandshakeTranscript = true,
        independentCryptographicReview = true, monotonicRatchetStateProved = true,
        encryptedRestoreAndRollbackTested = true, replayAndDowngradeMatrixPassed = true,
        crossDeviceInteroperabilityTested = true
    )
    @Test fun missingIndependentEvidenceNeverStartsRatchet() {
        val blocked = RatchetOfflineActivationGate.assess(
            RatchetOfflineActivationGate.Evidence())
        assertTrue(blocked is RatchetOfflineActivationGate.Decision.Blocked)
        assertEquals(RatchetOfflineActivationGate.Missing.values().toSet(),
            (blocked as RatchetOfflineActivationGate.Decision.Blocked).missing)
    }
    @Test fun everySingleMissingProofBlocksPrototype() {
        val cases = listOf(
            ready().copy(authenticatedPeerIdentity = false),
            ready().copy(signedHandshakeTranscript = false),
            ready().copy(independentCryptographicReview = false),
            ready().copy(monotonicRatchetStateProved = false),
            ready().copy(encryptedRestoreAndRollbackTested = false),
            ready().copy(replayAndDowngradeMatrixPassed = false),
            ready().copy(crossDeviceInteroperabilityTested = false))
        assertTrue(cases.all { RatchetOfflineActivationGate.assess(it)
            is RatchetOfflineActivationGate.Decision.Blocked })
    }
    @Test fun evenCompleteSyntheticEvidenceIsNotLiveProtocolPermission() {
        val decision = RatchetOfflineActivationGate.assess(ready())
        assertEquals(RatchetOfflineActivationGate.Decision.OfflinePrototypeReviewOnly, decision)
        assertFalse(decision.toString().contains("Enabled"))
        assertFalse(decision.toString().contains("Migrated"))
    }
}
