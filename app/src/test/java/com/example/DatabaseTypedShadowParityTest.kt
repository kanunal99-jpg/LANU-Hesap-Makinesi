package com.example

import com.example.core.database.DatabaseShadowParity
import com.example.core.database.DatabaseShadowParity.SqlValue
import org.junit.Assert.*
import org.junit.Test

/** Synthetic SQLite values only; never opens a real owner database. */
class DatabaseTypedShadowParityTest {
    private fun hash(vararg values: SqlValue): String =
        DatabaseShadowParity.fingerprintTyped("messages", listOf(values.toList())).sha256

    @Test fun integerRealTextBlobAndNullAreDistinctEvenWhenDisplayedIdentically() {
        val variants = listOf(
            SqlValue.Integer(1),
            SqlValue.Real(1.0),
            SqlValue.Text("1"),
            SqlValue.Blob(byteArrayOf(49)),
            SqlValue.Null
        )
        assertEquals(variants.size, variants.map { hash(it) }.toSet().size)
        assertNotEquals(hash(SqlValue.Text("")), hash(SqlValue.Null))
        assertNotEquals(hash(SqlValue.Blob(byteArrayOf())), hash(SqlValue.Text("")))
    }

    @Test fun rowOrderingDoesNotChangeProofButColumnOrderAndDuplicateRowsDo() {
        val rows = listOf(
            listOf<SqlValue>(SqlValue.Integer(1), SqlValue.Text("one")),
            listOf<SqlValue>(SqlValue.Integer(2), SqlValue.Text("two"))
        )
        val a = DatabaseShadowParity.fingerprintTyped("messages", rows)
        assertEquals(a, DatabaseShadowParity.fingerprintTyped("messages", rows.reversed()))
        assertNotEquals(a.sha256, DatabaseShadowParity.fingerprintTyped(
            "messages", rows.map { it.reversed() }).sha256)
        assertNotEquals(a.sha256, DatabaseShadowParity.fingerprintTyped(
            "messages", rows + listOf(rows[0])).sha256)
    }

    @Test fun oneByteBlobChangeInvalidatesShadow() {
        val before = hash(SqlValue.Blob(byteArrayOf(0, 1, 2, 3)))
        val after = hash(SqlValue.Blob(byteArrayOf(0, 1, 2, 4)))
        assertNotEquals(before, after)
        assertFalse(before.contains("private"))
    }

    @Test fun exactSqliteRealRepresentationIsPreserved() {
        assertNotEquals(hash(SqlValue.Real(-0.0)), hash(SqlValue.Real(0.0)))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            try {
                hash(SqlValue.Real(invalid))
                fail("Nonfinite floating-point shadow evidence accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun totalEvidenceBudgetAndSingleValueLimitFailClosed() {
        val largeValue = "a".repeat(1_048_576)
        val entries = (1..17).map { listOf<SqlValue>(SqlValue.Text(largeValue)) }
        try {
            DatabaseShadowParity.fingerprintTyped("messages", entries)
            fail("17 MiB of synthetic shadow values bypassed total cap")
        } catch (_: IllegalArgumentException) {}
        try {
            hash(SqlValue.Blob(ByteArray(1_048_577)))
            fail("Oversize BLOB accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun typedAndLegacyStringProofsCannotCompareEqual() {
        val old = DatabaseShadowParity.fingerprint("messages", listOf(listOf("1")))
        val typed = DatabaseShadowParity.fingerprintTyped("messages",
            listOf(listOf<SqlValue>(SqlValue.Text("1"))))
        assertNotEquals(old.sha256, typed.sha256)
        assertEquals(1, typed.count)
        assertTrue(typed.sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
