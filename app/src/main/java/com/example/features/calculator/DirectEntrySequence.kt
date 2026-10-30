package com.example.features.calculator

/**
 * Recognize a deliberate, contiguous keypad/keyboard entry only.
 * Restored expressions, calculation results, history items and indirect
 * mutations never constitute an entry gesture on their own.
 *
 * This helper does not own, store or print any credential or user identity.
 */
internal class DirectEntrySequence(private val code: String) {
    private var typed = ""

    fun observe(key: String, expressionBefore: String, expressionAfter: String): Boolean {
        // AC/clear or a reset dialog can empty the expression without a
        // direct key event reaching this guard. Permit a fresh sequence.
        if (expressionBefore.isEmpty()) typed = ""
        if (key.length != 1 || key[0] !in "0123456789." ||
            expressionBefore != typed || typed.length >= code.length) {
            typed = ""
            return false
        }

        typed += key
        val granted = typed == code && expressionAfter == typed
        if (granted) typed = ""
        return granted
    }

    fun reset() {
        typed = ""
    }
}
