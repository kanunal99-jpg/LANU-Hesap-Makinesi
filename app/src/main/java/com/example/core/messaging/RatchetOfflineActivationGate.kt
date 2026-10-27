package com.example.core.messaging

/**
 * Offline design acceptance only. This class cannot activate a v2 crypto suite,
 * rewrite old messages, pin a peer, create keys or change the wire protocol.
 * An independently reviewed Double Ratchet implementation does not yet exist.
 */
object RatchetOfflineActivationGate {
    data class Evidence(
        val authenticatedPeerIdentity: Boolean = false,
        val signedHandshakeTranscript: Boolean = false,
        val independentCryptographicReview: Boolean = false,
        val monotonicRatchetStateProved: Boolean = false,
        val encryptedRestoreAndRollbackTested: Boolean = false,
        val replayAndDowngradeMatrixPassed: Boolean = false,
        val crossDeviceInteroperabilityTested: Boolean = false
    )
    enum class Missing {
        PEER_IDENTITY, AUTHENTICATED_HANDSHAKE, CRYPTO_REVIEW,
        PERSISTENT_RATCHET_STATE, BACKUP_ROLLBACK, REPLAY_DOWNGRADE, INTEROPERABILITY
    }
    sealed class Decision {
        data class Blocked(val missing: Set<Missing>) : Decision()
        object OfflinePrototypeReviewOnly : Decision()
    }
    fun assess(e: Evidence): Decision {
        val missing = mutableSetOf<Missing>()
        if (!e.authenticatedPeerIdentity) missing += Missing.PEER_IDENTITY
        if (!e.signedHandshakeTranscript) missing += Missing.AUTHENTICATED_HANDSHAKE
        if (!e.independentCryptographicReview) missing += Missing.CRYPTO_REVIEW
        if (!e.monotonicRatchetStateProved) missing += Missing.PERSISTENT_RATCHET_STATE
        if (!e.encryptedRestoreAndRollbackTested) missing += Missing.BACKUP_ROLLBACK
        if (!e.replayAndDowngradeMatrixPassed) missing += Missing.REPLAY_DOWNGRADE
        if (!e.crossDeviceInteroperabilityTested) missing += Missing.INTEROPERABILITY
        return if (missing.isEmpty()) Decision.OfflinePrototypeReviewOnly
        else Decision.Blocked(missing)
    }
}
