package com.example

import com.example.core.messaging.MessageProtocolUpgradeGate
import com.example.core.messaging.MessageProtocolUpgradeGate.Decision
import com.example.core.messaging.MessageProtocolUpgradeGate.Suite
import org.junit.Assert.*
import org.junit.Test

/** Current v1 messages are untouched. No Double Ratchet implementation claim. */
class RatchetDowngradeExhaustiveMatrixTest {
    @Test fun everyPeerPinEngineAndOfferCombinationFailsClosedAsDesigned() {
        val suites = Suite.values()
        var covered = 0
        for (pinned in listOf(false, true))
            for (enginePresent in listOf(false, true))
                for (mask in 0 until (1 shl suites.size)) {
                    val offered = suites.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
                    val expected = when {
                        !pinned -> Decision.LEGACY_ALLOWED_FOR_UNPINNED_PEER
                        enginePresent && Suite.AUTHENTICATED_RATCHET_V2 in offered ->
                            Decision.REQUIRE_VERIFIED_V2_HANDSHAKE
                        else -> Decision.BLOCK_PINNED_PEER_DOWNGRADE
                    }
                    assertEquals(expected, MessageProtocolUpgradeGate.decide(pinned, offered, enginePresent))
                    covered++
                }
        assertEquals(16, covered)
    }

    @Test fun pinnedPeerMayNeverReturnLegacyAfterMissingOfferOrMissingEngine() {
        val offers = setOf(Suite.STATIC_ECDH_V1)
        assertEquals(Decision.BLOCK_PINNED_PEER_DOWNGRADE,
            MessageProtocolUpgradeGate.decide(true, offers, true))
        assertEquals(Decision.BLOCK_PINNED_PEER_DOWNGRADE,
            MessageProtocolUpgradeGate.decide(true, setOf(Suite.AUTHENTICATED_RATCHET_V2), false))
    }
}
