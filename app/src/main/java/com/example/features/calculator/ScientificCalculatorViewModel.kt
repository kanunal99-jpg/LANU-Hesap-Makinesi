package com.example.features.calculator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.calculator.CalculatorEngine
import com.example.core.calculator.HighPrecisionMathEngine
import com.example.core.database.CalculationHistoryEntity
import com.example.core.repository.CalculatorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * Angle unit representation for scientific trigonometric calculations.
 */
enum class AngleUnit {
    DEGREES,
    RADIANS
}

/**
 * Numerical notation modes for scientific calculations.
 */
enum class NotationMode {
    STANDARD,
    SCIENTIFIC,
    ENGINEERING
}

/**
 * Immutable UI State representing the complete current calculator state and operation context.
 */
data class ScientificCalculatorUiState(
    val expression: String = "",
    val result: String = "",
    val primaryDisplay: String = "0",
    val secondaryDisplay: String = "",
    val currentOperand: String = "",
    val pendingOperation: String? = null,
    val storedOperand: BigDecimal? = null,
    val isDegrees: Boolean = true,
    val angleUnit: AngleUnit = AngleUnit.DEGREES,
    val isScientific: Boolean = false,
    val precisionDigits: Int = 16,
    val notationMode: NotationMode = NotationMode.STANDARD,
    val highPrecisionMemory: BigDecimal = BigDecimal.ZERO,
    val isNewInput: Boolean = true,
    val hasActiveCalculation: Boolean = false,
    val errorMessage: String? = null
)

/**
 * ViewModel that handles high-precision scientific calculations (using BigDecimal and MathContext)
 * and maintains the complete calculator state for active and pending operations.
 */
