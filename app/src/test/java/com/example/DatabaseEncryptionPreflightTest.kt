package com.example

import com.example.core.database.DatabaseEncryptionPreflight
import com.example.core.database.DatabaseEncryptionPreflight.State
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The preflight MUST NOT create, migrate, delete or rewrite a live database
 * or any WAL/SHM companion. Tests use only temporary synthetic fixtures.
 */
class DatabaseEncryptionPreflightTest {
    @get:Rule val files = TemporaryFolder()

    private fun database(): File = File(files.root, "lanu_calculator_db")
    private fun sqlite(size: Int = 8192): ByteArray =
        ByteArray(size) { (it % 101).toByte() }.also { body ->
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                .copyInto(body, endIndex = 16)
            // SQLite 4096-byte pages, WAL-capable read/write versions, fixed
            // payload fractions required by SQLite file-format header.
            body[16] = 0x10
            body[17] = 0
            body[18] = 2
            body[19] = 2
            body[21] = 64
            body[22] = 32
            body[23] = 32
        }

    @Test fun existingSqliteAndJournalRemainByteForByteUntouched() {
        val db = database()
        val bytes = sqlite()
        db.writeBytes(bytes)
        val wal = File(db.path + "-wal")
        val shm = File(db.path + "-shm")
        val journal = File(db.path + "-journal")
        val walBytes = ByteArray(2053) { 0x52.toByte() }
        val shmBytes = ByteArray(512) { 0x27.toByte() }
        val journalBytes = ByteArray(257) { 0x36.toByte() }
        wal.writeBytes(walBytes); shm.writeBytes(shmBytes); journal.writeBytes(journalBytes)
        val snapshot = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        assertEquals(State.PLAINTEXT_SQLITE, snapshot.state)
        assertEquals(bytes.size.toLong(), snapshot.databaseBytes)
        assertEquals(walBytes.size.toLong(), snapshot.walBytes)
        assertEquals(shmBytes.size.toLong(), snapshot.sharedMemoryBytes)
        assertEquals(journalBytes.size.toLong(), snapshot.rollbackJournalBytes)
        assertTrue(snapshot.needsWalCheckpoint)
        assertTrue(snapshot.sufficientDiskSpace)
        assertTrue(snapshot.safeToBeginOfflineMigrationDesign)
        assertArrayEquals(bytes, db.readBytes())
        assertArrayEquals(walBytes, wal.readBytes())
        assertArrayEquals(shmBytes, shm.readBytes())
        assertArrayEquals(journalBytes, journal.readBytes())
    }

    @Test fun neverClaimsNonexistentOrOrphanedFilesAreSafeForMigration() {
        val db = database()
        val fresh = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        assertEquals(State.NEW_DATABASE, fresh.state)
        assertFalse(fresh.safeToBeginOfflineMigrationDesign)
        assertFalse(db.exists())
        File(db.path + "-wal").writeBytes(byteArrayOf(4, 5))
        val orphaned = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        assertEquals(State.ORPHANED_COMPANIONS, orphaned.state)
        assertFalse(orphaned.safeToBeginOfflineMigrationDesign)
        assertFalse(db.exists())
    }

    @Test fun unknownEncryptedOrTruncatedDbIsNeverTreatedAsPlaintext() {
        val db = database()
        for (bytes in listOf(
            byteArrayOf(0, 1, 2),
            ByteArray(8192) { 0x5c.toByte() },
            ByteArray(100)
        )) {
            db.writeBytes(bytes)
            val snapshot = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
            assertEquals(State.UNKNOWN_OR_TRUNCATED, snapshot.state)
            assertFalse(snapshot.safeToBeginOfflineMigrationDesign)
            assertArrayEquals(bytes, db.readBytes())
        }
    }

    @Test fun spaceMustCoverCompanionsAndSeparateSafeCopyPlusHeadroom() {
        val db = database()
        val bytes = sqlite()
        db.writeBytes(bytes)
        File(db.path + "-wal").writeBytes(ByteArray(8192))
        val required = 64L * 1024 * 1024 + 2L * 16384
        val failed = DatabaseEncryptionPreflight.inspect(db, required - 1)
        assertEquals(required, failed.conservativeRequiredBytes)
        assertFalse(failed.sufficientDiskSpace)
        assertFalse(failed.safeToBeginOfflineMigrationDesign)
        val passed = DatabaseEncryptionPreflight.inspect(db, required)
        assertTrue(passed.sufficientDiskSpace)
        assertTrue(passed.safeToBeginOfflineMigrationDesign)
        assertTrue(passed.needsWalCheckpoint)
        assertEquals(State.PLAINTEXT_SQLITE, passed.state)
    }

