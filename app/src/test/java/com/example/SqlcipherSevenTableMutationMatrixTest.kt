package com.example

import com.example.core.database.DatabaseShadowParity
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data ONLY. These hashes do not activate a real DB migration. */
class SqlcipherSevenTableMutationMatrixTest {
    private fun fixture() = DatabaseShadowParity.TABLES.sorted().map { name ->
        DatabaseShadowParity.fingerprint(name, listOf(
            listOf("row-1", null, "türkçe", "value"),
            listOf("row-2", "", "0", "payload")
        ))
    }

    @Test fun allSevenTablesAreRequiredAndRowOrderIsIrrelevant() {
        val before = fixture()
        val reversed = DatabaseShadowParity.TABLES.sorted().reversed().map { name ->
            DatabaseShadowParity.fingerprint(name, listOf(
                listOf("row-2", "", "0", "payload"),
                listOf("row-1", null, "türkçe", "value")
            ))
        }
        assertEquals(7, before.size)
        assertTrue(DatabaseShadowParity.equivalent(before, reversed))
        assertFalse(DatabaseShadowParity.equivalent(before, reversed.drop(1)))
        assertFalse(DatabaseShadowParity.equivalent(before, reversed + reversed.first()))
    }

    @Test fun oneChangedByteNullOrDuplicateMustBlockShadowAcceptance() {
        val baseline = fixture()
        for (name in DatabaseShadowParity.TABLES) {
            val modified = baseline.map { item ->
                if (item.table == name) DatabaseShadowParity.fingerprint(name, listOf(
                    listOf("row-1", "null", "türkçe", "value"),
                    listOf("row-2", "", "0", "payload")
                )) else item
            }
            assertFalse("A changed table escaped parity check: " + name,
                DatabaseShadowParity.equivalent(baseline, modified))
        }
        val first = baseline.first()
        assertFalse(DatabaseShadowParity.equivalent(baseline,
            baseline.drop(1) + first.copy(sha256 = "a".repeat(64))))
    }

    @Test fun malformedAndNegativeEvidenceAlwaysFailsClosed() {
        val baseline = fixture()
        assertFalse(DatabaseShadowParity.equivalent(
            baseline, baseline.mapIndexed { i, p -> if (i == 0) p.copy(count = -1) else p }))
        assertFalse(DatabaseShadowParity.equivalent(
            baseline, baseline.mapIndexed { i, p -> if (i == 0) p.copy(sha256 = "INVALID") else p }))
    }
}
