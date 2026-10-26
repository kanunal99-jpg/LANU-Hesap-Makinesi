package com.example

import com.example.core.calculator.HighPrecisionMathEngine as MathEngine
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Avoids materializing huge decimal coefficients during limit checks. */
class ScientificMagnitudeSafetyMatrixTest {
    @Test fun legitimateDecimalScaleAndSignRemainAccurate() {
        assertEquals(0, MathEngine.sqrt(BigDecimal("4")).compareTo(BigDecimal("2")))
        val cube = MathEngine.cbrt(BigDecimal("-27"))
        assertTrue(cube.subtract(BigDecimal("-3")).abs() < BigDecimal("1E-22"))
        assertEquals(0, MathEngine.pow(BigDecimal("2"), BigDecimal("10"))
            .compareTo(BigDecimal("1024")))
        assertEquals(0, MathEngine.ln(BigDecimal.ONE).compareTo(BigDecimal.ZERO))
        assertEquals(0, MathEngine.sqrt(BigDecimal("1E+400"))
            .compareTo(BigDecimal("1E+200")))
    }

    @Test fun hugeExponentialNotationFailsBeforeDangerousExpansion() {
        val extremeAngle = BigDecimal("1E+2147483647")
        try {
            MathEngine.sin(extremeAngle)
            fail("Extreme trigonometric magnitude accepted")
        } catch (_: ArithmeticException) {}
        try {
            MathEngine.ln(BigDecimal("1E+4001"))
            fail("Extreme logarithm accepted")
        } catch (_: ArithmeticException) {}
        try {
            MathEngine.pow(BigDecimal("2"), BigDecimal("10001"))
            fail("Unsupported exponent accepted")
        } catch (_: ArithmeticException) {}
    }

    @Test fun parserRejectsOversizedInputWithoutAllocatingPolynomialState() {
        val oversized = "1".repeat(8193)
        try {
            MathEngine.evaluate(oversized)
            fail("Oversized expression accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
