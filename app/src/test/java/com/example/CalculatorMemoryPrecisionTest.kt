package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.repository.CalculatorRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalculatorMemoryPrecisionTest {
    @Test fun `memory addition and subtraction never accumulate binary rounding errors`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = CalculatorRepository(db.calculationHistoryDao(), db.settingsDao())
            repo.setMemory(BigDecimal("0.1"))
            repo.addToMemory(BigDecimal("0.2"))
            assertEquals(0, repo.exactMemoryValue.value.compareTo(BigDecimal("0.3")))
            repo.subtractFromMemory(BigDecimal("0.1"))
            assertEquals(0, repo.exactMemoryValue.value.compareTo(BigDecimal("0.2")))
            repo.clearMemory()
            assertEquals(BigDecimal.ZERO, repo.exactMemoryValue.value)
        } finally { db.close() }
    }

    @Test fun `long decimal memory values retain all significant digits`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = CalculatorRepository(db.calculationHistoryDao(), db.settingsDao())
            val original = BigDecimal("123456789012345678.123456789012345678")
            repo.setMemory(original)
            repo.addToMemory(BigDecimal("0.000000000000000001"))
            assertEquals(BigDecimal("123456789012345678.123456789012345679"),
                repo.exactMemoryValue.value)
            // Legacy Double exposure is intentionally not a calculation source.
            assertTrue(repo.memoryValue.value > 0)
        } finally { db.close() }
    }

    @Test fun `legacy Double memory calls still work while using decimal arithmetic`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = CalculatorRepository(db.calculationHistoryDao(), db.settingsDao())
            repo.setMemory(0.1)
            repo.addToMemory(0.2)
            assertEquals(0, repo.exactMemoryValue.value.compareTo(BigDecimal("0.3")))
            repo.subtractFromMemory(0.3)
            assertEquals(BigDecimal.ZERO, repo.exactMemoryValue.value.stripTrailingZeros())
        } finally { db.close() }
    }
}
