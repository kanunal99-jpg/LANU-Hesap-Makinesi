package com.example.core.database

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * In-memory, synthetic/offline acceptance proof for a future SQLCipher shadow.
 * It does NOT connect to Room, open a SQLite file, checkpoint WAL, create keys,
 * perform a migration or approve switching the live database.
 *
 * Every row value is length-prefixed and hashed; row hashes are sorted before
 * final hashing so differing SQL row iteration order cannot hide missing,
 * duplicate, swapped, NULL or modified records. Callers must capture the exact
 * same columns in the same order from independent frozen snapshots.
 */
object DatabaseShadowParity {
    val TABLES: Set<String> = setOf(
        "calculation_history", "user_profiles", "app_settings", "contacts",
        "conversations", "messages", "call_logs"
    )

    data class TableProof(val table: String, val count: Int, val sha256: String)

    fun fingerprint(table: String, rows: List<List<String?>>): TableProof {
        require(table in TABLES) { "Unsupported table in shadow evidence" }
        require(rows.size <= 100_000) { "Shadow evidence row limit exceeded" }
        val hashes = rows.map { row ->
            require(row.size <= 128) { "Shadow evidence column limit exceeded" }
            val md = MessageDigest.getInstance("SHA-256")
            addInt(md, row.size)
            row.forEach { value ->
                if (value == null) addInt(md, -1)
                else {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 1_048_576) { "Shadow evidence value limit exceeded" }
                    addInt(md, bytes.size)
                    md.update(bytes)
                }
            }
            md.digest().toHex()
        }.sorted()
        val total = MessageDigest.getInstance("SHA-256")
        addInt(total, rows.size)
        hashes.forEach { hash -> total.update(hash.toByteArray(Charsets.US_ASCII)) }
        return TableProof(table, rows.size, total.digest().toHex())
    }

    /**
     * Typed SQLite v4 shadow evidence. The older string-only fingerprint is
     * retained for compatibility with existing offline fixtures, but MUST NOT
     * be used to approve an actual SQLite/SQLCipher migration: INTEGER 1,
     * TEXT "1", REAL 1.0 and BLOB 0x31 are different SQLite cell types.
     *
     * No database is opened or mutated. Only bounded, fixed-length digests
     * leave this function; source values must never be logged.
     */
    sealed class SqlValue {
        object Null : SqlValue()
        data class Integer(val value: Long) : SqlValue()
        data class Real(val value: Double) : SqlValue()
        data class Text(val value: String) : SqlValue()
        data class Blob(val value: ByteArray) : SqlValue()
    }

    fun fingerprintTyped(table: String, rows: List<List<SqlValue>>): TableProof {
        require(table in TABLES) { "Unsupported table in typed shadow evidence" }
        require(rows.size <= 100_000) { "Shadow evidence row limit exceeded" }
        var aggregateBytes = 0L
        val rowHashes = rows.map { row ->
            require(row.size <= 128) { "Shadow evidence column limit exceeded" }
            val md = MessageDigest.getInstance("SHA-256")
            addInt(md, row.size)
            for (cell in row) {
                when (cell) {
                    is SqlValue.Null -> md.update(0.toByte())
                    is SqlValue.Integer -> {
                        md.update(1.toByte())
                        md.update(ByteBuffer.allocate(8).putLong(cell.value).array())
                    }
                    is SqlValue.Real -> {
                        require(cell.value.isFinite()) { "Non-finite SQL real evidence" }
                        md.update(2.toByte())
                        md.update(ByteBuffer.allocate(8)
                            .putLong(java.lang.Double.doubleToRawLongBits(cell.value)).array())
                    }
                    is SqlValue.Text -> {
                        md.update(3.toByte())
                        val bytes = cell.value.toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 1_048_576) { "Shadow evidence value limit exceeded" }
                        aggregateBytes += bytes.size
                        require(aggregateBytes <= 16L * 1024 * 1024) { "Shadow evidence total limit exceeded" }
                        addInt(md, bytes.size)
                        md.update(bytes)
                    }
                    is SqlValue.Blob -> {
                        md.update(4.toByte())
                        require(cell.value.size <= 1_048_576) { "Shadow evidence value limit exceeded" }
                        aggregateBytes += cell.value.size
                        require(aggregateBytes <= 16L * 1024 * 1024) { "Shadow evidence total limit exceeded" }
                        addInt(md, cell.value.size)
                        md.update(cell.value)
                    }
                }
            }
            md.digest().toHex()
        }.sorted()
        val total = MessageDigest.getInstance("SHA-256")
        // Version/domain separation: legacy string proofs cannot pass typed parity.
        total.update("LANU-SQLITE-TYPED-v1".toByteArray(Charsets.US_ASCII))
        // Bind the proof to its named Room v4 table. Identical row bytes
        // copied into another table must never replay the same typed digest.
        total.update(table.toByteArray(Charsets.UTF_8))
        addInt(total, rows.size)
        rowHashes.forEach { total.update(it.toByteArray(Charsets.US_ASCII)) }
        return TableProof(table, rows.size, total.digest().toHex())
    }

    /** Fail closed if any of the exact seven Room v4 tables is missing/duplicated. */
    fun equivalent(original: List<TableProof>, shadow: List<TableProof>): Boolean {
        fun valid(proofs: List<TableProof>): Boolean =
            proofs.size == TABLES.size &&
            proofs.map { it.table }.toSet() == TABLES &&
            proofs.all { it.count in 0..100_000 && it.sha256.matches(Regex("[0-9a-f]{64}")) }
        if (!valid(original) || !valid(shadow)) return false
        return original.associateBy { it.table } == shadow.associateBy { it.table }
    }

    private fun addInt(md: MessageDigest, value: Int) =
        md.update(ByteBuffer.allocate(4).putInt(value).array())

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
