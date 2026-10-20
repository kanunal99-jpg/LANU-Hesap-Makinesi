package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.CalculationHistoryEntity
import com.example.core.repository.CalculatorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalculatorHistoryRetentionTest {
    @Test fun `history always prunes to newest 50 even when timestamps tie`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.calculationHistoryDao()
            repeat(80) { i ->
                dao.insertAndPruneHistory(CalculationHistoryEntity(
                    expression = "expr-$i", result = "$i", timestamp = 12345L))
            }
            val rows = dao.getAllHistory().first()
            assertEquals(50, rows.size)
            assertEquals("expr-79", rows.maxByOrNull { it.id }?.expression)
            assertEquals("expr-30", rows.minByOrNull { it.id }?.expression)
        } finally { db.close() }
    }

    @Test fun `failed prune rolls back new insert and retains old history`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.calculationHistoryDao()
            repeat(50) { i ->
                dao.insertAndPruneHistory(CalculationHistoryEntity(
                    expression = "previous-$i", result = "$i", timestamp = i.toLong()))
            }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_prune BEFORE DELETE ON calculation_history " +
                    "BEGIN SELECT RAISE(FAIL, 'prune failed'); END;")
            try {
                dao.insertAndPruneHistory(CalculationHistoryEntity(
                    expression = "must-roll-back", result = "0", timestamp = 99999L))
                fail("Retention failure must rollback the entire transaction")
            } catch (_: Exception) { }
            val rows = dao.getAllHistory().first()
            assertEquals(50, rows.size)
            assertFalse(rows.any { it.expression == "must-roll-back" })
            assertTrue(rows.any { it.expression == "previous-0" })
        } finally { db.close() }
    }

    @Test fun `concurrent repository saves preserve strict history retention`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = CalculatorRepository(db.calculationHistoryDao(), db.settingsDao())
            coroutineScope {
                (0 until 90).map { i ->
                    async(Dispatchers.Default) { repo.saveToHistory("simultaneous-$i", "$i") }
                }.awaitAll()
            }
            assertEquals(50, db.calculationHistoryDao().getAllHistory().first().size)
        } finally { db.close() }
    }
}
