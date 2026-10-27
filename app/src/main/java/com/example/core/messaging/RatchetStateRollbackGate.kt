package com.example.core.messaging

/**
 * Offline-only high-water state comparison for a future authenticated
 * Double Ratchet design. These public counters are NOT cryptographic
 * authentication, persistence, replay protection or a v2 implementation.
 * A reviewed Keystore-bound, rollback-resistant anchor is required before
 * this could participate in any real backup/restore decision.
 */
object RatchetStateRollbackGate {
    data class Counters(
        val sentTotal: Long,
        val receivedTotal: Long,
        val dhSteps: Long,
        val backupGeneration: Long
    )

    enum class Block {
        NO_TRUSTED_ANCHOR,
        INVALID_COUNTER,
        BACKUP_GENERATION_ROLLBACK,
        MESSAGE_COUNTER_ROLLBACK,
        DH_STEP_ROLLBACK
    }

    sealed class Decision {
        data class Blocked(val reason: Block) : Decision()
        /** Never permission to send v2, load a real identity or install backup. */
        object OfflineComparisonOnly : Decision()
    }

    fun compare(
        trustedAnchor: Counters?,
        restored: Counters,
        anchorAuthenticatedAndDeviceBound: Boolean
    ): Decision {
        if (!anchorAuthenticatedAndDeviceBound || trustedAnchor == null)
            return Decision.Blocked(Block.NO_TRUSTED_ANCHOR)

        val counters = listOf(
            trustedAnchor.sentTotal, trustedAnchor.receivedTotal,
            trustedAnchor.dhSteps, trustedAnchor.backupGeneration,
            restored.sentTotal, restored.receivedTotal,
            restored.dhSteps, restored.backupGeneration
        )
        if (counters.any { it < 0L || it == Long.MAX_VALUE })
            return Decision.Blocked(Block.INVALID_COUNTER)

        if (restored.backupGeneration < trustedAnchor.backupGeneration)
            return Decision.Blocked(Block.BACKUP_GENERATION_ROLLBACK)
        if (restored.dhSteps < trustedAnchor.dhSteps)
            return Decision.Blocked(Block.DH_STEP_ROLLBACK)
        // Totals are lifetime high-watermarks; chain-local N/PN may reset
        // after a DH ratchet, so do not compare chain-local counters here.
        if (restored.sentTotal < trustedAnchor.sentTotal ||
            restored.receivedTotal < trustedAnchor.receivedTotal)
            return Decision.Blocked(Block.MESSAGE_COUNTER_ROLLBACK)
        return Decision.OfflineComparisonOnly
    }
}