    @Test fun directoryOrUnsafeCompanionMustFailClosed() {
        val db = database()
        assertTrue(db.mkdir())
        assertEquals(State.UNREADABLE,
            DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024).state)
        assertTrue(db.delete())
        db.writeBytes(sqlite())
        assertTrue(File(db.path + "-wal").mkdir())
        val snapshot = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        assertEquals(State.UNREADABLE, snapshot.state)
        assertFalse(snapshot.safeToBeginOfflineMigrationDesign)
        assertArrayEquals(sqlite(), db.readBytes())
    }

    @Test fun forgedSqliteMagicAndInvalidPageLayoutsFailClosed() {
        val db = database()
        val badCases = listOf(
            sqlite(8192).also { it[16] = 0; it[17] = 3 }, // non power-of-two
            sqlite(8192).also { it[16] = 0; it[17] = 0 }, // invalid page size
            sqlite(8192).also { it[18] = 3 }, // unsupported write format
            sqlite(8192).also { it[19] = 0 }, // unsupported read format
            sqlite(8192).also { it[21] = 63 }, // payload fraction
            sqlite(8192).also { it[22] = 0 }, // payload fraction
            sqlite(8192).also { it[23] = 0 }, // payload fraction
            sqlite(8193), // partial final page
            sqlite(100), // magic alone is not a complete database
        )
        for (bytes in badCases) {
            db.writeBytes(bytes)
            val snapshot = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
            assertEquals(State.UNKNOWN_OR_TRUNCATED, snapshot.state)
            assertFalse(snapshot.safeToBeginOfflineMigrationDesign)
            assertArrayEquals(bytes, db.readBytes())
        }
    }

    @Test fun unknownAvailableSpaceNeverQualifiesAsSufficient() {
        val db = database()
        db.writeBytes(sqlite())
        val snapshot = DatabaseEncryptionPreflight.inspect(db, -1)
        assertFalse(snapshot.sufficientDiskSpace)
        assertFalse(snapshot.safeToBeginOfflineMigrationDesign)
    }

    @Test fun forgedReadinessSnapshotCannotBypassExactDiskAndWalEvidence() {
        val db = database()
        val original = sqlite()
        db.writeBytes(original)
        val baseline = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        assertTrue(baseline.safeToBeginOfflineMigrationDesign)
        val required = baseline.conservativeRequiredBytes

        for (forged in listOf(
            baseline.copy(databaseBytes = 511),
            baseline.copy(databaseBytes = -1),
            baseline.copy(walBytes = -1),
            baseline.copy(sharedMemoryBytes = -1),
            baseline.copy(rollbackJournalBytes = -1),
            baseline.copy(availableBytes = required - 1, sufficientDiskSpace = true),
            baseline.copy(sufficientDiskSpace = false),
            baseline.copy(conservativeRequiredBytes = required - 1),
            baseline.copy(conservativeRequiredBytes = Long.MAX_VALUE),
            baseline.copy(needsWalCheckpoint = true),
            baseline.copy(databaseBytes = Long.MAX_VALUE,
                conservativeRequiredBytes = Long.MAX_VALUE)
        )) {
            assertFalse("Forged or stale snapshot must not claim readiness: $forged",
                forged.safeToBeginOfflineMigrationDesign)
        }

        val forgedWal = baseline.copy(
            walBytes = 1024,
            conservativeRequiredBytes = DatabaseEncryptionPreflight.conservativeRequiredBytes(
                baseline.databaseBytes, 1024, 0, 0),
            needsWalCheckpoint = false
        )
        assertFalse(forgedWal.safeToBeginOfflineMigrationDesign)

        val wal = File(db.path + "-wal")
        val walData = ByteArray(1024) { 0x42.toByte() }
        wal.writeBytes(walData)
        val withWal = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        assertTrue(withWal.needsWalCheckpoint)
        assertTrue(withWal.safeToBeginOfflineMigrationDesign)
        assertArrayEquals(original, db.readBytes())
        assertArrayEquals(walData, wal.readBytes())
    }

    @Test fun reportCannotAccidentallyDiscloseContentPinOrDatabasePath() {
        val db = database()
        db.writeBytes(sqlite())
        val snapshot = DatabaseEncryptionPreflight.inspect(db, 128L * 1024 * 1024)
        val serialized = snapshot.toString()
        assertFalse(serialized.contains(files.root.absolutePath))
        assertFalse(serialized.contains("lanu_calculator_db"))
        assertFalse(serialized.contains("privateKey"))
        assertFalse(serialized.contains("pin_hash"))
    }
}
