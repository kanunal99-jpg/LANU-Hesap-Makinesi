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
        val required = 16L * 1024 * 1024 + 2L * 16384
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
