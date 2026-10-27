package com.example

import com.example.core.database.DatabaseShadowParity
import org.junit.Assert.*
import org.junit.Test

/** Dummy rows only. Never opens or copies the owner's active Room database. */
class DatabaseShadowParityTest {
    private fun baseline(): List<DatabaseShadowParity.TableProof> =
        DatabaseShadowParity.TABLES.sorted().map { table ->
            val rows = when (table) {
                "messages" -> listOf(listOf("1", "dummy secret alpha"),
                    listOf("2", "dummy secret beta"))
                "contacts" -> listOf(listOf("1", "Test contact"))
                else -> emptyList()
            }
            DatabaseShadowParity.fingerprint(table, rows)
        }

    @Test fun equalRowsInDifferentSqlOrderPass() {
        val source = baseline()
        val reordered = source.reversed()
        assertTrue(DatabaseShadowParity.equivalent(source, reordered))
        val a = DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("1", "alpha"), listOf("2", "beta")))
        val b = DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("2", "beta"), listOf("1", "alpha")))
        assertEquals(a, b)
    }

    @Test fun missingDuplicateOrUnexpectedRoomTableFailsClosed() {
        val source = baseline()
        assertFalse(DatabaseShadowParity.equivalent(source, source.drop(1)))
        assertFalse(DatabaseShadowParity.equivalent(source, source + source.first()))
        assertFalse(DatabaseShadowParity.equivalent(source, source.dropLast(1) +
            source.first()))
        assertFalse(DatabaseShadowParity.equivalent(source,
            source.dropLast(1) + source.last().copy(table = "unknown")))
    }

    @Test fun nullEmptyChangedOrDuplicatedRowsCannotPass() {
        val a = DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("1", null), listOf("2", "")))
        val b = DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("1", ""), listOf("2", null)))
        assertNotEquals(a.sha256, b.sha256)
        val c = DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("1", null), listOf("1", null)))
        assertNotEquals(a.sha256, c.sha256)
        assertNotEquals(DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("ab", "c"))).sha256,
            DatabaseShadowParity.fingerprint("messages",
                listOf(listOf("a", "bc"))).sha256)
    }

    @Test fun editedValueAndUnsupportedEvidenceAreRejected() {
        val source = baseline()
        val modified = source.map {
            if (it.table == "messages")
                DatabaseShadowParity.fingerprint("messages", listOf(listOf("1", "changed")))
            else it
        }
        assertFalse(DatabaseShadowParity.equivalent(source, modified))
        try {
            DatabaseShadowParity.fingerprint("unknown", emptyList())
            fail("Unknown table accepted")
        } catch (_: IllegalArgumentException) {}
        assertFalse(DatabaseShadowParity.equivalent(source,
            source.map { it.copy(sha256 = "not-a-digest") }))
    }

    @Test fun legacyEvidenceCannotProcessMoreThanSixteenMiBOfPrivateData() {
        // The old string-only proof still runs on offline fixtures; it must
        // not silently hash 100,000 large rows and exhaust device memory.
        val oneMiB = "x".repeat(1_048_576)
        val withinBudget = List(16) { listOf(oneMiB) }
        assertEquals(16, DatabaseShadowParity.fingerprint(
            "messages", withinBudget).count)

        try {
            DatabaseShadowParity.fingerprint("messages",
                withinBudget + listOf(listOf(oneMiB)))
            fail("17 MiB of string evidence bypassed the aggregate budget")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun proofDoesNotContainDummyPrivateMessages() {
        val proof = DatabaseShadowParity.fingerprint("messages",
            listOf(listOf("Dummy PIN=1234", "Dummy secret content")))
        assertFalse(proof.toString().contains("1234"))
        assertFalse(proof.toString().contains("secret content"))
    }
}
