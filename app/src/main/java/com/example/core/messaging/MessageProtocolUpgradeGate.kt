package com.example.core.messaging

/**
 * Pre-migration threat-model gate. NO Double Ratchet is implemented or
 * negotiated by current LANU builds. It does not inspect keys or messages,
 * allocate state, change wire payloads, store trust or send a packet.
 *
 * Future work must first provide a reviewed and authenticated v2 handshake,
 * retained device-specific ratchet state and rollback-safe backup semantics.
 */
object MessageProtocolUpgradeGate {
    enum class Suite { STATIC_ECDH_V1, AUTHENTICATED_RATCHET_V2 }
    enum class Decision {
        LEGACY_ALLOWED_FOR_UNPINNED_PEER,
        BLOCK_PINNED_PEER_DOWNGRADE,
        REQUIRE_VERIFIED_V2_HANDSHAKE
    }

    /**
     * A peer that has ever been safely pinned to v2 must never silently fall
     * back to the long-term static ECDH cipher on packet loss, app downgrade,
     * backup restore, or a forged capability advertisement.
     *
     * Even when both sides advertise v2, this gate NEVER automatically sends
     * v2; an independently verified cryptographic session must authorize it.
     */
    fun decide(
        peerPinnedToV2: Boolean,
        offered: Set<Suite>,
        reviewedV2EnginePresent: Boolean
    ): Decision {
        if (peerPinnedToV2) {
            return if (reviewedV2EnginePresent && Suite.AUTHENTICATED_RATCHET_V2 in offered)
                Decision.REQUIRE_VERIFIED_V2_HANDSHAKE
            else Decision.BLOCK_PINNED_PEER_DOWNGRADE
        }
        // V1 remains the current supported protocol. An untrusted offer of v2
        // alone must not cause a silent wire-format or key change.
        return Decision.LEGACY_ALLOWED_FOR_UNPINNED_PEER
    }
}
