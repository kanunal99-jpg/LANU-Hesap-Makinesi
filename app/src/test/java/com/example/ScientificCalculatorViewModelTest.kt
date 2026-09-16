package com.example

import com.example.core.calculator.HighPrecisionMathEngine
import com.example.core.database.CalculationHistoryDao
import com.example.core.database.CalculationHistoryEntity
import com.example.core.database.SettingsDao
import com.example.core.database.SettingsEntity
import com.example.core.repository.CalculatorRepository
import com.example.features.calculator.AngleUnit
import com.example.features.calculator.NotationMode
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
        override suspend fun insertHistory(history: CalculationHistoryEntity) { historyList.add(history) }
        override suspend fun deleteHistory(id: Long) { historyList.removeAll { it.id == id } }
        override suspend fun clearAllHistory() { historyList.clear() }
        override suspend fun deleteHistoryOlderThan(timestamp: Long) {}
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
