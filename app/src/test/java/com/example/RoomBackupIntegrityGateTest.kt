package com.example

import android.database.MatrixCursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.core.messaging.RoomBackupIntegrityGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Synthetic query results: no real user database or files are modified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomBackupIntegrityGateTest {
    private fun database(
        quickCheck: List<String?> = listOf("ok"),
        invalidForeignKey: Boolean = false
    ): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            check(method.name == "query") { "Read-only verification only" }
            when (args?.firstOrNull() as? String) {
                "PRAGMA quick_check(1)" ->
                    MatrixCursor(arrayOf("quick_check")).apply {
                        quickCheck.forEach { addRow(arrayOf(it)) }
                    }
                "PRAGMA foreign_key_check" ->
                    MatrixCursor(arrayOf("table", "rowid", "parent", "fkid")).apply {
                        if (invalidForeignKey) addRow(arrayOf("messages", 1, "conversations", 0))
                    }
                else -> error("Unexpected database operation")
            }
        } as SupportSQLiteDatabase

    @Test fun validDatabasePassesOnlyReadQueries() {
        RoomBackupIntegrityGate.requireHealthy(database())
    }

    @Test fun malformedOrMissingQuickCheckNeverProducesTrustedBackup() {
        for (status in listOf(
            emptyList(), listOf<String?>(null), listOf("corrupt index"),
            listOf("OK"), listOf("ok", "corrupt index")
        )) {
            try {
                RoomBackupIntegrityGate.requireHealthy(database(quickCheck = status))
                fail("Unhealthy SQLite must not pass")
            } catch (e: IllegalStateException) {
                assertEquals("Error must not disclose schema rows or paths",
                    "Yerel veritabanı bütünlüğü doğrulanamadı. Mevcut kayıtlar korunuyor; güvenli yedek oluşturulmadı.",
                    e.message)
                assertNull("Original internal SQL errors must not be chained", e.cause)
            }
        }
    }

    @Test fun foreignKeyViolationNeverProducesTrustedBackup() {
        try {
            RoomBackupIntegrityGate.requireHealthy(database(invalidForeignKey = true))
            fail("Missing parent row must fail backup")
        } catch (e: IllegalStateException) {
            assertFalse(e.message.orEmpty().contains("messages"))
            assertFalse(e.message.orEmpty().contains("conversations"))
        }
    }
}
