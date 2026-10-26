package com.example

import com.example.core.messaging.MessageProtocolUpgradeGate
import com.example.core.messaging.MessageProtocolUpgradeGate.Decision
import com.example.core.messaging.MessageProtocolUpgradeGate.Suite
import org.junit.Assert.*
import org.junit.Test

class MessageProtocolUpgradeGateTest {
    @Test fun existingUnpinnedContactsRemainOnProvenLegacyCipher() {
        for (offered in listOf(emptySet(), setOf(Suite.STATIC_ECDH_V1),
            setOf(Suite.AUTHENTICATED_RATCHET_V2),
            setOf(Suite.STATIC_ECDH_V1, Suite.AUTHENTICATED_RATCHET_V2))) {
            for (enginePresent in listOf(false, true)) {
                assertEquals(Decision.LEGACY_ALLOWED_FOR_UNPINNED_PEER,
                    MessageProtocolUpgradeGate.decide(false, offered, enginePresent))
            }
        }
    }

    @Test fun compromisedOrDowngradedPeerCannotFallBackFromRatchet() {
        for (offered in listOf(emptySet(), setOf(Suite.STATIC_ECDH_V1),
            setOf(Suite.AUTHENTICATED_RATCHET_V2))) {
            assertEquals(Decision.BLOCK_PINNED_PEER_DOWNGRADE,
                MessageProtocolUpgradeGate.decide(true, offered, false))
        }
        assertEquals(Decision.BLOCK_PINNED_PEER_DOWNGRADE,
            MessageProtocolUpgradeGate.decide(true,
                setOf(Suite.STATIC_ECDH_V1), true))
    }

    @Test fun pinnedV2StillRequiresIndependentAuthenticatedHandshake() {
        val result = MessageProtocolUpgradeGate.decide(true,
            setOf(Suite.STATIC_ECDH_V1, Suite.AUTHENTICATED_RATCHET_V2), true)
        assertEquals(Decision.REQUIRE_VERIFIED_V2_HANDSHAKE, result)
        assertFalse(result.name.contains("CONNECTED"))
        assertFalse(result.name.contains("AUTO"))
    }
}
