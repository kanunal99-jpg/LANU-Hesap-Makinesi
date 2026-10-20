package com.example.core.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Monetary computations never silently interpret malformed inputs as 0 or 1. */
object FinancialMath {
    private val MC = MathContext.DECIMAL128
    private val HUNDRED = BigDecimal("100")
    private val MAX_AMOUNT = BigDecimal("1000000000000")
    private val MAX_RATE = BigDecimal("1000")
    data class Values(val first: BigDecimal, val second: BigDecimal, val third: BigDecimal? = null)

    private fun decimal(raw: String, label: String): BigDecimal {
        val input = raw.trim().replace(',', '.')
        require(input.length in 1..32 && input.matches(Regex("[+-]?\\d{1,16}(?:\\.\\d{1,12})?"))) {
            "$label geçerli bir sayı olmalıdır."
        }
        return BigDecimal(input, MC)
    }
    private fun amount(raw: String) = decimal(raw, "Tutar").also {
        require(it >= BigDecimal.ZERO && it <= MAX_AMOUNT) { "Tutar 0 ile 1 trilyon TL arasında olmalıdır." }
    }
    private fun rate(raw: String, max: BigDecimal = MAX_RATE) = decimal(raw, "Oran").also {
        require(it >= BigDecimal.ZERO && it <= max) { "Oran 0 ile $max arasında olmalıdır." }
    }
    private fun ratio(rate: BigDecimal) = rate.divide(HUNDRED, MC)
    fun display(value: BigDecimal?): String =
        value?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.plus(" TL") ?: "—"

    fun percentage(amountText: String, rateText: String): Values {
        val base = amount(amountText)
        val percentage = decimal(rateText, "Yüzde").also {
            require(it.abs() <= MAX_RATE) { "Yüzde oranı geçerli aralıkta değil." }
        }
        val portion = base.multiply(ratio(percentage), MC)
        return Values(portion, base.add(portion), base.subtract(portion))
    }

    fun discount(priceText: String, rateText: String): Values {
        val base = amount(priceText)
        val fraction = ratio(rate(rateText, HUNDRED))
        val discount = base.multiply(fraction, MC)
        return Values(discount, base.subtract(discount))
    }

    fun vat(priceText: String, rateText: String, included: Boolean): Values {
        val price = amount(priceText)
        val fraction = ratio(rate(rateText, HUNDRED))
        val base = if (included) price.divide(BigDecimal.ONE.add(fraction), MC) else price
        val total = if (included) price else base.multiply(BigDecimal.ONE.add(fraction), MC)
        return Values(base, total.subtract(base), total)
    }

    fun installment(amountText: String, monthsText: String, rateText: String): Values {
        val principal = amount(amountText)
        val months = monthsText.trim().toIntOrNull()
        require(months != null && months in 1..600) { "Taksit sayısı 1–600 arasında olmalıdır." }
        val monthlyRate = ratio(rate(rateText))
        val payment = if (monthlyRate.signum() == 0) {
            principal.divide(BigDecimal(months), MC)
        } else {
            val growth = BigDecimal.ONE.add(monthlyRate).pow(months, MC)
            principal.multiply(monthlyRate, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC)
        }
        val total = payment.multiply(BigDecimal(months), MC)
        return Values(payment, total.subtract(principal), total)
    }

    fun interest(principalText: String, rateText: String, daysText: String): Values {
        val principal = amount(principalText)
        val days = daysText.trim().toIntOrNull()
        require(days != null && days in 1..36500) { "Vade 1–36500 gün arasında olmalıdır." }
        val interest = principal.multiply(rate(rateText), MC).multiply(BigDecimal(days), MC)
            .divide(BigDecimal("36500"), MC)
        return Values(interest, principal.add(interest))
    }

    fun margin(costText: String, marginText: String): Values {
        val cost = amount(costText)
        val margin = rate(marginText, HUNDRED)
        require(margin < HUNDRED) { "%100 veya üzeri kâr marjıyla satış fiyatı hesaplanamaz." }
        val sale = cost.divide(BigDecimal.ONE.subtract(ratio(margin)), MC)
        return Values(sale, sale.subtract(cost))
    }
}
