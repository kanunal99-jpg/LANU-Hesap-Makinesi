package com.example

import com.example.core.calculator.HighPrecisionMathEngine
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CalculatorFormatBudgetTest {
    @Test fun userControlledHugePrecisionCannotOverflowMathContextOrAllocateGiganticString() {
        val value = BigDecimal("1E+100")
        for (digits in listOf(-1, 1001, Int.MAX_VALUE)) {
            for (scientific in listOf(true, false)) {
                try {
                    HighPrecisionMathEngine.format(value, digits, scientific)
                    fail("Unsafe formatting precision accepted: $digits")
                } catch (_: IllegalArgumentException) {}
            }
        }
    }
    @Test fun ordinaryScientificAndPlainFormattingRemainAvailable() {
        assertTrue(HighPrecisionMathEngine.format(BigDecimal("123.456"), 3).isNotBlank())
        assertTrue(HighPrecisionMathEngine.format(BigDecimal("1E+400"), 6, true).contains("e+"))
        assertEquals("0", HighPrecisionMathEngine.format(BigDecimal.ZERO))
    }
}
