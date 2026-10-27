package com.example

import com.example.core.database.DatabaseShadowParity
import com.example.core.database.OfflineCipherSchemaGate
import org.junit.Assert.*
import org.junit.Test

class OfflineCipherSchemaGateTest {
    private val id = OfflineCipherSchemaGate.Column("id", "INTEGER", true, 1)
    private val text = OfflineCipherSchemaGate.Column("value", "TEXT", false, 0)
    private fun proof(table: String, cols: List<OfflineCipherSchemaGate.Column> = listOf(id, text)) =
        OfflineCipherSchemaGate.fingerprint(table, cols)

    @Test fun allSevenTableSchemasMustBeIdentical() {
        val original = DatabaseShadowParity.TABLES.map { proof(it) }
        assertTrue(OfflineCipherSchemaGate.equivalent(original, original.reversed()))
        assertFalse(OfflineCipherSchemaGate.equivalent(original.dropLast(1), original))
        val changed = original.map {
            if (it.table == "messages") proof(it.table, listOf(id, text.copy(notNull = true))) else it
        }
        assertFalse(OfflineCipherSchemaGate.equivalent(original, changed))
        assertFalse(OfflineCipherSchemaGate.equivalent(original, original + original.first()))
    }

    @Test fun columnOrderTypePrimaryKeyAndNullabilityCannotBeHidden() {
        val base = proof("messages")
        for (variant in listOf(listOf(text, id),
            listOf(id, text.copy(affinity = "BLOB")),
            listOf(id, text.copy(primaryKeyOrder = 3)),
            listOf(id.copy(notNull = false), text))) {
            if (variant[1].primaryKeyOrder == 3) {
                try { proof("messages", variant); fail("Noncontiguous PK accepted") }
                catch (_: IllegalArgumentException) {}
            } else assertNotEquals(base.sha256, proof("messages", variant).sha256)
        }
        assertEquals(64, base.sha256.length)
    }

    @Test fun sqliteIdentifiersCannotDifferOnlyByCaseInShadowProof() {
        for (second in listOf("ID", "Id", "iD")) {
            try {
                proof("messages", listOf(id, text.copy(name = second)))
                fail("Case-insensitive SQLite duplicate column accepted: $second")
            } catch (_: IllegalArgumentException) { }
        }
        // Header validation must not accidentally depend on default system locale.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            try {
                proof("messages", listOf(id, text.copy(name = "ID")))
                fail("Locale-dependent column collision accepted")
            } catch (_: IllegalArgumentException) { }
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test fun malformedOrDuplicateColumnMetadataFailsClosed() {
        for (columns in listOf(emptyList(), listOf(id, id),
            listOf(id, text.copy(name = "private content")),
            listOf(id, text.copy(affinity = "UNKNOWN")))) {
            try { proof("messages", columns); fail("Invalid schema accepted") }
            catch (_: IllegalArgumentException) {}
        }
        try { proof("unknown"); fail("Unknown table accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