open class ScientificCalculatorViewModel(
    protected val repository: CalculatorRepository
) : ViewModel() {

    // Comprehensive operation and UI state
    protected val _uiState = MutableStateFlow(ScientificCalculatorUiState())
    val uiState: StateFlow<ScientificCalculatorUiState> = _uiState.asStateFlow()

    // Backward-compatible individual StateFlows
    protected val _expression = MutableStateFlow("")
    val expression: StateFlow<String> = _expression.asStateFlow()

    protected val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result.asStateFlow()

    protected val _isDegrees = MutableStateFlow(true)
    val isDegrees: StateFlow<Boolean> = _isDegrees.asStateFlow()

    protected val _isScientific = MutableStateFlow(false)
    val isScientific: StateFlow<Boolean> = _isScientific.asStateFlow()

    val history: Flow<List<CalculationHistoryEntity>> = repository.history
    val memory: StateFlow<Double> = repository.memoryValue

    private val mathContext: MathContext
        get() = MathContext(_uiState.value.precisionDigits + 18, RoundingMode.HALF_UP)

    init {
        viewModelScope.launch {
            val precision = repository.getDecimalPrecision()
            setPrecision(precision.coerceIn(4, 50))
        }
    }

    /**
     * Toggles between Degrees and Radians angle measurement.
     */
    fun toggleDegrees() {
        val newDegrees = !_isDegrees.value
        _isDegrees.value = newDegrees
        _uiState.update { current ->
            current.copy(
                isDegrees = newDegrees,
                angleUnit = if (newDegrees) AngleUnit.DEGREES else AngleUnit.RADIANS
            )
        }
        evaluateRealtime()
    }

    /**
     * Sets explicit angle unit.
     */
    fun setAngleUnit(unit: AngleUnit) {
        val degrees = (unit == AngleUnit.DEGREES)
        _isDegrees.value = degrees
        _uiState.update { current ->
            current.copy(isDegrees = degrees, angleUnit = unit)
        }
        evaluateRealtime()
    }

    /**
     * Toggles scientific mode keypad layout.
     */
    fun toggleScientific() {
        val newSci = !_isScientific.value
        _isScientific.value = newSci
        _uiState.update { it.copy(isScientific = newSci) }
    }

    /**
     * Sets desired decimal precision scale for calculations.
     */
    fun setPrecision(digits: Int) {
        val validDigits = digits.coerceIn(2, 50)
        _uiState.update { it.copy(precisionDigits = validDigits) }
        evaluateRealtime()
    }

    /**
     * Sets numerical notation mode (Standard, Scientific, Engineering).
     */
    fun setNotationMode(mode: NotationMode) {
        _uiState.update { it.copy(notationMode = mode) }
        evaluateRealtime()
    }

    /**
     * Appends a numerical digit (0-9) to the current expression or operand.
     */
    fun inputDigit(digit: String) {
        _expression.update { it + digit }
        syncUiState()
        evaluateRealtime()
    }

    /**
     * Appends decimal point to the expression if permissible.
     */
    fun inputDecimal() {
        val current = _expression.value
        val lastToken = current.split(Regex("[+\\-*×/÷%^()]")).lastOrNull() ?: ""
        if (!lastToken.contains(".")) {
            _expression.update { if (it.isEmpty() || it.endsWith(" ") || it.endsWith("(")) it + "0." else it + "." }
            syncUiState()
            evaluateRealtime()
        }
    }

    /**
     * Appends binary operator (+, -, ×, ÷, %, ^, mod).
     */
    fun inputOperator(op: String) {
        val current = _expression.value
        val formattedOp = when (op) {
            "×", "*" -> "×"
            "÷", "/" -> "÷"
            "mod", "%" -> " mod "
            else -> op
        }

        if (current.isBlank() && (op == "-" || op == "+")) {
            _expression.value = op
        } else if (current.isNotBlank()) {
            _expression.update { it + formattedOp }
        }
        syncUiState()
        evaluateRealtime()
    }

    /**
     * Appends a scientific function with opening parenthesis.
     */
    fun inputScientificFunction(func: String) {
        val current = _expression.value
        val functionName = when (func) {
            "asin", "sin⁻¹" -> "asin"
            "acos", "cos⁻¹" -> "acos"
            "atan", "tan⁻¹" -> "atan"
            "10ˣ" -> "10^"
            "eˣ" -> "E^"
            "x²" -> "^2"
            "x³" -> "^3"
            "xʸ" -> "^"
            "factorial", "n!" -> "!"
            else -> func
        }

        if (functionName.endsWith("^") || functionName.startsWith("^") || functionName == "!") {
            _expression.update { it + functionName }
        } else {
            _expression.update { it + "$functionName(" }
        }
        syncUiState()
        evaluateRealtime()
    }

    /**
     * Appends scientific constant (PI or E).
     */
    fun inputConstant(constant: String) {
        val symbol = when (constant.uppercase(Locale.US)) {
            "PI", "π" -> "π"
            "E" -> "e"
            else -> constant
        }
        _expression.update { it + symbol }
        syncUiState()
        evaluateRealtime()
    }

    /**
     * Evaluates the current expression with high precision and commits to history.
     */
    fun calculate(saveHistory: Boolean = true): BigDecimal? {
        val expr = _expression.value
        if (expr.isBlank()) return null
        if (expr.contains("x") || expr.contains("X")) {
            return null
        }

        return try {
            val evalResult = evaluateExpression(expr)
            val formatted = formatBigDecimal(evalResult)

            _result.value = formatted
            _expression.value = formatted

            _uiState.update {
                it.copy(
                    expression = formatted,
                    result = formatted,
                    primaryDisplay = formatted,
                    secondaryDisplay = "$expr = $formatted",
                    hasActiveCalculation = true,
                    errorMessage = null,
                    isNewInput = true
                )
            }

            if (saveHistory) {
                viewModelScope.launch {
                    repository.saveToHistory(expr, formatted)
                }
            }
            evalResult
        } catch (e: Exception) {
            _result.value = "Error"
            _uiState.update {
                it.copy(
                    result = "Error",
                    primaryDisplay = "Error",
                    errorMessage = e.localizedMessage ?: "Calculation Error"
                )
            }
            null
        }
    }

    /**
     * High precision evaluation core function using HighPrecisionMathEngine.
     */
    fun evaluateExpression(expr: String): BigDecimal {
        return HighPrecisionMathEngine.evaluate(
            expression = expr,
            isDegrees = _isDegrees.value,
            mathContext = mathContext
        )
    }

    /**
     * Performs real-time preview evaluation of the expression as the user types.
     */
    protected fun evaluateRealtime() {
        val expr = _expression.value
        if (expr.isNotBlank()) {
            if (expr.contains("x") || expr.contains("X")) {
                _result.value = "f(x) Grafik Çizimi Aktif 📈"
                _uiState.update {
                    it.copy(
                        result = "f(x) Grafik Çizimi Aktif 📈",
                        primaryDisplay = expr,
                        secondaryDisplay = "Grafik Modu",
                        hasActiveCalculation = true,
                        errorMessage = null
                    )
                }
                return
            }
            try {
                var parseExpr = expr
                if (parseExpr.endsWith("+") || parseExpr.endsWith("-") || parseExpr.endsWith("×") ||
                    parseExpr.endsWith("÷") || parseExpr.endsWith("*") || parseExpr.endsWith("/") ||
                    parseExpr.endsWith("^") || parseExpr.endsWith(" mod ")
                ) {
                    parseExpr = parseExpr.trimEnd().dropLast(1).trimEnd()
                }

                if (parseExpr.isNotBlank()) {
                    val evalResult = evaluateExpression(parseExpr)
                    val formatted = formatBigDecimal(evalResult)
                    _result.value = formatted
                    _uiState.update {
                        it.copy(
                            result = formatted,
                            primaryDisplay = formatted,
                            secondaryDisplay = expr,
                            hasActiveCalculation = true,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                // Keep the previous result or blank during active typing
            }
        } else {
            _result.value = ""
            _uiState.update {
                it.copy(
                    result = "",
                    primaryDisplay = "0",
                    secondaryDisplay = "",
                    hasActiveCalculation = false,
                    errorMessage = null
                )
            }
        }
    }

    /**
     * Clears the current expression and reset result (All Clear).
     */
    fun allClear() {
        _expression.value = ""
        _result.value = ""
        _uiState.update {
            it.copy(
                expression = "",
                result = "",
                primaryDisplay = "0",
                secondaryDisplay = "",
                currentOperand = "",
                pendingOperation = null,
                storedOperand = null,
                hasActiveCalculation = false,
                errorMessage = null,
                isNewInput = true
            )
        }
    }

    /**
     * Clears current entry only.
     */
    fun clearEntry() {
        _expression.value = ""
        _uiState.update {
            it.copy(
                expression = "",
                primaryDisplay = "0",
                hasActiveCalculation = false
            )
        }
    }

    /**
     * Deletes the last character from current expression.
     */
    fun backspace() {
        val current = _expression.value
        if (current.isNotEmpty()) {
            _expression.value = current.substring(0, current.length - 1)
        }
        syncUiState()
        evaluateRealtime()
    }

    /**
     * Toggles positive / negative sign of the active formula or operand.
     */
    fun toggleSign() {
        val expr = _expression.value
        if (expr.isNotEmpty()) {
            if (expr.startsWith("-")) {
                _expression.value = expr.substring(1)
            } else {
                _expression.value = "-$expr"
            }
            syncUiState()
            evaluateRealtime()
        }
    }

    // Memory operations with high precision
    fun memoryClear() {
        repository.clearMemory()
        _uiState.update { it.copy(highPrecisionMemory = BigDecimal.ZERO) }
    }

    fun memoryRecall() {
        val mem = _uiState.value.highPrecisionMemory
        val formatted = formatBigDecimal(mem)
        _expression.update { it + formatted }
        syncUiState()
        evaluateRealtime()
    }

    fun memoryAdd(amount: BigDecimal? = null) {
        try {
            val valueToAdd = amount ?: evaluateExpression(_expression.value.ifBlank { "0" })
            _uiState.update { current ->
                val newMem = current.highPrecisionMemory.add(valueToAdd, mathContext)
                repository.addToMemory(valueToAdd.toDouble())
                current.copy(highPrecisionMemory = newMem)
            }
        } catch (e: Exception) {}
    }

    fun memorySubtract(amount: BigDecimal? = null) {
        try {
            val valueToSub = amount ?: evaluateExpression(_expression.value.ifBlank { "0" })
            _uiState.update { current ->
                val newMem = current.highPrecisionMemory.subtract(valueToSub, mathContext)
                repository.subtractFromMemory(valueToSub.toDouble())
                current.copy(highPrecisionMemory = newMem)
            }
        } catch (e: Exception) {}
    }

    fun memoryStore(value: BigDecimal? = null) {
        try {
            val valueToStore = value ?: evaluateExpression(_expression.value.ifBlank { "0" })
            _uiState.update { current ->
                repository.setMemory(valueToStore.toDouble())
                current.copy(highPrecisionMemory = valueToStore)
            }
        } catch (e: Exception) {}
    }

    /**
     * Synchronizes state helper.
     */
    protected fun syncUiState() {
        val expr = _expression.value
        _uiState.update {
            it.copy(
                expression = expr,
                primaryDisplay = expr.ifBlank { "0" },
                hasActiveCalculation = expr.isNotBlank() || (_result.value.isNotBlank() && _result.value != "0")
            )
        }
    }

    /**
     * High precision formatting helper.
     */
    private fun formatBigDecimal(value: BigDecimal): String {
        val precision = _uiState.value.precisionDigits
        val isSci = _uiState.value.notationMode == NotationMode.SCIENTIFIC
        return HighPrecisionMathEngine.format(value, precision, isSci)
    }

    /**
     * Unified key press dispatcher matching the UI keypad.
     */
    fun onKeyPress(key: String) {
        when (key) {
            "AC" -> allClear()
            "C", "CE" -> clearEntry()
            "Backspace" -> backspace()
            "=" -> { calculate() }
            "±" -> toggleSign()
            "%" -> inputOperator("mod")
            "x²", "x³", "xʸ", "10ˣ", "eˣ", "factorial" -> inputScientificFunction(key)
            "MC" -> memoryClear()
            "MR" -> memoryRecall()
            "M+" -> memoryAdd()
            "M-" -> memorySubtract()
            "MS" -> memoryStore()
            "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "log", "ln", "sqrt", "cbrt" -> {
                inputScientificFunction(key)
            }
            "π", "e" -> inputConstant(key)
            else -> {
                _expression.value += key
                syncUiState()
                evaluateRealtime()
            }
        }
    }

    fun loadHistoryItem(item: String) {
        _expression.value = item
        syncUiState()
        evaluateRealtime()
    }

    fun appendExpression(text: String) {
        _expression.value += text
        syncUiState()
        evaluateRealtime()
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteHistoryItem(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun smartClearHistory(daysToKeep: Int = 7) {
        viewModelScope.launch {
            val timestampThreshold = System.currentTimeMillis() - (daysToKeep * 24L * 60L * 60L * 1000L)
            repository.deleteHistoryOlderThan(timestampThreshold)
        }
    }

    class Factory(private val repository: CalculatorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScientificCalculatorViewModel(repository) as T
        }
    }
}
