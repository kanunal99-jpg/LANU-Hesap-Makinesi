package com.example.core.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.*

object CalculatorEngine {

    private val MC = MathContext(16, RoundingMode.HALF_UP)

    fun evaluate(expression: String, isDegrees: Boolean = true): Double {
        val sanitized = sanitize(expression)
        if (sanitized.isBlank()) return 0.0
        return Parser(sanitized, isDegrees).parse()
    }

    private fun sanitize(expr: String): String {
        return expr
            .replace("×", "*")
            .replace("÷", "/")
            .replace("π", "PI")
            .replace("e", "E")
            .replace("mod", "%")
            .replace(" ", "")
    }

    // High precision helper to format double values to avoid floating point errors
    fun formatResult(value: Double, precision: Int = 6): String {
        if (value.isNaN()) return "Error"
        if (value.isInfinite()) return "Infinity"
        
        val cleanValue = if (value == -0.0) 0.0 else value
        
        if (cleanValue == cleanValue.toLong().toDouble() && abs(cleanValue) < 1e12) {
            return cleanValue.toLong().toString()
        }

        val formatPattern = "%.${precision}f"
        val formatted = String.format(Locale.US, formatPattern, cleanValue)
        return try {
            BigDecimal(formatted).stripTrailingZeros().toPlainString()
        } catch (e: Exception) {
            formatted
        }
    }

    fun factorial(n: Double): Double {
        if (n < 0.0) return Double.NaN
        if (n != floor(n)) return Double.NaN // Only integer factorial supported
        val intN = n.toInt()
        if (intN > 170) return Double.POSITIVE_INFINITY // Limit for Double capacity
        var result = 1.0
        for (i in 1..intN) {
            result *= i
        }
        return result
    }

    private class Parser(private val str: String, private val isDegrees: Boolean) {
        private var pos = -1
        private var ch = ' '

        private fun nextChar() {
            pos++
            ch = if (pos < str.length) str[pos] else '\u0000'
        }

        private fun eat(charToEat: Char): Boolean {
            while (ch == ' ') nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < str.length) throw RuntimeException("Unexpected character: $ch")
            return x
        }

        // Expression = Term (+/- Term)*
        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+') -> x += parseTerm() // addition
                    eat('-') -> x -= parseTerm() // subtraction
                    else -> return x
                }
            }
        }

        // Term = Factor (* / % Factor)*
        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*') -> x *= parseFactor() // multiplication
                    eat('/') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        x /= divisor // division
                    }
                    eat('%') -> {
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Modulo by zero")
                        x %= divisor // modulo
                    }
                    else -> return x
                }
            }
        }

        // Factor = (+/- Factor) or (Base ^ Factor)
        private fun parseFactor(): Double {
            if (eat('+')) return parseFactor() // unary plus
            val negate = eat('-')
            var x: Double
            val startPos = this.pos

            if (eat('(')) { // parentheses
                x = parseExpression()
                if (!eat(')')) throw RuntimeException("Missing closing parenthesis")
            } else if ((ch in '0'..'9') || ch == '.') { // numbers
                while ((ch in '0'..'9') || ch == '.') nextChar()
                // Check for EXP scientific notation (e.g. 1E5)
                if (ch == 'E' || ch == 'e') {
                    nextChar()
                    val expSign = if (ch == '+' || ch == '-') {
                        val sign = ch
                        nextChar()
                        sign
                    } else '+'
                    val startExp = pos
                    while (ch in '0'..'9') nextChar()
                    val expVal = str.substring(startExp, pos).toDoubleOrNull() ?: 0.0
                    val baseVal = str.substring(startPos, startExp - 1).toDouble()
                    x = if (expSign == '-') {
                        baseVal * 10.0.pow(-expVal)
                    } else {
                        baseVal * 10.0.pow(expVal)
                    }
                } else {
                    x = str.substring(startPos, pos).toDouble()
                }
            } else if (ch in 'A'..'Z' || ch in 'a'..'z') { // functions or constants
                while (ch in 'A'..'Z' || ch in 'a'..'z') nextChar()
                val funcName = str.substring(startPos, pos)
                
                if (funcName == "PI") {
                    x = Math.PI
                } else if (funcName == "E") {
                    x = Math.E
                } else {
                    // It's a scientific function
                    if (!eat('(')) throw RuntimeException("Missing opening parenthesis for function $funcName")
                    val arg = parseExpression()
                    if (!eat(')')) throw RuntimeException("Missing closing parenthesis for function $funcName")
                    
                    x = when (funcName.lowercase()) {
                        "sin" -> if (isDegrees) sin(Math.toRadians(arg)) else sin(arg)
                        "cos" -> if (isDegrees) cos(Math.toRadians(arg)) else cos(arg)
                        "tan" -> if (isDegrees) tan(Math.toRadians(arg)) else tan(arg)
                        "asin" -> {
                            val res = asin(arg)
                            if (isDegrees) Math.toDegrees(res) else res
                        }
                        "acos" -> {
                            val res = acos(arg)
                            if (isDegrees) Math.toDegrees(res) else res
                        }
                        "atan" -> {
                            val res = atan(arg)
                            if (isDegrees) Math.toDegrees(res) else res
                        }
                        "sinh" -> sinh(arg)
                        "cosh" -> cosh(arg)
                        "tanh" -> tanh(arg)
                        "log" -> log10(arg)
                        "ln" -> ln(arg)
                        "sqrt" -> {
                            if (arg < 0) throw IllegalArgumentException("Square root of negative number")
                            sqrt(arg)
                        }
                        "cbrt" -> cbrt(arg)
                        else -> throw RuntimeException("Unknown function: $funcName")
                    }
                }
            } else {
                throw RuntimeException("Unexpected token: $ch")
            }

            // Handle power operator (exponentiation)
            if (eat('^')) {
                val exponent = parseFactor()
                x = x.pow(exponent)
            }

            // Handle postfix operators like factorial
            if (eat('!')) {
                x = factorial(x)
            }

            return if (negate) -x else x
        }
    }
}
