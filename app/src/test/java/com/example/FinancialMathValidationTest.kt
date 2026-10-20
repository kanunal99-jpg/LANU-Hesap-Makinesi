package com.example

import com.example.core.calculator.FinancialMath
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class FinancialMathValidationTest {
    @Test fun `installment rejects zero and negative months instead of division by zero`() {
        listOf("0", "-1", "601", "oops", "").forEach { months ->
            assertFails { FinancialMath.installment("12000", months, "1.5") }
        }
        assertEquals("1000.00 TL", FinancialMath.display(FinancialMath.installment("12000", "12", "0").first))
        assertEquals("12000.00 TL", FinancialMath.display(FinancialMath.installment("12000", "12", "0").third))
        val result = FinancialMath.installment("12000", "12", "1.5")
        assertTrue(result.first > BigDecimal("1000"))
        assertTrue(result.second > BigDecimal.ZERO)
    }
    @Test fun `VAT and discount use exact decimals`() {
        assertEquals("20.00 TL", FinancialMath.display(FinancialMath.vat("100", "20", false).second))
        assertEquals("100.00 TL", FinancialMath.display(FinancialMath.vat("120", "20", true).first))
        assertEquals("125.00 TL", FinancialMath.display(FinancialMath.discount("500", "25").first))
        assertEquals("375.00 TL", FinancialMath.display(FinancialMath.discount("500", "25").second))
        assertEquals("10.00 TL", FinancialMath.display(FinancialMath.percentage("100", "10").first))
        assertEquals("0.30 TL", FinancialMath.display(FinancialMath.percentage("0,10", "300").first))
    }
    @Test fun `deposit interest and profit margins validate domain`() {
        assertEquals("45.00 TL", FinancialMath.display(FinancialMath.interest("3650", "45", "10").first))
        assertEquals("100.00 TL", FinancialMath.display(FinancialMath.margin("80", "20").first))
        assertFails { FinancialMath.margin("80", "100") }
        assertFails { FinancialMath.margin("80", "105") }
        assertFails { FinancialMath.interest("100", "20", "0") }
        assertFails { FinancialMath.discount("100", "101") }
    }
    @Test fun `invalid values are not silently converted to zero`() {
        listOf("", "abc", "NaN", "Infinity", "-1", "1000000000001").forEach { amount ->
            assertFails { FinancialMath.installment(amount, "12", "0") }
        }
        assertFails { FinancialMath.installment("1000", "12", "-3") }
        assertFails { FinancialMath.percentage("100", "NaN") }
        assertEquals("—", FinancialMath.display(null))
    }
    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Invalid financial input must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
