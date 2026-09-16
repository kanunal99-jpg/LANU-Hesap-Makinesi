package com.example.core.calculator

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * HighPrecisionMathEngine provides high-precision scientific math evaluation
 * using BigDecimal and BigInteger, with configurable MathContext (default 34 digits).
 */
object HighPrecisionMathEngine {

    val DEFAULT_MATH_CONTEXT: MathContext = MathContext(34, RoundingMode.HALF_UP)

    val PI: BigDecimal = BigDecimal("3.1415926535897932384626433832795028841971693993751058209749445923078164062862089986280348253421170679")
    val E: BigDecimal = BigDecimal("2.7182818284590452353602874713526624977572470936999595749669676277240766303535475945713821785251664274")
    val TWO_PI: BigDecimal = PI.multiply(BigDecimal(2))
    val HALF_PI: BigDecimal = PI.divide(BigDecimal(2), DEFAULT_MATH_CONTEXT)

    /**
     * Evaluates a mathematical expression string with high precision.
     */
    fun evaluate(
        expression: String,
        isDegrees: Boolean = true,
        mathContext: MathContext = DEFAULT_MATH_CONTEXT
    ): BigDecimal {
        val sanitized = sanitize(expression)
        if (sanitized.isBlank()) return BigDecimal.ZERO
        val parser = ExpressionParser(sanitized, isDegrees, mathContext)
        return parser.parse()
    }

    /**
     * Sanitizes expression string by replacing common math symbols with standard tokens.
     */
    fun sanitize(expr: String): String {
        return expr
            .replace("×", "*")
            .replace("÷", "/")
            .replace("mod", "%")
            .replace("π", "PI")
            .replace(" ", "")
            .replace("（", "(")
            .replace("）", ")")
    }

    /**
     * High precision formatting of a BigDecimal according to precision digits and mode.
     */
    fun format(
        value: BigDecimal,
        precision: Int = 16,
        scientificNotation: Boolean = false
    ): String {
        if (value.compareTo(BigDecimal.ZERO) == 0) return "0"

        val stripped = value.stripTrailingZeros()
        if (scientificNotation) {
            return String.format(Locale.US, "%.${precision}e", value.toDouble())
        }

        // If the number is within normal display scale, show plain string
        val plain = stripped.toPlainString()
        if (plain.length > 25 || (plain.contains(".") && plain.substringAfter(".").length > precision)) {
            val rounded = value.setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros()
            return rounded.toPlainString()
        }
        return plain
    }

    // High-precision square root via Newton-Raphson iteration
    fun sqrt(value: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (value.signum() < 0) throw ArithmeticException("Square root of negative number")
        if (value.signum() == 0) return BigDecimal.ZERO

        val two = BigDecimal(2)
        var x = BigDecimal(kotlin.math.sqrt(value.toDouble()), mc)
        for (i in 0 until 20) {
            val nextX = x.add(value.divide(x, mc), mc).divide(two, mc)
            if (x == nextX) break
            x = nextX
        }
        return x
    }

    // High-precision cube root via Newton-Raphson
    fun cbrt(value: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (value.signum() == 0) return BigDecimal.ZERO
        val isNeg = value.signum() < 0
        val absVal = value.abs()
        val three = BigDecimal(3)
        var x = BigDecimal(kotlin.math.cbrt(absVal.toDouble()), mc)
        for (i in 0 until 20) {
            val x2 = x.multiply(x, mc)
            val nextX = x.multiply(BigDecimal(2), mc).add(absVal.divide(x2, mc), mc).divide(three, mc)
            if (x == nextX) break
            x = nextX
        }
        return if (isNeg) x.negate() else x
    }

    // High-precision Factorial using BigInteger
    fun factorial(n: BigDecimal): BigDecimal {
        if (n.signum() < 0) throw ArithmeticException("Factorial of negative number")
        if (n.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            throw ArithmeticException("Factorial only supported for integers")
        }
        val intVal = n.toBigInteger()
        if (intVal > BigInteger.valueOf(1000)) throw ArithmeticException("Factorial overflow (>1000!)")

        var result = BigInteger.ONE
        var current = BigInteger.ONE
        while (current <= intVal) {
            result = result.multiply(current)
            current = current.add(BigInteger.ONE)
        }
        return BigDecimal(result)
    }

