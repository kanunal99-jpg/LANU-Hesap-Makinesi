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
        conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
            8192, 8192, 4096, 0
        ),
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

    @Test fun contradictoryWalFlagCannotBypassActualCompanionFiles() {
        for (companion in listOf(
            snapshot.copy(walBytes = 4096, sharedMemoryBytes = 0,
                rollbackJournalBytes = 0, needsWalCheckpoint = false,
                conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
                    8192, 4096, 0, 0)),
            snapshot.copy(walBytes = 0, sharedMemoryBytes = 4096,
                rollbackJournalBytes = 0, needsWalCheckpoint = false,
                conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
                    8192, 0, 4096, 0)),
            snapshot.copy(walBytes = 0, sharedMemoryBytes = 0,
                rollbackJournalBytes = 4096, needsWalCheckpoint = false,
                conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
                    8192, 0, 0, 4096))
        )) {
            blocked(ready().copy(snapshot = companion,
                checkpointAndWalVerified = false),
                DatabaseCipherCutoverGate.BlockReason.WAL_CHECKPOINT_NOT_VERIFIED)
        }
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
                    walBytes = 0, sharedMemoryBytes = 0, needsWalCheckpoint = false,
                    conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
                        8192, 0, 0, 0)),
                    checkpointAndWalVerified = false)))
    }
    @Test fun forgedOrStaleDiskReservationCannotApproveShadowEncryption() {
        val reason = DatabaseCipherCutoverGate.BlockReason.INVALID_SNAPSHOT_METADATA
        for (wrong in listOf(0L, 1L, 64L * 1024 * 1024,
            snapshot.conservativeRequiredBytes - 1,
            snapshot.conservativeRequiredBytes + 1, Long.MAX_VALUE)) {
            blocked(ready().copy(snapshot = snapshot.copy(
                conservativeRequiredBytes = wrong)), reason)
        }
    }

    @Test fun negativeOrOverflowedCompanionSizesAlwaysBlockShadowTest() {
        val reason = DatabaseCipherCutoverGate.BlockReason.INVALID_SNAPSHOT_METADATA
        for (bad in listOf(
            snapshot.copy(walBytes = -1),
            snapshot.copy(sharedMemoryBytes = -1),
            snapshot.copy(rollbackJournalBytes = -1),
            snapshot.copy(databaseBytes = -1),
            snapshot.copy(databaseBytes = 511),
            snapshot.copy(databaseBytes = Long.MAX_VALUE)
        )) {
            blocked(ready().copy(snapshot = bad), reason)
        }
        assertEquals(Long.MAX_VALUE,
            DatabaseEncryptionPreflight.conservativeRequiredBytes(
                Long.MAX_VALUE, 0, 0, 0))
        assertEquals(Long.MAX_VALUE,
            DatabaseEncryptionPreflight.conservativeRequiredBytes(
                8192, -1, 0, 0))
    }

    @Test fun accurateDiskBudgetRetainsIsolatedShadowOnlyNotLiveCutover() {
        val copy = snapshot.copy(
            databaseBytes = 4096, walBytes = 0, sharedMemoryBytes = 0,
            rollbackJournalBytes = 0, needsWalCheckpoint = false,
            conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
                4096, 0, 0, 0)
        )
        assertEquals(DatabaseCipherCutoverGate.Decision.ShadowTestOnly,
            DatabaseCipherCutoverGate.assess(ready().copy(
                snapshot = copy, checkpointAndWalVerified = false)))
    }

}
