package com.example

import com.example.core.database.DatabaseCipherCutoverGate
import com.example.core.database.DatabaseEncryptionPreflight
import org.junit.Assert.*
import org.junit.Test

class DatabaseCipherCutoverGateTest {
    private val snapshot = DatabaseEncryptionPreflight.Snapshot(
        state = DatabaseEncryptionPreflight.State.PLAINTEXT_SQLITE,
        databaseBytes = 8192,
        walBytes = 8192,
        sharedMemoryBytes = 4096,
        rollbackJournalBytes = 0,
        availableBytes = 256L * 1024 * 1024,
        conservativeRequiredBytes = 64L * 1024 * 1024,
        sufficientDiskSpace = true,
        needsWalCheckpoint = true
    )

    private fun ready() = DatabaseCipherCutoverGate.Evidence(
        snapshot = snapshot,
        stableSignerVerified = true,
        independentBackupRestoreVerified = true,
        allRoomWritersStopped = true,
        checkpointAndWalVerified = true,
        keystoreWrappingVerified = true,
        encryptedShadowReopenVerified = true,
        allTablesAndRecordsIdentical = true
    )

    private fun blocked(evidence: DatabaseCipherCutoverGate.Evidence,
                        reason: DatabaseCipherCutoverGate.BlockReason) {
        assertEquals(DatabaseCipherCutoverGate.Decision.Blocked(reason),
            DatabaseCipherCutoverGate.assess(evidence))
    }

    @Test fun noEvidenceIsEverPermissionToMigrate() {
        blocked(DatabaseCipherCutoverGate.Evidence(snapshot),
            DatabaseCipherCutoverGate.BlockReason.SIGNING_IDENTITY_NOT_VERIFIED)
    }

    @Test fun missingBackupWritersCheckpointAndKeyEachFailClosed() {
        blocked(ready().copy(independentBackupRestoreVerified = false),
            DatabaseCipherCutoverGate.BlockReason.BACKUP_NOT_RESTORED)
        blocked(ready().copy(allRoomWritersStopped = false),
            DatabaseCipherCutoverGate.BlockReason.WRITERS_STILL_ACTIVE)
        blocked(ready().copy(checkpointAndWalVerified = false),
            DatabaseCipherCutoverGate.BlockReason.WAL_CHECKPOINT_NOT_VERIFIED)
        blocked(ready().copy(keystoreWrappingVerified = false),
            DatabaseCipherCutoverGate.BlockReason.KEY_NOT_HARDWARE_PROTECTED)
    }

    @Test fun unverifiedCiphertextOrDataParityBlocksSwitch() {
        blocked(ready().copy(encryptedShadowReopenVerified = false),
            DatabaseCipherCutoverGate.BlockReason.ENCRYPTED_SHADOW_NOT_VERIFIED)
        blocked(ready().copy(allTablesAndRecordsIdentical = false),
            DatabaseCipherCutoverGate.BlockReason.RECORDS_NOT_IDENTICAL)
    }

    @Test fun corruptedDatabaseAndOrphanedWalAreNeverEligible() {
        for (state in DatabaseEncryptionPreflight.State.values().filter {
            it != DatabaseEncryptionPreflight.State.PLAINTEXT_SQLITE
        }) {
            blocked(ready().copy(snapshot = snapshot.copy(state = state)),
                DatabaseCipherCutoverGate.BlockReason.UNSUPPORTED_DATABASE)
        }
    }

    @Test fun insufficentOrUnknownFreeSpaceIsBlocked() {
        blocked(ready().copy(snapshot = snapshot.copy(sufficientDiskSpace = false)),
            DatabaseCipherCutoverGate.BlockReason.INSUFFICIENT_DISK)
        blocked(ready().copy(snapshot = snapshot.copy(availableBytes = -1)),
            DatabaseCipherCutoverGate.BlockReason.INSUFFICIENT_DISK)
    }

    @Test fun allEvidenceOnlyPermitsShadowTestNeverAutomaticCutover() {
        assertEquals(DatabaseCipherCutoverGate.Decision.ShadowTestOnly,
            DatabaseCipherCutoverGate.assess(ready()))
        // Snapshot metadata cannot carry message, PIN, key or database path.
        val label = DatabaseCipherCutoverGate.assess(ready()).toString()
        assertFalse(label.contains("lanu_calculator_db"))
        assertFalse(label.contains("private_key"))
        assertFalse(label.contains("PIN="))
    }

    @Test fun checkpointIsRequiredOnlyWhenRealWalCompanionsExist() {
        assertEquals(DatabaseCipherCutoverGate.Decision.ShadowTestOnly,
            DatabaseCipherCutoverGate.assess(
                ready().copy(snapshot = snapshot.copy(
                    walBytes = 0, sharedMemoryBytes = 0, needsWalCheckpoint = false),
                    checkpointAndWalVerified = false)))
    }
}
