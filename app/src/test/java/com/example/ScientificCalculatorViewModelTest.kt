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
    fun `extreme decimal trig exponents cannot expand into huge intermediate allocations`() {
        val extremes = listOf(
            "1E+2147483647", "-1E+2147483647",
            "1E-2147483647", "-1E-2147483647"
        )
        val contexts = listOf(
            HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT,
            java.math.MathContext(100, java.math.RoundingMode.HALF_UP)
        )
        for (input in extremes) {
            val angle = BigDecimal(input)
            for (degrees in listOf(true, false)) {
                for (context in contexts) {
                    for (operation in listOf<(BigDecimal, Boolean, java.math.MathContext) -> BigDecimal>(
                        HighPrecisionMathEngine::sin,
                        HighPrecisionMathEngine::cos,
                        HighPrecisionMathEngine::tan
                    )) {
                        try {
                            operation(angle, degrees, context)
                            org.junit.Assert.fail("Expected bounded trig rejection: $input")
                        } catch (expected: ArithmeticException) {
                            assertTrue(expected.message.orEmpty().contains("Trigonometric angle exponent"))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `extreme trig expressions fail safely but ordinary angles are unchanged`() {
        val extremes = listOf("sin(1E+2147483647)", "cos(1E-2147483647)",
            "tan(-1E+2147483647)")
        for (expression in extremes) {
            try {
                HighPrecisionMathEngine.evaluate(expression)
                org.junit.Assert.fail("Resource-exhausting expression must be rejected")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("Trigonometric angle exponent"))
            }
        }
        assertEquals(BigDecimal.ZERO, HighPrecisionMathEngine.sin(BigDecimal.ZERO))
        assertEquals(BigDecimal.ONE, HighPrecisionMathEngine.cos(BigDecimal.ZERO))
        assertEquals(BigDecimal.ZERO, HighPrecisionMathEngine.tan(BigDecimal.ZERO))
        assertEquals(BigDecimal.ONE, HighPrecisionMathEngine.sin(BigDecimal(90)))
    }

    @Test
    fun `extreme sqrt and cbrt exponents reject without BigDecimal expansion`() {
        for (input in listOf("1E+2147483647", "1E-2147483647",
            "-1E+2147483647", "-1E-2147483647")) {
            val n = BigDecimal(input)
            if (n.signum() > 0) {
                try {
                    HighPrecisionMathEngine.sqrt(n)
                    org.junit.Assert.fail("Extreme square root was accepted")
                } catch (expected: ArithmeticException) {
                    assertTrue(expected.message.orEmpty().contains("resource budget"))
                }
            }
            try {
                HighPrecisionMathEngine.cbrt(n)
                org.junit.Assert.fail("Extreme cube root was accepted")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("resource budget"))
            }
        }
        assertEquals(0, HighPrecisionMathEngine.sqrt(BigDecimal("1E+400"))
            .compareTo(BigDecimal("1E+200")))
        assertEquals(0, HighPrecisionMathEngine.cbrt(BigDecimal("1E+300"))
            .compareTo(BigDecimal("1E+100")))
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
    fun `exponential rejects overflowing arguments before Int conversion`() {
        for (invalid in listOf("10000.01", "-10000.01", "2147483648", "-2147483649", "1E+100")) {
            try {
                HighPrecisionMathEngine.exp(BigDecimal(invalid))
                org.junit.Assert.fail("Expected safe rejection for $invalid")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("range"))
            }
        }
    }

    @Test
    fun `exponential retains small integer and fractional calculations`() {
        assertEquals(0, HighPrecisionMathEngine.exp(BigDecimal.ZERO).compareTo(BigDecimal.ONE))
        val positive = HighPrecisionMathEngine.exp(BigDecimal.ONE)
        val negative = HighPrecisionMathEngine.exp(BigDecimal("-1"))
        val product = positive.multiply(negative, HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT)
        assertTrue(product.subtract(BigDecimal.ONE).abs() <= BigDecimal("1E-32"))
        assertTrue(HighPrecisionMathEngine.exp(BigDecimal("0.5")) > BigDecimal.ONE)
    }

    @Test
    fun `expression parser cannot wrap a huge exp argument`() {
        try {
            HighPrecisionMathEngine.evaluate("exp(2147483648)")
            org.junit.Assert.fail("exp must not silently truncate the argument")
        } catch (expected: ArithmeticException) {
            assertTrue(expected.message.orEmpty().contains("range"))
        }
    }

    @Test
    fun `logarithm handles very large values beyond Double range`() {
        val result = HighPrecisionMathEngine.log10(BigDecimal("1E+400"))
        assertTrue(result.subtract(BigDecimal("400")).abs() <= BigDecimal("1E-27"))
    }

    @Test
    fun `logarithm handles extremely small positive values without underflow`() {
        val result = HighPrecisionMathEngine.log10(BigDecimal("1E-400"))
        assertTrue(result.subtract(BigDecimal("-400")).abs() <= BigDecimal("1E-27"))
    }

    @Test
    fun `logarithm keeps normal values accurate`() {
        assertEquals(0, HighPrecisionMathEngine.ln(BigDecimal.ONE).compareTo(BigDecimal.ZERO))
        val actual = HighPrecisionMathEngine.ln(BigDecimal("2"))
        val expected = BigDecimal("0.693147180559945309417232121458176568")
        assertTrue(actual.subtract(expected).abs() <= BigDecimal("1E-29"))
    }

    @Test
    fun `logarithm rejects invalid and unsupported extreme input`() {
        for (value in listOf("0", "-1", "1E+4001", "1E-4001")) {
            try {
                HighPrecisionMathEngine.ln(BigDecimal(value))
                org.junit.Assert.fail("ln must reject: $value")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("Logarithm"))
            }
        }
    }

    @Test
    fun `inverse tangent handles values much larger than Double can represent`() {
        val positive = HighPrecisionMathEngine.atan(BigDecimal("1E+400"))
        val negative = HighPrecisionMathEngine.atan(BigDecimal("-1E+400"))
        assertTrue(positive.subtract(BigDecimal(90)).abs() <= BigDecimal("1E-28"))
        assertTrue(negative.add(BigDecimal(90)).abs() <= BigDecimal("1E-28"))
    }

    @Test
    fun `inverse tangent reduces ordinary large arguments without changing accuracy`() {
        val actual = HighPrecisionMathEngine.atan(BigDecimal(2), isDegrees = false)
        val expected = BigDecimal("1.107148717794090503017065460178537")
        assertTrue(actual.subtract(expected).abs() <= BigDecimal("1E-28"))
        val negative = HighPrecisionMathEngine.atan(BigDecimal(-2), isDegrees = false)
        assertTrue(negative.add(actual).abs() <= BigDecimal("1E-30"))
    }

    @Test
    fun `inverse tangent stays stable for zero and sub Double range inputs`() {
        assertEquals(0, HighPrecisionMathEngine.atan(BigDecimal.ZERO).compareTo(BigDecimal.ZERO))
        val tiny = BigDecimal("1E-400")
        val tinyRadians = HighPrecisionMathEngine.atan(tiny, isDegrees = false)
        assertTrue(tinyRadians.subtract(tiny).abs() <= BigDecimal("1E-430"))
    }

    @Test
    fun `inverse trig endpoints retain requested 80 digit precision`() {
        val context = java.math.MathContext(80, java.math.RoundingMode.HALF_UP)
        val halfPi = HighPrecisionMathEngine.PI.divide(BigDecimal(2), context)
        val asinPositive = HighPrecisionMathEngine.asin(BigDecimal.ONE, isDegrees = false, mc = context)
        val asinNegative = HighPrecisionMathEngine.asin(BigDecimal("-1"), isDegrees = false, mc = context)
        assertEquals(0, asinPositive.compareTo(halfPi))
        assertEquals(0, asinNegative.compareTo(halfPi.negate()))
        assertTrue(asinPositive.precision() > 70)
    }

    @Test
    fun `inverse cosine endpoints respect high precision context`() {
        val context = java.math.MathContext(80, java.math.RoundingMode.HALF_UP)
        assertEquals(0, HighPrecisionMathEngine.acos(BigDecimal.ONE, isDegrees = false, mc = context).compareTo(BigDecimal.ZERO))
        val actual = HighPrecisionMathEngine.acos(BigDecimal("-1"), isDegrees = false, mc = context)
        val expected = HighPrecisionMathEngine.PI.round(context)
        assertTrue(actual.subtract(expected).abs() <= BigDecimal("1E-78"))
    }

    @Test
    fun `large argument atan uses high precision half pi`() {
        val context = java.math.MathContext(80, java.math.RoundingMode.HALF_UP)
        val expected = HighPrecisionMathEngine.PI.divide(BigDecimal(2), context)
        val actual = HighPrecisionMathEngine.atan(BigDecimal("1E+400"), isDegrees = false, mc = context)
        assertTrue(actual.subtract(expected).abs() <= BigDecimal("1E-78"))
        assertTrue(actual.precision() > 70)
    }

    @Test
    fun `scientific e and pi constants work with case insensitive input`() {
        assertEquals(0, HighPrecisionMathEngine.evaluate("e").compareTo(HighPrecisionMathEngine.E))
        assertEquals(0, HighPrecisionMathEngine.evaluate("E").compareTo(HighPrecisionMathEngine.E))
        assertEquals(0, HighPrecisionMathEngine.evaluate("pi").compareTo(HighPrecisionMathEngine.PI))
        assertEquals(0, HighPrecisionMathEngine.evaluate("π").compareTo(HighPrecisionMathEngine.PI))
        assertEquals(0, HighPrecisionMathEngine.evaluate("e+1").compareTo(HighPrecisionMathEngine.E.add(BigDecimal.ONE, HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT)))
    }

    @Test
    fun `graph variable recognizer ignores the exp function name`() {
        assertEquals(false, HighPrecisionMathEngine.containsGraphVariable("exp(1)"))
        assertEquals(false, HighPrecisionMathEngine.containsGraphVariable("exp(2)+PI"))
        assertEquals(false, HighPrecisionMathEngine.containsGraphVariable("1E+6"))
        assertEquals(true, HighPrecisionMathEngine.containsGraphVariable("x^2+1"))
        assertEquals(true, HighPrecisionMathEngine.containsGraphVariable("2X+1"))
        assertEquals(true, HighPrecisionMathEngine.containsGraphVariable("sin(x)"))
    }

    @Test
    fun `viewModel calculates exp instead of erroneously entering graph mode`() {
        viewModel.appendExpression("exp(1)")
        assertEquals(false, HighPrecisionMathEngine.containsGraphVariable(viewModel.uiState.value.expression))
        val computed = viewModel.calculate(saveHistory = false)
        assertNotNull(computed)
        assertTrue(computed!! > BigDecimal("2.7"))
        assertTrue(viewModel.uiState.value.result != "f(x) Grafik Çizimi Aktif 📈")
    }

    @Test
    fun `implicit multiplication handles pi parentheses and functions`() {
        val mc = HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT
        val twoPi = HighPrecisionMathEngine.PI.multiply(BigDecimal(2), mc)
        assertEquals(0, HighPrecisionMathEngine.evaluate("2π").compareTo(twoPi))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2PI").compareTo(twoPi))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2(3+4)").compareTo(BigDecimal(14)))
        assertEquals(0, HighPrecisionMathEngine.evaluate("(1+2)(3+4)").compareTo(BigDecimal(21)))
        assertTrue(HighPrecisionMathEngine.evaluate("2sin(30)").subtract(BigDecimal.ONE).abs() < BigDecimal("1E-28"))
    }

    @Test
    fun `euler constant remains distinct from scientific exponent notation`() {
        val mc = HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT
        val twoE = HighPrecisionMathEngine.E.multiply(BigDecimal(2), mc)
        assertEquals(0, HighPrecisionMathEngine.evaluate("2e").compareTo(twoE))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2E+3").compareTo(BigDecimal("2000")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2e-3").compareTo(BigDecimal("0.002")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2exp(0)").compareTo(BigDecimal(2)))
    }

    @Test
    fun `explicit arithmetic precedence survives implicit multiplication`() {
        assertEquals(0, HighPrecisionMathEngine.evaluate("2+3(4+1)").compareTo(BigDecimal(17)))
        assertEquals(0, HighPrecisionMathEngine.evaluate("20/2(2)").compareTo(BigDecimal(20)))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2^3(2)").compareTo(BigDecimal(16)))
    }

    @Test
    fun `radian tangent at PI over two rejects numerical poles`() {
        val mc = HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT
        val halfPi = HighPrecisionMathEngine.PI.divide(BigDecimal(2), mc)
        for (pole in listOf(halfPi, halfPi.negate())) {
            try {
                HighPrecisionMathEngine.tan(pole, isDegrees = false)
                org.junit.Assert.fail("Tangent must not return a fabricated finite value at PI/2")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("Tangent"))
            }
        }
    }

    @Test
    fun `degree tangent retains exact undefined ninety degrees`() {
        try {
            HighPrecisionMathEngine.tan(BigDecimal("90"), isDegrees = true)
            org.junit.Assert.fail("Tangent 90 degrees is undefined")
        } catch (expected: ArithmeticException) {
            assertTrue(expected.message.orEmpty().contains("Tangent"))
        }
    }

    @Test
    fun `ordinary tangent values remain defined`() {
        val mc = HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT
        val quarterPi = HighPrecisionMathEngine.PI.divide(BigDecimal(4), mc)
        val result = HighPrecisionMathEngine.tan(quarterPi, isDegrees = false)
        assertTrue(result.subtract(BigDecimal.ONE).abs() <= BigDecimal("1E-27"))
        val fortyFive = HighPrecisionMathEngine.tan(BigDecimal("45"), isDegrees = true)
        assertTrue(fortyFive.subtract(BigDecimal.ONE).abs() <= BigDecimal("1E-27"))
    }

    @Test
    fun `tiny nonzero display never rounds to the string zero`() {
        assertEquals(
            "1.0000e-400",
            HighPrecisionMathEngine.format(BigDecimal("1E-400"), precision = 4)
        )
        assertEquals(
            "-1.0000e-400",
            HighPrecisionMathEngine.format(BigDecimal("-1E-400"), precision = 4)
        )
        assertEquals(
            "4.0000e-03",
            HighPrecisionMathEngine.format(BigDecimal("0.004"), precision = 4, scientificNotation = true)
        )
    }

    @Test
    fun `extremely large or small scales avoid huge plain decimal strings`() {
        assertEquals("1E+1000000", HighPrecisionMathEngine.format(BigDecimal("1E+1000000"), precision = 3))
        assertEquals("1E-1000000", HighPrecisionMathEngine.format(BigDecimal("1E-1000000"), precision = 3))
    }

    @Test
    fun `ordinary decimal display stays readable and rejects negative precision`() {
        assertEquals("0.12", HighPrecisionMathEngine.format(BigDecimal("0.123456"), precision = 2))
        assertEquals("0", HighPrecisionMathEngine.format(BigDecimal.ZERO, precision = 4))
        try {
            HighPrecisionMathEngine.format(BigDecimal.ZERO, precision = -1)
            org.junit.Assert.fail("Negative display precision should be rejected even for zero")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Precision"))
        }
    }

    @Test
    fun `nested parentheses reject before a stack overflow`() {
        val expression = "(".repeat(140) + "1" + ")".repeat(140)
        try {
            HighPrecisionMathEngine.evaluate(expression)
            org.junit.Assert.fail("Deep parenthesis nesting should be bounded")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("nesting"))
        }
    }

    @Test
    fun `deep unary and exponent recursion has a safe limit`() {
        val deepSigns = "-".repeat(140) + "1"
        val deepPowers = "2^".repeat(140) + "1"
        for (expression in listOf(deepSigns, deepPowers)) {
            try {
                HighPrecisionMathEngine.evaluate(expression)
                org.junit.Assert.fail("Recursive expression must be rejected safely")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("nesting"))
            }
        }
    }

    @Test
    fun `oversized pasted expression fails without costly parsing`() {
        try {
            HighPrecisionMathEngine.evaluate("1+".repeat(4200) + "1")
            org.junit.Assert.fail("Input over 8192 characters should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("length"))
        }
    }

    @Test
    fun `ordinary nested and flat expressions still work`() {
        val nested = "(".repeat(32) + "2+3" + ")".repeat(32)
        assertEquals(0, HighPrecisionMathEngine.evaluate(nested).compareTo(BigDecimal("5")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("1+2+3+4").compareTo(BigDecimal("10")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("2^3^2").compareTo(BigDecimal("512")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("--3").compareTo(BigDecimal("3")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("---3").compareTo(BigDecimal("-3")))
        assertEquals(0, HighPrecisionMathEngine.evaluate("-2^2").compareTo(BigDecimal("-4")))
    }

    @Test
    fun `mod operator accepts pasted uppercase and mixed case expressions`() {
        for (expression in listOf("10mod3", "10MOD3", "10MoD3")) {
            assertEquals(0, HighPrecisionMathEngine.evaluate(expression).compareTo(BigDecimal.ONE))
        }
        assertEquals(0, HighPrecisionMathEngine.evaluate("12 MOD 5").compareTo(BigDecimal(2)))
    }

    @Test
    fun `mod is not replaced inside unrelated function or identifier text`() {
        assertEquals("12mode", HighPrecisionMathEngine.sanitize("12mode"))
        assertEquals("modern(2)", HighPrecisionMathEngine.sanitize("modern(2)"))
        assertEquals("12%5", HighPrecisionMathEngine.sanitize("12MoD5"))
    }

    @Test
    fun `factorial rejects enormous BigDecimal values before allocating BigInteger`() {
        for (value in listOf("1001", "2147483648", "1E+1000000")) {
            try {
                HighPrecisionMathEngine.factorial(BigDecimal(value))
                org.junit.Assert.fail("Huge factorial must be rejected safely: $value")
            } catch (expected: ArithmeticException) {
                assertTrue(expected.message.orEmpty().contains("1000"))
            }
        }
    }

    @Test
    fun `factorial boundary and ordinary results remain correct`() {
        assertEquals(0, HighPrecisionMathEngine.factorial(BigDecimal.ZERO).compareTo(BigDecimal.ONE))
        assertEquals(0, HighPrecisionMathEngine.factorial(BigDecimal.ONE).compareTo(BigDecimal.ONE))
        assertEquals(0, HighPrecisionMathEngine.factorial(BigDecimal(5)).compareTo(BigDecimal(120)))
        assertEquals(2568, HighPrecisionMathEngine.factorial(BigDecimal(1000)).toPlainString().length)
    }

    @Test
    fun `fractional and negative factorial arguments still fail`() {
        for (bad in listOf("-1", "1.5")) {
            try {
                HighPrecisionMathEngine.factorial(BigDecimal(bad))
                org.junit.Assert.fail("Non-natural factorial must be rejected: $bad")
            } catch (_: ArithmeticException) {
                // The supported domain remains the integer range 0 through 1000.
            }
        }
    }

    @Test
    fun `asin keeps the angle offset for values rounded to 1 by Double`() {
        val x = BigDecimal("0.999999999999999999999999")
        val angle = HighPrecisionMathEngine.asin(x, isDegrees = false)
        val piOverTwo = HighPrecisionMathEngine.PI.divide(
            BigDecimal(2), HighPrecisionMathEngine.DEFAULT_MATH_CONTEXT
        )
        val offset = piOverTwo.subtract(angle)
        assertTrue(offset > BigDecimal("1E-13"))
        assertTrue(offset < BigDecimal("1E-11"))
        val recovered = HighPrecisionMathEngine.sin(angle, isDegrees = false)
        assertTrue(recovered.subtract(x).abs() <= BigDecimal("1E-28"))
    }

    @Test
    fun `asin remains symmetric close to negative one`() {
        val x = BigDecimal("0.999999999999999999999999")
        val positive = HighPrecisionMathEngine.asin(x, isDegrees = false)
        val negative = HighPrecisionMathEngine.asin(x.negate(), isDegrees = false)
        assertTrue(positive.add(negative).abs() <= BigDecimal("1E-28"))
        val recovered = HighPrecisionMathEngine.sin(negative, isDegrees = false)
        assertTrue(recovered.add(x).abs() <= BigDecimal("1E-28"))
    }

    @Test
    fun `asin domain endpoints and moderate input remain accurate`() {
        assertEquals(0, HighPrecisionMathEngine.asin(BigDecimal.ONE).compareTo(BigDecimal(90)))
        assertEquals(0, HighPrecisionMathEngine.asin(BigDecimal("-1")).compareTo(BigDecimal(-90)))
        val x = BigDecimal("0.9")
        val radians = HighPrecisionMathEngine.asin(x, isDegrees = false)
        assertTrue(HighPrecisionMathEngine.sin(radians, isDegrees = false)
            .subtract(x).abs() <= BigDecimal("1E-28"))
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
