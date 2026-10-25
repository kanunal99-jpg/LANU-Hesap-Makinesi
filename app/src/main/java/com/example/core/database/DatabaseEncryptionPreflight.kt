package com.example.core.database

import android.content.Context
import java.io.File
import java.io.FileInputStream

/**
 * READ-ONLY evidence gate for a future lossless Room -> SQLCipher migration.
 *
 * This module deliberately does NOT open Room, checkpoint a WAL, copy/rename
 * data, create a key, configure SQLCipher, or run on application startup.
 * Migration cannot proceed until the app closes database handles, obtains an
 * exclusive SQLite checkpoint, performs an authenticated copy and verifies
 * tables + exact content on a separate test/restore path.
 *
 * Public results expose lengths and risk categories only, never a DB path,
 * PIN, table contents, private key, message or sensitive SQL.
 */
object DatabaseEncryptionPreflight {
    private const val DATABASE_FILE = "lanu_calculator_db"
    private const val MIN_SQLITE_BYTES = 512L
    private const val HEADROOM_BYTES = 64L * 1024 * 1024
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    enum class State {
        NEW_DATABASE,
        PLAINTEXT_SQLITE,
        UNKNOWN_OR_TRUNCATED,
        ORPHANED_COMPANIONS,
        UNREADABLE
    }

    data class Snapshot(
        val state: State,
        val databaseBytes: Long,
        val walBytes: Long,
        val sharedMemoryBytes: Long,
        val rollbackJournalBytes: Long,
        val availableBytes: Long,
        val conservativeRequiredBytes: Long,
        val sufficientDiskSpace: Boolean,
        val needsWalCheckpoint: Boolean
    ) {
        // This is only a readiness-to-investigate signal, never permission
        // to migrate or delete data in place.
        val safeToBeginOfflineMigrationDesign: Boolean
            get() = state == State.PLAINTEXT_SQLITE && sufficientDiskSpace
    }

    fun inspect(context: Context): Snapshot =
        inspect(context.getDatabasePath(DATABASE_FILE))

    /**
     * File metadata and the fixed SQLite 100-byte header only. A caller may
     * pass an explicit availableBytes value for deterministic low-disk tests.
     */
    fun inspect(database: File, availableBytes: Long =
        database.parentFile?.usableSpace ?: -1L
    ): Snapshot {
        val wal = File(database.path + "-wal")
        val shm = File(database.path + "-shm")
        val journal = File(database.path + "-journal")
        fun length(file: File): Long = if (file.isFile) file.length() else 0L
        val dbBytes = length(database)
        val walBytes = length(wal)
        val shmBytes = length(shm)
        val journalBytes = length(journal)
        val companions = listOf(wal, shm, journal)
        val orphaned = !database.exists() && companions.any(File::exists)
        val unsafeCompanion = companions.any { it.exists() && !it.isFile }
        val state = when {
            orphaned -> State.ORPHANED_COMPANIONS
            !database.exists() -> State.NEW_DATABASE
            !database.isFile || unsafeCompanion -> State.UNREADABLE
            dbBytes < MIN_SQLITE_BYTES -> State.UNKNOWN_OR_TRUNCATED
            else -> try {
                // A 16-byte magic marker can be forged or survive file corruption.
                // Validate immutable SQLite format markers and plausible page
                // boundaries, without reading any table, PIN or user content.
                val header = ByteArray(100)
                val read = FileInputStream(database).use { input ->
                    var n = 0
                    while (n < header.size) {
                        val count = input.read(header, n, header.size - n)
                        if (count <= 0) break
                        n += count
                    }
                    n
                }
                if (read == header.size && hasValidSqliteHeader(header, dbBytes)) {
                    State.PLAINTEXT_SQLITE
                } else State.UNKNOWN_OR_TRUNCATED
            } catch (_: Exception) {
                State.UNREADABLE
            }
        }
        val totalBytes = listOf(dbBytes, walBytes, shmBytes, journalBytes)
            .fold(0L) { acc, size -> safeAdd(acc, size) }
        val required = safeAdd(safeMultiply(totalBytes, 2L), HEADROOM_BYTES)
        val spaceOkay = availableBytes >= required && required != Long.MAX_VALUE

        return Snapshot(
            state = state,
            databaseBytes = dbBytes,
            walBytes = walBytes,
            sharedMemoryBytes = shmBytes,
            rollbackJournalBytes = journalBytes,
            availableBytes = availableBytes,
            conservativeRequiredBytes = required,
            sufficientDiskSpace = spaceOkay,
            needsWalCheckpoint = walBytes > 0L || shmBytes > 0L || journalBytes > 0L
        )
    }

    private fun hasValidSqliteHeader(header: ByteArray, fileBytes: Long): Boolean {
        if (!header.copyOfRange(0, SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)) {
            return false
        }
        val storedPageSize = ((header[16].toInt() and 0xff) shl 8) or
            (header[17].toInt() and 0xff)
        val pageSize = if (storedPageSize == 1) 65536 else storedPageSize
        if (pageSize !in 512..65536 || pageSize and (pageSize - 1) != 0 ||
            fileBytes < pageSize || fileBytes % pageSize != 0L) {
            return false
        }
        // SQLite: write/read format must be rollback-journal=1 or WAL=2.
        // Fixed per-page payload fractions are 64, 32 and 32.
        return (header[18].toInt() and 0xff) in 1..2 &&
            (header[19].toInt() and 0xff) in 1..2 &&
            (header[21].toInt() and 0xff) == 64 &&
            (header[22].toInt() and 0xff) == 32 &&
            (header[23].toInt() and 0xff) == 32
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (first < 0 || second < 0 || first > Long.MAX_VALUE - second) Long.MAX_VALUE
        else first + second

    private fun safeMultiply(value: Long, factor: Long): Long =
        if (value < 0 || value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
}
