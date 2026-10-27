package com.example

import com.example.core.database.DatabaseShadowParity
import com.example.core.database.DatabaseShadowParity.SqlValue
import org.junit.Assert.*
import org.junit.Test

/** Synthetic Room evidence; never opens, migrates or deletes a real database. */
class DatabaseShadowTableDomainTest {
    @Test fun identicalRowsCannotReplayAHashAcrossDifferentTables() {
        val same = listOf(listOf<SqlValue>(
            SqlValue.Integer(13), SqlValue.Text("safe-fixture")))
        val first = DatabaseShadowParity.fingerprintTyped("messages", same)
        val second = DatabaseShadowParity.fingerprintTyped("contacts", same)
        assertNotEquals(first.sha256, second.sha256)
        assertEquals(first, DatabaseShadowParity.fingerprintTyped("messages", same))
        assertEquals(64, first.sha256.length)
    }
    @Test fun allSevenTablesStayBoundToUniqueDomainsEvenWhenEmpty() {
        val digests = DatabaseShadowParity.TABLES.map { table ->
            DatabaseShadowParity.fingerprintTyped(table, emptyList()).sha256
        }
        assertEquals(7, digests.toSet().size)
    }
}
