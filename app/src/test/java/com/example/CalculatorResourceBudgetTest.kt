package com.example

import com.example.core.calculator.CalculatorEngine
import com.example.core.calculator.HighPrecisionMathEngine
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext

/** Input-size and precision ceilings prevent stack/memory exhaustion. */
class CalculatorResourceBudgetTest {
    @Test fun legacyParserRejectsOversizedExpressionWithoutParsing() {
        expectInvalid {
            CalculatorEngine.evaluate("1".repeat(8193))
        }
        assertEquals(3.0, CalculatorEngine.evaluate("1+2"), 0.0)
    }

    @Test fun legacyParserRejectsNestedParenthesesAndUnaryOrPowerChains() {
        val cases = listOf(
            "(".repeat(130) + "1" + ")".repeat(130),
            "-".repeat(130) + "1",
            "2^".repeat(130) + "2"
        )
        for (expression in cases) {
            expectInvalid { CalculatorEngine.evaluate(expression) }
        }
        assertEquals(8.0, CalculatorEngine.evaluate("2^3"), 0.0)
        assertEquals(-3.0, CalculatorEngine.evaluate("-(1+2)"), 0.0)
        assertEquals(1.0, CalculatorEngine.evaluate("1"), 0.0)
    }

    @Test fun bothFormattersRejectUnboundedPrecisionBeforeAllocating() {
        for (invalid in listOf(-1, 101, Int.MAX_VALUE)) {
            expectInvalid { CalculatorEngine.formatResult(2.5, invalid) }
            expectInvalid { HighPrecisionMathEngine.format(BigDecimal("2.5"), invalid) }
        }
        assertEquals("2.5", CalculatorEngine.formatResult(2.5, 2))
        assertEquals("2.5", HighPrecisionMathEngine.format(BigDecimal("2.5"), 2))
        assertTrue(HighPrecisionMathEngine.format(
            BigDecimal("2.5"), precision = 100, scientificNotation = true
        ).startsWith("2.5"))
    }

    @Test fun highPrecisionParserAndExponentialRejectUnboundedMathContexts() {
        for (invalid in listOf(MathContext.UNLIMITED, MathContext(101))) {
            expectInvalid { HighPrecisionMathEngine.evaluate("1+1", mathContext = invalid) }
            expectInvalid { HighPrecisionMathEngine.exp(BigDecimal("10000"), invalid) }
            expectInvalid { HighPrecisionMathEngine.pow(
                BigDecimal("1.23456789"), BigDecimal("10000"), invalid
            ) }
        }
        assertEquals(0, HighPrecisionMathEngine.evaluate(
            "2^10", mathContext = MathContext(100)
        ).compareTo(BigDecimal("1024")))
    }

    private inline fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Input should have failed fast with IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Guarded rejection. No silently truncated or unbounded work.
        }
    }
}