    // High-precision Exponential e^x
    fun exp(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (x.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE

        // Separate integer and fractional part: e^(k + f) = (e^k) * e^f
        val k = x.setScale(0, RoundingMode.DOWN).toInt()
        val f = x.subtract(BigDecimal(k))

        // Compute e^f using Taylor series: sum_{n=0..} f^n / n!
        var sum = BigDecimal.ONE
        var term = BigDecimal.ONE
        for (n in 1..40) {
            term = term.multiply(f, mc).divide(BigDecimal(n), mc)
            sum = sum.add(term, mc)
            if (term.abs().compareTo(BigDecimal("1E-35")) < 0) break
        }

        // Multiply by E^k
        var ek = BigDecimal.ONE
        if (k > 0) {
            for (i in 0 until k) ek = ek.multiply(E, mc)
        } else if (k < 0) {
            for (i in 0 until -k) ek = ek.divide(E, mc)
        }
        return sum.multiply(ek, mc)
    }

    // High-precision Natural Logarithm ln(x)
    fun ln(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (x.signum() <= 0) throw ArithmeticException("Logarithm of non-positive number")
        if (x.compareTo(BigDecimal.ONE) == 0) return BigDecimal.ZERO

        // Use Double approximation for initial value, then Newton-Raphson: x_{n+1} = x_n + 2 * (x - e^{x_n}) / (x + e^{x_n})
        var y = BigDecimal(kotlin.math.ln(x.toDouble()), mc)
        for (i in 0 until 10) {
            val ey = exp(y, mc)
            val num = x.subtract(ey, mc)
            val den = x.add(ey, mc)
            val delta = BigDecimal(2).multiply(num, mc).divide(den, mc)
            y = y.add(delta, mc)
            if (delta.abs().compareTo(BigDecimal("1E-34")) < 0) break
        }
        return y
    }

    // High-precision Log base 10
    fun log10(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val ln10 = ln(BigDecimal(10), mc)
        return ln(x, mc).divide(ln10, mc)
    }

    // High-precision Log base 2
    fun log2(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val ln2 = ln(BigDecimal(2), mc)
        return ln(x, mc).divide(ln2, mc)
    }

    // High-precision Power x^y
    fun pow(base: BigDecimal, exponent: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (exponent.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            if (exponent.signum() < 0) throw ArithmeticException("Division by zero in 0^negative")
            return BigDecimal.ZERO
        }

        // If exponent is integer
        if (exponent.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            val expInt = exponent.toInt()
            if (expInt >= 0) {
                return base.pow(expInt, mc)
            } else {
                return BigDecimal.ONE.divide(base.pow(-expInt, mc), mc)
            }
        }

        // Non-integer exponent: x^y = exp(y * ln(x))
        if (base.signum() < 0) {
            throw ArithmeticException("Negative base with non-integer exponent")
        }
        return exp(exponent.multiply(ln(base, mc), mc), mc)
    }

    // High-precision Trigonometric: sin
    fun sin(angle: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val rad = if (isDegrees) {
            // Range reduce to [0, 360)
            val deg = angle.remainder(BigDecimal(360))
            val positiveDeg = if (deg.signum() < 0) deg.add(BigDecimal(360)) else deg
            
            // Exact angles
            if (positiveDeg.compareTo(BigDecimal.ZERO) == 0 || positiveDeg.compareTo(BigDecimal(180)) == 0 || positiveDeg.compareTo(BigDecimal(360)) == 0) {
                return BigDecimal.ZERO
            }
            if (positiveDeg.compareTo(BigDecimal(90)) == 0) return BigDecimal.ONE
            if (positiveDeg.compareTo(BigDecimal(270)) == 0) return BigDecimal("-1")
            if (positiveDeg.compareTo(BigDecimal(30)) == 0 || positiveDeg.compareTo(BigDecimal(150)) == 0) return BigDecimal("0.5")
            if (positiveDeg.compareTo(BigDecimal(210)) == 0 || positiveDeg.compareTo(BigDecimal(330)) == 0) return BigDecimal("-0.5")

            positiveDeg.multiply(PI, mc).divide(BigDecimal(180), mc)
        } else {
            angle.remainder(TWO_PI)
        }

        // Reduce to [-PI, PI]
        var x = rad
        if (x.compareTo(PI) > 0) x = x.subtract(TWO_PI)
        if (x.compareTo(PI.negate()) < 0) x = x.add(TWO_PI)

        // Taylor series for sin(x): x - x^3/3! + x^5/5! - ...
        var sum = x
        var term = x
        val x2 = x.multiply(x, mc)
        var sign = -1
        for (n in 1..25) {
            val d1 = 2 * n
            val d2 = 2 * n + 1
            term = term.multiply(x2, mc).divide(BigDecimal(d1 * d2), mc)
            sum = if (sign < 0) sum.subtract(term, mc) else sum.add(term, mc)
            sign = -sign
            if (term.abs().compareTo(BigDecimal("1E-35")) < 0) break
        }
        return sum
    }

    // High-precision Trigonometric: cos
    fun cos(angle: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (isDegrees) {
            val deg = angle.remainder(BigDecimal(360))
            val positiveDeg = if (deg.signum() < 0) deg.add(BigDecimal(360)) else deg
            if (positiveDeg.compareTo(BigDecimal(90)) == 0 || positiveDeg.compareTo(BigDecimal(270)) == 0) {
                return BigDecimal.ZERO
            }
            if (positiveDeg.compareTo(BigDecimal.ZERO) == 0 || positiveDeg.compareTo(BigDecimal(360)) == 0) return BigDecimal.ONE
            if (positiveDeg.compareTo(BigDecimal(180)) == 0) return BigDecimal("-1")
            if (positiveDeg.compareTo(BigDecimal(60)) == 0 || positiveDeg.compareTo(BigDecimal(300)) == 0) return BigDecimal("0.5")
            if (positiveDeg.compareTo(BigDecimal(120)) == 0 || positiveDeg.compareTo(BigDecimal(240)) == 0) return BigDecimal("-0.5")
        }
        // cos(x) = sin(x + PI/2)
        val shifted = if (isDegrees) angle.add(BigDecimal(90)) else angle.add(HALF_PI)
        return sin(shifted, isDegrees, mc)
    }

    // High-precision Trigonometric: tan
    fun tan(angle: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val cosVal = cos(angle, isDegrees, mc)
        if (cosVal.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("Tangent undefined (division by zero)")
        val sinVal = sin(angle, isDegrees, mc)
        return sinVal.divide(cosVal, mc)
    }

    // High-precision Inverse Trigonometric: asin
    fun asin(x: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        if (x.abs().compareTo(BigDecimal.ONE) > 0) throw ArithmeticException("Domain error for asin: [-1, 1]")
        if (x.compareTo(BigDecimal.ONE) == 0) return if (isDegrees) BigDecimal(90) else HALF_PI
        if (x.compareTo(BigDecimal("-1")) == 0) return if (isDegrees) BigDecimal(-90) else HALF_PI.negate()
        if (x.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO

        val dblRes = kotlin.math.asin(x.toDouble())
        var rad = BigDecimal(dblRes, mc)
        // Refine with Newton's method: f(y) = sin(y) - x = 0 => y_new = y - (sin(y) - x) / cos(y)
        for (i in 0 until 8) {
            val s = sin(rad, false, mc)
            val c = cos(rad, false, mc)
            if (c.compareTo(BigDecimal.ZERO) == 0) break
            val delta = s.subtract(x, mc).divide(c, mc)
            rad = rad.subtract(delta, mc)
            if (delta.abs().compareTo(BigDecimal("1E-34")) < 0) break
        }
        return if (isDegrees) rad.multiply(BigDecimal(180), mc).divide(PI, mc) else rad
    }

    // High-precision Inverse Trigonometric: acos
    fun acos(x: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val asinVal = asin(x, isDegrees, mc)
        val half = if (isDegrees) BigDecimal(90) else HALF_PI
        return half.subtract(asinVal, mc)
    }

    // High-precision Inverse Trigonometric: atan
    fun atan(x: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val dblRes = kotlin.math.atan(x.toDouble())
        var rad = BigDecimal(dblRes, mc)
        for (i in 0 until 8) {
            val t = tan(rad, false, mc)
            val c = cos(rad, false, mc)
            val sec2 = BigDecimal.ONE.divide(c.multiply(c, mc), mc)
            val delta = t.subtract(x, mc).divide(sec2, mc)
            rad = rad.subtract(delta, mc)
            if (delta.abs().compareTo(BigDecimal("1E-34")) < 0) break
        }
        return if (isDegrees) rad.multiply(BigDecimal(180), mc).divide(PI, mc) else rad
    }

    // Hyperbolic Functions
    fun sinh(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val ex = exp(x, mc)
        val enx = exp(x.negate(), mc)
        return ex.subtract(enx, mc).divide(BigDecimal(2), mc)
    }

    fun cosh(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val ex = exp(x, mc)
        val enx = exp(x.negate(), mc)
        return ex.add(enx, mc).divide(BigDecimal(2), mc)
    }

    fun tanh(x: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        val s = sinh(x, mc)
        val c = cosh(x, mc)
        return s.divide(c, mc)
    }

    /**
     * Expression parser supporting precedence, parentheses, and functions.
     */
    private class ExpressionParser(
        private val str: String,
        private val isDegrees: Boolean,
        private val mc: MathContext
    ) {
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

        fun parse(): BigDecimal {
            nextChar()
            val x = parseExpression()
            if (pos < str.length) throw RuntimeException("Unexpected character: $ch")
            return x
        }

        // Expression = Term (+/- Term)*
        private fun parseExpression(): BigDecimal {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+') -> x = x.add(parseTerm(), mc)
                    eat('-') -> x = x.subtract(parseTerm(), mc)
                    else -> return x
                }
            }
        }

        // Term = Factor (* / % Factor)*
        private fun parseTerm(): BigDecimal {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*') -> x = x.multiply(parseFactor(), mc)
                    eat('/') -> {
                        val divisor = parseFactor()
                        if (divisor.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("Division by zero")
                        x = x.divide(divisor, mc)
                    }
                    eat('%') -> {
                        val divisor = parseFactor()
                        if (divisor.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("Modulo by zero")
                        x = x.remainder(divisor, mc)
                    }
                    else -> return x
                }
            }
        }

        // Factor = (+/- Factor) or (Base ^ Factor) or Function / Number / Constant
        private fun parseFactor(): BigDecimal {
            if (eat('+')) return parseFactor()
            val negate = eat('-')
            var x: BigDecimal
            val startPos = this.pos

            if (eat('(')) {
                x = parseExpression()
                if (!eat(')')) throw RuntimeException("Missing closing parenthesis")
            } else if ((ch in '0'..'9') || ch == '.') {
                while ((ch in '0'..'9') || ch == '.') nextChar()
                // Check for scientific notation exponent: e.g. 1.5E6 or 2e-4
                if (ch == 'E' || ch == 'e') {
                    nextChar()
                    if (ch == '+' || ch == '-') nextChar()
                    while (ch in '0'..'9') nextChar()
                }
                x = BigDecimal(str.substring(startPos, pos), mc)
            } else if (ch in 'A'..'Z' || ch in 'a'..'z') {
                while (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9') nextChar()
                val token = str.substring(startPos, pos)

                when (token) {
                    "PI" -> x = PI
                    "E" -> x = E
                    else -> {
                        if (!eat('(')) throw RuntimeException("Missing opening parenthesis for $token")
                        val arg = parseExpression()
                        if (!eat(')')) throw RuntimeException("Missing closing parenthesis for $token")

                        x = when (token.lowercase(Locale.US)) {
                            "sin" -> sin(arg, isDegrees, mc)
                            "cos" -> cos(arg, isDegrees, mc)
                            "tan" -> tan(arg, isDegrees, mc)
                            "asin" -> asin(arg, isDegrees, mc)
                            "acos" -> acos(arg, isDegrees, mc)
                            "atan" -> atan(arg, isDegrees, mc)
                            "sinh" -> sinh(arg, mc)
                            "cosh" -> cosh(arg, mc)
                            "tanh" -> tanh(arg, mc)
                            "log" -> log10(arg, mc)
                            "ln" -> ln(arg, mc)
                            "log2" -> log2(arg, mc)
                            "sqrt" -> sqrt(arg, mc)
                            "cbrt" -> cbrt(arg, mc)
                            "abs" -> arg.abs()
                            "exp" -> exp(arg, mc)
                            else -> throw RuntimeException("Unknown function: $token")
                        }
                    }
                }
            } else {
                throw RuntimeException("Unexpected token: $ch")
            }

            // Exponentiation operator ^
            if (eat('^')) {
                val exponent = parseFactor()
                x = pow(x, exponent, mc)
            }

            // Postfix factorial !
            if (eat('!')) {
                x = factorial(x)
            }

            return if (negate) x.negate() else x
        }
    }
}
