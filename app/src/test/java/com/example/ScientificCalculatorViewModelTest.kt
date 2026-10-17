package com.example

import com.example.core.calculator.HighPrecisionMathEngine
import com.example.core.database.CalculationHistoryDao
import com.example.core.database.CalculationHistoryEntity
import com.example.core.database.SettingsDao
import com.example.core.database.SettingsEntity
import com.example.core.repository.CalculatorRepository
import com.example.features.calculator.AngleUnit
import com.example.features.calculator.ScientificCalculatorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class ScientificCalculatorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fakeHistoryDao = object : CalculationHistoryDao {
        val historyList = mutableListOf<CalculationHistoryEntity>()

        override fun getAllHistory(): Flow<List<CalculationHistoryEntity>> = flowOf(historyList)
        override suspend fun insertHistory(history: CalculationHistoryEntity) {
            historyList.removeAll { it.id == history.id && history.id != 0L }
            historyList.add(history)
        }
        override suspend fun deleteHistory(id: Long) {
            historyList.removeAll { it.id == id }
        }
        override suspend fun clearAllHistory() {
            historyList.clear()
        }
        override suspend fun deleteHistoryOlderThan(timestamp: Long) {
            historyList.removeAll { it.timestamp < timestamp }
        }
        override suspend fun getHistoryIdsToPrune(): List<Long> {
            return historyList
                .sortedByDescending { it.timestamp }
                .drop(50)
                .map { it.id }
        }
        override suspend fun deleteHistoryByIds(ids: List<Long>) {
            historyList.removeAll { ids.contains(it.id) }
        }
    }

    private val fakeSettingsDao = object : SettingsDao {
        override suspend fun getSettingValue(key: String): String? = "16"
        override suspend fun insertSetting(setting: SettingsEntity) {}
        override suspend fun deleteSetting(key: String) {}
    }

    private lateinit var repository: CalculatorRepository
    private lateinit var viewModel: ScientificCalculatorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = CalculatorRepository(fakeHistoryDao, fakeSettingsDao)
        viewModel = ScientificCalculatorViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scientific display preserves values beyond Double range`() {
        val actual = HighPrecisionMathEngine.format(
            BigDecimal("1E+400"), precision = 4, scientificNotation = true
        )
        assertEquals("1.0000e+400", actual)
    }

    @Test
    fun `scientific display preserves significant decimal digits`() {
        val actual = HighPrecisionMathEngine.format(
            BigDecimal("123456789012345678901234567890"),
            precision = 18, scientificNotation = true
        )
        assertEquals("1.234567890123456789e+29", actual)
    }

    @Test
    fun `scientific display handles exponent rounding and negative tiny values`() {
        assertEquals(
            "1.000e+01",
            HighPrecisionMathEngine.format(BigDecimal("9.9999"), precision = 3, scientificNotation = true)
        )
        assertEquals(
            "-1.23e-12",
            HighPrecisionMathEngine.format(BigDecimal("-0.00000000000123"), precision = 2, scientificNotation = true)
        )
        assertEquals(
            "0",
            HighPrecisionMathEngine.format(BigDecimal.ZERO, precision = 2, scientificNotation = true)
        )
    }

    @Test
    fun `square root supports magnitudes beyond Double range`() {
        val huge = HighPrecisionMathEngine.sqrt(BigDecimal("1E+400"))
        val tiny = HighPrecisionMathEngine.sqrt(BigDecimal("1E-400"))
        assertEquals(0, huge.compareTo(BigDecimal("1E+200")))
        assertEquals(0, tiny.compareTo(BigDecimal("1E-200")))
    }

    @Test
    fun `square root retains precision after BigDecimal magnitude normalization`() {
        val root = HighPrecisionMathEngine.sqrt(BigDecimal("2"))
        val squared = root.multiply(root, HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT)
        assertTrue(squared.subtract(BigDecimal(2)).abs() <= BigDecimal("1E-32"))

        val larger = BigDecimal("9E+400")
        assertEquals(0, HighPrecisionMathEngine.sqrt(larger).compareTo(BigDecimal("3E+200")))
    }

    @Test
    fun `cube root supports magnitudes beyond Double range`() {
        val huge = HighPrecisionMathEngine.cbrt(BigDecimal("8E+600"))
        val tiny = HighPrecisionMathEngine.cbrt(BigDecimal("1E-600"))
        assertEquals(0, huge.compareTo(BigDecimal("2E+200")))
        assertEquals(0, tiny.compareTo(BigDecimal("1E-200")))
    }

    @Test
    fun `cube root supports negative values without floating point conversion`() {
        assertEquals(
            0,
            HighPrecisionMathEngine.cbrt(BigDecimal("-8E+600")).compareTo(BigDecimal("-2E+200"))
        )
        assertEquals(0, HighPrecisionMathEngine.cbrt(BigDecimal("-27")).compareTo(BigDecimal("-3")))
        assertEquals(0, HighPrecisionMathEngine.cbrt(BigDecimal.ZERO).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `cube root preserves 34-digit precision for irrational values`() {
        val root = HighPrecisionMathEngine.cbrt(BigDecimal("2"))
        val cubed = root.multiply(root, HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT)
            .multiply(root, HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT)
        assertTrue(cubed.subtract(BigDecimal(2)).abs() <= BigDecimal("1E-32"))
    }

    @Test
    fun `integer power rejects out of range exponents before Int conversion`() {
        for (badExponent in listOf("10001", "-10001", "2147483648", "-2147483649", "1E+100")) {
            try {
                HighPrecisionMathEngine.pow(BigDecimal(2), BigDecimal(badExponent))
                org.junit.Assert.fail("Expected safe rejection for $badExponent")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("range"))
            }
        }
    }

    @Test
    fun `integer power keeps negative bases and reciprocal results correct`() {
        assertEquals(0, HighPrecisionMathEngine.pow(BigDecimal(-2), BigDecimal(3)).compareTo(BigDecimal(-8)))
        assertEquals(0, HighPrecisionMathEngine.pow(BigDecimal(2), BigDecimal(-3)).compareTo(BigDecimal("0.125")))
        assertEquals(0, HighPrecisionMathEngine.pow(BigDecimal(2), BigDecimal(0)).compareTo(BigDecimal.ONE))
    }

    @Test
    fun `expression parser also rejects overflowing exponent without wrapping`() {
        try {
            HighPrecisionMathEngine.evaluate("2^2147483648")
            org.junit.Assert.fail("Overflowing exponent must not wrap to a negative Int")
        } catch (expected: ArithmeticException) {
            assertTrue(expected.message.orEmpty().contains("range"))
        }
    }

    @Test
    fun `high precision engine solves decimal addition without floating point artifacts`() {
        val result = HighPrecisionMathEngine.evaluate("0.1 + 0.2")
        assertEquals("0.3", result.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `high precision engine evaluates exact degree trigonometric values`() {
        val sin90 = HighPrecisionMathEngine.evaluate("sin(90)", isDegrees = true)
        val cos90 = HighPrecisionMathEngine.evaluate("cos(90)", isDegrees = true)
        val sin30 = HighPrecisionMathEngine.evaluate("sin(30)", isDegrees = true)
        val tan45 = HighPrecisionMathEngine.evaluate("tan(45)", isDegrees = true)

        assertEquals("1", sin90.stripTrailingZeros().toPlainString())
        assertEquals("0", cos90.stripTrailingZeros().toPlainString())
        assertEquals("0.5", sin30.stripTrailingZeros().toPlainString())
        assertEquals("1", tan45.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `high precision engine evaluates factorials and powers accurately`() {
        val fact5 = HighPrecisionMathEngine.evaluate("5!")
        val pow2_10 = HighPrecisionMathEngine.evaluate("2^10")

        assertEquals("120", fact5.stripTrailingZeros().toPlainString())
        assertEquals("1024", pow2_10.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `viewModel maintains state on digit and operator inputs`() {
        viewModel.inputDigit("5")
        viewModel.inputOperator("+")
        viewModel.inputDigit("3")

        assertEquals("5+3", viewModel.uiState.value.expression)
        assertEquals("8", viewModel.uiState.value.result)
        assertTrue(viewModel.uiState.value.hasActiveCalculation)
    }

    @Test
    fun `viewModel calculate evaluates expression and sets new state`() {
        viewModel.appendExpression("15*4")
        val computed = viewModel.calculate(saveHistory = false)

        assertNotNull(computed)
        assertEquals("60", computed?.stripTrailingZeros()?.toPlainString())
        assertEquals("60", viewModel.uiState.value.result)
        assertEquals("60", viewModel.uiState.value.primaryDisplay)
    }

    @Test
    fun `viewModel allClear resets calculator operation state`() {
        viewModel.inputDigit("9")
        viewModel.inputOperator("×")
        viewModel.inputDigit("9")
        viewModel.calculate(saveHistory = false)

        viewModel.allClear()

        assertEquals("", viewModel.uiState.value.expression)
        assertEquals("", viewModel.uiState.value.result)
        assertEquals("0", viewModel.uiState.value.primaryDisplay)
        assertEquals(false, viewModel.uiState.value.hasActiveCalculation)
    }

    @Test
    fun `viewModel maintains angleUnit and toggles accurately`() {
        assertEquals(AngleUnit.DEGREES, viewModel.uiState.value.angleUnit)
        assertTrue(viewModel.uiState.value.isDegrees)

        viewModel.toggleDegrees()

        assertEquals(AngleUnit.RADIANS, viewModel.uiState.value.angleUnit)
        assertEquals(false, viewModel.uiState.value.isDegrees)
    }

    @Test
    fun `viewModel memory operations update high precision state`() {
        viewModel.appendExpression("25")
        viewModel.memoryStore()

        assertEquals(0, BigDecimal("25").compareTo(viewModel.uiState.value.highPrecisionMemory))

        viewModel.allClear()
        viewModel.memoryRecall()

        assertEquals("25", viewModel.uiState.value.expression)
    }
}
