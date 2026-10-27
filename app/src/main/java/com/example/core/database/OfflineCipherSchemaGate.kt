package com.example.core.database

import java.security.MessageDigest
import java.util.Locale

/**
 * Schema-only proof for a future offline, encrypted Room v4 shadow.
 * No live database, key, backup, migration or SQL execution is performed.
 * Hashes are safe to compare; table cells or SQL statements never leave RAM.
 */
object OfflineCipherSchemaGate {
    data class Column(val name: String, val affinity: String, val notNull: Boolean, val primaryKeyOrder: Int)
    data class SchemaProof(val table: String, val sha256: String)
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val allowedAffinities = setOf("INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC")

    fun fingerprint(table: String, columns: List<Column>): SchemaProof {
        require(table in DatabaseShadowParity.TABLES) { "Unknown Room table" }
        require(columns.isNotEmpty() && columns.size <= 128) { "Invalid column count" }
        // SQLite treats ASCII identifier names case-insensitively. Allowing
        // both "message_id" and "MESSAGE_ID" as separate proof columns could
        // approve an impossible/ambiguous schema during an offline cutover.
        require(columns.map { it.name.lowercase(Locale.ROOT) }.toSet().size == columns.size) {
            "Duplicate SQLite column"
        }
        require(columns.all { it.name.matches(identifier) &&
            it.affinity in allowedAffinities && it.primaryKeyOrder in 0..128 }) { "Invalid column metadata" }
        val pk = columns.map { it.primaryKeyOrder }.filter { it > 0 }.sorted()
        require(pk == (1..pk.size).toList()) { "Noncontiguous primary key order" }
        val bytes = buildString {
            append("LANU-ROOM-SCHEMA-v1;").append(table).append(';')
            for (col in columns) {
                append(col.name.length).append(':').append(col.name).append(':')
                append(col.affinity).append(':').append(if (col.notNull) '1' else '0')
                append(':').append(col.primaryKeyOrder).append(';')
            }
        }.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return SchemaProof(table, digest.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun equivalent(source: List<SchemaProof>, shadow: List<SchemaProof>): Boolean {
        fun valid(proofs: List<SchemaProof>): Boolean =
            proofs.size == DatabaseShadowParity.TABLES.size &&
                proofs.map { it.table }.toSet() == DatabaseShadowParity.TABLES &&
                proofs.all { it.sha256.matches(Regex("[a-f0-9]{64}")) }
        return valid(source) && valid(shadow) &&
            source.associateBy { it.table } == shadow.associateBy { it.table }
    }
}
