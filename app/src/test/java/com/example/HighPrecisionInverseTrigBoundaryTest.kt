package com.example

import com.example.core.calculator.HighPrecisionMathEngine
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Precision regression: BigDecimal can encode a point arbitrarily close to ±1. */
class HighPrecisionInverseTrigBoundaryTest {
    @Test fun asinNearOneRetainsARealDifferenceAtDefaultPrecision() {
        val nearOne = BigDecimal.ONE.subtract(BigDecimal("1E-50"))
        val endpoint = HighPrecisionMathEngine.asin(BigDecimal.ONE, isDegrees = false)
        val actual = HighPrecisionMathEngine.asin(nearOne, isDegrees = false)
        val difference = endpoint.subtract(actual)

        assertTrue("asin(1 - 1E-50) must be below pi/2", actual < endpoint)
        assertTrue("angular difference must not round to zero", difference > BigDecimal("1E-26"))
        assertTrue("near-one angle should stay near the endpoint", difference < BigDecimal("1E-24"))
        assertEquals(0, HighPrecisionMathEngine.asin(nearOne.negate(), false)
            .add(actual).compareTo(BigDecimal.ZERO))
    }

    @Test fun acosNearOneUsesSmallAngleInsteadOfSubtractingTwoNearlyEqualValues() {
        val nearOne = BigDecimal.ONE.subtract(BigDecimal("1E-90"))
        val radians = HighPrecisionMathEngine.acos(nearOne, isDegrees = false)
        val degrees = HighPrecisionMathEngine.acos(nearOne, isDegrees = true)

        // acos(1 - d) approaches sqrt(2d); for d=1E-90 this is ~1.414E-45.
        assertTrue("a nonzero tiny angle must not become zero", radians > BigDecimal("1E-46"))
        assertTrue(radians < BigDecimal("1E-44"))
        assertTrue(degrees > BigDecimal("1E-44"))
        assertTrue(degrees < BigDecimal("1E-42"))
        assertEquals(0, HighPrecisionMathEngine.acos(BigDecimal.ONE, false)
            .compareTo(BigDecimal.ZERO))
    }

    @Test fun ordinaryInverseTrigAnglesAndDomainChecksRemainIntact() {
        val regular = HighPrecisionMathEngine.acos(BigDecimal("0.8"), isDegrees = true)
        assertTrue(regular.subtract(BigDecimal("36.86989764584402")).abs() <
            BigDecimal("0.000001"))
        assertEquals(0, HighPrecisionMathEngine.asin(BigDecimal.ZERO)
            .compareTo(BigDecimal.ZERO))
        assertEquals(0, HighPrecisionMathEngine.acos(BigDecimal.ZERO)
            .compareTo(BigDecimal(90)))
        for (outside in listOf(BigDecimal("1.00001"), BigDecimal("-1.00001"))) {
            try {
                HighPrecisionMathEngine.acos(outside)
                fail("acos must reject inputs outside [-1,1]")
            } catch (_: ArithmeticException) {
                // Expected and protects the calculator from invalid inputs.
            }
        }
    }
}
