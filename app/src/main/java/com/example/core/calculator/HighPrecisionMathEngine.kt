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
        require(mathContext.precision in 1..100) { "Math precision must be between 1 and 100" }
        require(expression.length <= 8192) { "Expression exceeds supported length (8192 characters)" }
        val sanitized = sanitize(expression)
        if (sanitized.isBlank()) return BigDecimal.ZERO
        val parser = ExpressionParser(sanitized, isDegrees, mathContext)
        return parser.parse()
    }

    /**
     * Graph variables must be stand-alone letters, not part of a function
     * name such as exp, max or a scientific notation exponent.
     * "2x", "x^2", "sin(x)" are graph expressions; "exp(1)" is not.
     */
    fun containsGraphVariable(expression: String): Boolean =
        Regex("(?<![A-Za-z])x(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(expression)

    /**
     * Sanitizes expression string by replacing common math symbols with standard tokens.
     */
    fun sanitize(expr: String): String {
        return expr
            .replace("×", "*")
            .replace("÷", "/")
            // Replace MOD only as a standalone operator, not inside a word.
            // Handle pasted or voice-entered MOD/MoD as well as the "mod" key.
            .replace(Regex("(?<![A-Za-z])mod(?![A-Za-z])", RegexOption.IGNORE_CASE), "%")
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
        // All supported UI modes use at most 100 decimal places. An arbitrary
        // precision here may allocate gigabytes of zeros in setScale/format.
        require(precision in 0..100) { "Precision must be between 0 and 100" }
        if (value.compareTo(BigDecimal.ZERO) == 0) return "0"

        val stripped = value.stripTrailingZeros()
        // Scale may be near Int.MAX_VALUE; compute magnitude in Long to
        // avoid overflow and never allocate a gigantic plain decimal string.
        val magnitude = stripped.precision().toLong() - stripped.scale().toLong() - 1L
        if (!scientificNotation && (magnitude < -precision.toLong() || magnitude >= 24L)) {
            if (magnitude !in -10000L..10000L) return stripped.toString()
            return format(value, precision, scientificNotation = true)
        }
        if (scientificNotation) {
            // Keep the full BigDecimal exponent; Double overflows near 1E309.
            val rounded = value.round(MathContext(precision + 1, RoundingMode.HALF_UP))
            val exponent = rounded.precision().toLong() - rounded.scale().toLong() - 1L
            if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                return rounded.stripTrailingZeros().toString()
            }
            val mantissa = rounded.movePointLeft(exponent.toInt()).setScale(precision, RoundingMode.HALF_UP)
            val exponentSign = if (exponent >= 0) "+" else "-"
            val exponentDigits = kotlin.math.abs(exponent).toString().padStart(2, '0')
            return "${mantissa.toPlainString()}e$exponentSign$exponentDigits"
        }

        // If the number is within normal display scale, show plain string
        val plain = stripped.toPlainString()
        if (plain.length > 25 || (plain.contains(".") && plain.substringAfter(".").length > precision)) {
            val rounded = value.setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros()
            return rounded.toPlainString()
        }
        return plain
    }

    /** Entry points exposed directly to calculators must not permit unbounded precision. */
    private fun requireSupportedMathContext(mc: MathContext) {
        require(mc.precision in 1..100) { "Math precision must be between 1 and 100" }
    }

    // High-precision square root via Newton-Raphson iteration
    fun sqrt(value: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        if (value.signum() < 0) throw ArithmeticException("Square root of negative number")
        if (value.signum() == 0) return BigDecimal.ZERO

        val two = BigDecimal(2)
        // Normalize the initial estimate by decimal magnitude. Converting the
        // original number to Double may become Infinity (e.g. 1E+400) or zero
        // (e.g. 1E-400), even though BigDecimal can represent both exactly.
        val decimalExponent = value.precision().toLong() - value.scale().toLong() - 1L
        // Compact 1E+2147483647 is not safe to expand during Newton iterations.
        if (decimalExponent !in -10_000L..10_000L)
            throw ArithmeticException("Square root exponent exceeds resource budget")
        val halfExponent = Math.floorDiv(decimalExponent, 2L)
        if (halfExponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw ArithmeticException("Square root exponent out of range")
        }
        var x = BigDecimal.ONE.scaleByPowerOfTen(halfExponent.toInt())
        for (i in 0 until 20) {
            val nextX = x.add(value.divide(x, mc), mc).divide(two, mc)
            if (x == nextX) break
            x = nextX
        }
        return x
    }

    // High-precision cube root via Newton-Raphson
    fun cbrt(value: BigDecimal, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        if (value.signum() == 0) return BigDecimal.ZERO
        val isNeg = value.signum() < 0
        val absVal = value.abs()
        val three = BigDecimal(3)
        // A Double seed overflows to Infinity for 1E+400 and underflows for
        // 1E-400. Use decimal magnitude for an entirely BigDecimal estimate.
        val decimalExponent = absVal.precision().toLong() - absVal.scale().toLong() - 1L
        if (decimalExponent !in -10_000L..10_000L)
            throw ArithmeticException("Cube root exponent exceeds resource budget")
        val thirdExponent = Math.floorDiv(decimalExponent, 3L)
        if (thirdExponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw ArithmeticException("Cube root exponent out of range")
        }
        var x = BigDecimal.ONE.scaleByPowerOfTen(thirdExponent.toInt())
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
        // Enforce the documented 1000! ceiling BEFORE computing a potentially
        // enormous BigInteger. A value such as 1E+1000000 must fail immediately
        // rather than allocate millions of digits during conversion.
        if (n > BigDecimal(1000)) {
            throw ArithmeticException("Factorial overflow (>1000!)")
        }
        if (n.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            throw ArithmeticException("Factorial only supported for integers")
        }
        val intVal = BigInteger.valueOf(n.intValueExact().toLong())

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
        // MathContext.UNLIMITED makes repeated E multiplication allocate
        // unbounded coefficients, even for a compact input such as exp(10000).
        require(mc.precision in 1..100) { "Math precision must be between 1 and 100" }
        if (x.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE

        // Reject extreme inputs before narrowing BigDecimal to Int. Otherwise
        // 2147483648 wraps into a negative k and returns a wrong result.
        if (x.abs() > BigDecimal(10000)) {
            throw ArithmeticException("Exponential argument out of supported range (-10000 to 10000)")
        }
        // Separate integer and fractional part: e^(k + f) = (e^k) * e^f
        val k = x.setScale(0, RoundingMode.DOWN).intValueExact()
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
        requireSupportedMathContext(mc)
        if (x.signum() <= 0) throw ArithmeticException("Logarithm of non-positive number")
        if (x.compareTo(BigDecimal.ONE) == 0) return BigDecimal.ZERO

        // Normalize the decimal magnitude before forming the initial estimate.
        // x.toDouble() overflows near 1E+309 and underflows near 1E-324;
        // the normalized significand is always within [1, 10).
        val decimalExponent = x.precision().toLong() - x.scale().toLong() - 1L
        // exp(y) is also used below, so reject inputs outside its safe
        // operating range instead of exhausting CPU or overflowing.
        if (decimalExponent !in -4000L..4000L) {
            throw ArithmeticException("Logarithm argument out of supported magnitude range")
        }
        val significand = x.movePointLeft(decimalExponent.toInt())
        val lnTen = BigDecimal("2.302585092994045684017991454684364207601101488628772976033327900967572609677352480235997205089598298")
        var y = lnTen.multiply(BigDecimal.valueOf(decimalExponent), mc)
            .add(BigDecimal(kotlin.math.ln(significand.toDouble()), mc), mc)
        // Newton-Raphson: y_next = y + 2 * (x - e^y) / (x + e^y).
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
        // Unlimited precision with an allowed 10000 exponent could expand a
        // compact decimal base into hundreds of thousands of digits.
        require(mc.precision in 1..100) { "Math precision must be between 1 and 100" }
        if (exponent.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            if (exponent.signum() < 0) throw ArithmeticException("Division by zero in 0^negative")
            return BigDecimal.ZERO
        }

        // If exponent is integer, reject impossible or excessively expensive
        // requests before converting BigDecimal to Int. toInt() truncates values
        // outside its range (e.g. 2147483648), yielding incorrect results.
        if (exponent.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            val maxMagnitude = BigDecimal(10000)
            if (exponent.abs() > maxMagnitude) {
                throw ArithmeticException("Integer exponent out of supported range (-10000 to 10000)")
            }
            val expInt = exponent.intValueExact()
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

    /**
     * BigDecimal can represent a gigantic exponent in a few bytes, yet
     * remainder(360) and angle+90 can expand it into an unbounded coefficient.
     * Examine scale/precision metadata in O(1) before trig arithmetic.
     * This limit is a resource policy, not an accuracy guarantee.
     */
    private fun requireSupportedTrigMagnitude(angle: BigDecimal) {
        if (angle.signum() == 0) return
        val exponent = angle.precision().toLong() - angle.scale().toLong() - 1L
        if (exponent !in -10_000L..10_000L) {
            throw ArithmeticException("Trigonometric angle exponent out of supported range")
        }
    }

    // High-precision Trigonometric: sin
    fun sin(angle: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        requireSupportedTrigMagnitude(angle)
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
        // Fixed 25 terms / 1E-35 used to silently cap a MathContext(100)
        // request at ~35 digits. Bound work to 100 terms and adapt to the
        // explicitly supported precision range 1..100.
        val tolerance = BigDecimal.ONE.scaleByPowerOfTen(-mc.precision - 2)
        for (n in 1..100) {
            val d1 = 2 * n
            val d2 = 2 * n + 1
            term = term.multiply(x2, mc).divide(BigDecimal(d1 * d2), mc)
            sum = if (sign < 0) sum.subtract(term, mc) else sum.add(term, mc)
            sign = -sign
            if (term.abs() < tolerance) break
        }
        return sum
    }

    // High-precision Trigonometric: cos
    fun cos(angle: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        requireSupportedTrigMagnitude(angle)
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
        val shifted = if (isDegrees) angle.add(BigDecimal(90)) else angle.add(PI.divide(BigDecimal(2), mc))
        return sin(shifted, isDegrees, mc)
    }

    // High-precision Trigonometric: tan
    fun tan(angle: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        requireSupportedTrigMagnitude(angle)
        val cosVal = cos(angle, isDegrees, mc)
        // In radian mode, the PI constant and Taylor evaluation introduce
        // a tiny non-zero rounding residue at PI/2 (and odd multiples).
        // Dividing by that residue would invent a huge finite tangent.
        val precision = if (mc.precision > 0) mc.precision else DEFAULT_MATH_CONTEXT.precision
        val zeroTolerance = BigDecimal.ONE.scaleByPowerOfTen(2 - precision)
        if (cosVal.abs() <= zeroTolerance) {
            throw ArithmeticException("Tangent undefined or numerically unstable near PI/2")
        }
        val sinVal = sin(angle, isDegrees, mc)
        return sinVal.divide(cosVal, mc)
    }

    // High-precision Inverse Trigonometric: asin
    fun asin(x: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        if (x.abs().compareTo(BigDecimal.ONE) > 0) throw ArithmeticException("Domain error for asin: [-1, 1]")
        if (x.compareTo(BigDecimal.ONE) == 0) return if (isDegrees) BigDecimal(90) else PI.divide(BigDecimal(2), mc)
        if (x.compareTo(BigDecimal("-1")) == 0) return if (isDegrees) BigDecimal(-90) else PI.divide(BigDecimal(2), mc).negate()
        if (x.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO

        // Near +/-1, x.toDouble() rounds distinct BigDecimal inputs to 1.0,
        // making Newton's derivative cos(y) almost zero. Reduce to a smaller
        // argument before the Double seed: asin(x) = sign(x) *
        // (PI/2 - asin(sqrt(1 - x*x))). The recursive argument is < 0.75.
        if (x.abs() > BigDecimal("0.75")) {
            // (1 - |x|) * (1 + |x|) avoids catastrophic cancellation:
            // rounding x*x first can turn 1 - 1E-90 into exactly 1 at
            // MathContext(34), making a real angular difference disappear.
            val distance = BigDecimal.ONE.subtract(x.abs())
                .multiply(BigDecimal.ONE.add(x.abs()), mc)
            val small = asin(sqrt(distance, mc), isDegrees = false, mc = mc)
            val radians = PI.divide(BigDecimal(2), mc).subtract(small, mc)
                .let { if (x.signum() < 0) it.negate() else it }
            return if (isDegrees) radians.multiply(BigDecimal(180), mc).divide(PI, mc) else radians
        }

        val dblRes = kotlin.math.asin(x.toDouble())
        var rad = BigDecimal(dblRes, mc)
        // Refine with Newton's method: f(y) = sin(y) - x = 0 => y_new = y - (sin(y) - x) / cos(y)
        for (i in 0 until 8) {
            val s = sin(rad, false, mc)
            val c = cos(rad, false, mc)
            if (c.compareTo(BigDecimal.ZERO) == 0) break
            val delta = s.subtract(x, mc).divide(c, mc)
            rad = rad.subtract(delta, mc)
            if (delta.abs() < BigDecimal.ONE.scaleByPowerOfTen(-mc.precision - 2)) break
        }
        return if (isDegrees) rad.multiply(BigDecimal(180), mc).divide(PI, mc) else rad
    }

    // High-precision Inverse Trigonometric: acos
    fun acos(x: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        // Near +1 the ordinary PI/2 - asin(x) subtraction loses every
        // significant digit of a small acos angle at finite precision.
        // Compute acos(x) = asin(sqrt((1-x)*(1+x))) directly in that region.
        if (x > BigDecimal("0.75") && x <= BigDecimal.ONE) {
            val complement = BigDecimal.ONE.subtract(x)
                .multiply(BigDecimal.ONE.add(x), mc)
            val smallRadians = asin(sqrt(complement, mc), isDegrees = false, mc = mc)
            return if (isDegrees)
                smallRadians.multiply(BigDecimal(180), mc).divide(PI, mc)
            else smallRadians
        }
        val asinVal = asin(x, isDegrees, mc)
        val half = if (isDegrees) BigDecimal(90) else PI.divide(BigDecimal(2), mc)
        return half.subtract(asinVal, mc)
    }

    // High-precision Inverse Trigonometric: atan
    fun atan(x: BigDecimal, isDegrees: Boolean = true, mc: MathContext = DEFAULT_MATH_CONTEXT): BigDecimal {
        requireSupportedMathContext(mc)
        // The computed degree conversion of 0 may retain a noncanonical
        // BigDecimal scale; the mathematical result is exactly zero.
        if (x.signum() == 0) return BigDecimal.ZERO
        // atan(x) = sign(x) * (PI/2 - atan(1/abs(x))) for |x| > 1.
        // Reducing the argument to [-1, 1] avoids converting 1E+400 to
        // Double.POSITIVE_INFINITY and avoids Newton iteration near a tan pole.
        if (x.abs() > BigDecimal.ONE) {
            val reciprocal = BigDecimal.ONE.divide(x.abs(), mc)
            val smallRad = atan(reciprocal, isDegrees = false, mc = mc)
            val reduced = PI.divide(BigDecimal(2), mc).subtract(smallRad, mc)
            val rad = if (x.signum() < 0) reduced.negate() else reduced
            return if (isDegrees) rad.multiply(BigDecimal(180), mc).divide(PI, mc) else rad
        }

        val dblRes = kotlin.math.atan(x.toDouble())
        var rad = BigDecimal(dblRes, mc)
        for (i in 0 until 8) {
            val t = tan(rad, false, mc)
            val c = cos(rad, false, mc)
            val sec2 = BigDecimal.ONE.divide(c.multiply(c, mc), mc)
            val delta = t.subtract(x, mc).divide(sec2, mc)
            rad = rad.subtract(delta, mc)
            if (delta.abs() < BigDecimal.ONE.scaleByPowerOfTen(-mc.precision - 2)) break
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
        private var factorNesting = 0
        private val maxFactorNesting = 128

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
                    // Implied multiplication: 2PI, 2sin(30), 2(3+4), (2+3)(4+1)
                    // are factors at the same precedence as explicit '*' and '/'.
                    ch == '(' || ch == '.' || ch in '0'..'9' ||
                        ch in 'A'..'Z' || ch in 'a'..'z' -> x = x.multiply(parseFactor(), mc)
                    else -> return x
                }
            }
        }

        // Factor = (+/- Factor) or (Base ^ Factor) or Function / Number / Constant
        private fun parseFactor(): BigDecimal {
            // Parentheses, unary signs and power chains all recurse through
            // parseFactor. Reject excessively deep input before JVM stack overflow.
            if (factorNesting >= maxFactorNesting) {
                throw IllegalArgumentException("Expression nesting exceeds supported depth (128)")
            }
            factorNesting++
            try {
            if (eat('+')) return parseFactor()
            if (eat('-')) return parseFactor().negate()
            var x: BigDecimal
            val startPos = this.pos

            if (eat('(')) {
                x = parseExpression()
                if (!eat(')')) throw RuntimeException("Missing closing parenthesis")
            } else if ((ch in '0'..'9') || ch == '.') {
                while ((ch in '0'..'9') || ch == '.') nextChar()
                // Check for scientific notation exponent: e.g. 1.5E6 or 2e-4
                if (ch == 'E' || ch == 'e') {
                    // Consume E only when followed by actual exponent digits.
                    // Otherwise 'e' is the Euler constant: "2e" means "2 * e".
                    var i = pos + 1
                    if (i < str.length && (str[i] == '+' || str[i] == '-')) i++
                    val exponentDigitsStart = i
                    while (i < str.length && str[i] in '0'..'9') i++
                    if (i > exponentDigitsStart) {
                        while (pos < i) nextChar()
                    }
                }
                x = BigDecimal(str.substring(startPos, pos), mc)
            } else if (ch in 'A'..'Z' || ch in 'a'..'z') {
                while (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9') nextChar()
                val token = str.substring(startPos, pos)

                when (token.uppercase(Locale.US)) {
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

            return x
            } finally {
                factorNesting--
            }
        }
    }
}
