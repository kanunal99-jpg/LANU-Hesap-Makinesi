package com.example.core.database

/**
 * Fail-closed approval gate for an OFFLINE SQLCipher shadow-copy experiment.
 * This does NOT perform encryption, open Room, touch a live SQLite file,
 * create any key, checkpoint WAL, switch databases or start on app launch.
 *
 * Full, independently verified evidence is required even to start a shadow
 * test. A future, separately reviewed release must implement migration.
 */
object DatabaseCipherCutoverGate {
    enum class BlockReason {
        UNSUPPORTED_DATABASE,
        INSUFFICIENT_DISK,
        SIGNING_IDENTITY_NOT_VERIFIED,
        BACKUP_NOT_RESTORED,
        WRITERS_STILL_ACTIVE,
        WAL_CHECKPOINT_NOT_VERIFIED,
        KEY_NOT_HARDWARE_PROTECTED,
        ENCRYPTED_SHADOW_NOT_VERIFIED,
        RECORDS_NOT_IDENTICAL
    }

    data class Evidence(
        val snapshot: DatabaseEncryptionPreflight.Snapshot,
        val stableSignerVerified: Boolean = false,
        val independentBackupRestoreVerified: Boolean = false,
        val allRoomWritersStopped: Boolean = false,
        val checkpointAndWalVerified: Boolean = false,
        val keystoreWrappingVerified: Boolean = false,
        val encryptedShadowReopenVerified: Boolean = false,
        val allTablesAndRecordsIdentical: Boolean = false
    )

    sealed class Decision {
        data class Blocked(val reason: BlockReason) : Decision()
        /** Means: test an isolated shadow copy ONLY; never switch active DB. */
        object ShadowTestOnly : Decision()
    }

    fun assess(evidence: Evidence): Decision {
        val snapshot = evidence.snapshot
        if (snapshot.state != DatabaseEncryptionPreflight.State.PLAINTEXT_SQLITE)
            return Decision.Blocked(BlockReason.UNSUPPORTED_DATABASE)
        if (!snapshot.sufficientDiskSpace ||
            snapshot.availableBytes < snapshot.conservativeRequiredBytes)
            return Decision.Blocked(BlockReason.INSUFFICIENT_DISK)
        if (!evidence.stableSignerVerified)
            return Decision.Blocked(BlockReason.SIGNING_IDENTITY_NOT_VERIFIED)
        if (!evidence.independentBackupRestoreVerified)
            return Decision.Blocked(BlockReason.BACKUP_NOT_RESTORED)
        if (!evidence.allRoomWritersStopped)
            return Decision.Blocked(BlockReason.WRITERS_STILL_ACTIVE)
        if (snapshot.needsWalCheckpoint && !evidence.checkpointAndWalVerified)
            return Decision.Blocked(BlockReason.WAL_CHECKPOINT_NOT_VERIFIED)
        if (!evidence.keystoreWrappingVerified)
            return Decision.Blocked(BlockReason.KEY_NOT_HARDWARE_PROTECTED)
        if (!evidence.encryptedShadowReopenVerified)
            return Decision.Blocked(BlockReason.ENCRYPTED_SHADOW_NOT_VERIFIED)
        if (!evidence.allTablesAndRecordsIdentical)
            return Decision.Blocked(BlockReason.RECORDS_NOT_IDENTICAL)
        return Decision.ShadowTestOnly
    }
}
