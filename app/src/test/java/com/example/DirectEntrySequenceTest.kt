package com.example

import com.example.features.calculator.DirectEntrySequence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verification of deliberate keypad entry, independent from any real secret. */
class DirectEntrySequenceTest {
    @Test fun everyCharacterMustBeEnteredDirectlyAndConsecutively() {
        val guard = DirectEntrySequence("4321.")
        var expression = ""
        for (key in "4321.") {
            val next = expression + key
            val accepted = guard.observe(key.toString(), expression, next)
            if (key == '.') assertTrue(accepted) else assertFalse(accepted)
            expression = next
        }
    }

    @Test fun calculatorResultOrHistoryValueCannotSubstituteForDirectEntry() {
        val guard = DirectEntrySequence("4321.")
        assertFalse(guard.observe("=", "4000+321", "4321."))
        assertFalse(guard.observe(".", "4321", "4321."))
        assertFalse(guard.observe("4", "4321.", "4321.4"))
        assertFalse(guard.observe("4", "999", "9994"))
    }

    @Test fun resetOrNonDigitInterruptsTheEntireEntrySequence() {
        val guard = DirectEntrySequence("4321.")
        assertFalse(guard.observe("4", "", "4"))
        assertFalse(guard.observe("+", "4", "4+"))
        assertFalse(guard.observe("3", "4+", "4+3"))
        guard.reset()
        var expression = ""
        for (key in "4321.") {
            val next = expression + key
            val accepted = guard.observe(key.toString(), expression, next)
            if (key == '.') assertTrue(accepted) else assertFalse(accepted)
            expression = next
        }
    }

    @Test fun clearingCalculatorStartsFreshDirectSequenceImmediately() {
        val guard = DirectEntrySequence("4321.")
        assertFalse(guard.observe("4", "", "4"))
        // A reset dialog clears expression without passing through the
        // direct keypad handler; a fresh first key must still work.
        assertFalse(guard.observe("4", "", "4"))
        assertFalse(guard.observe("3", "4", "43"))
        assertFalse(guard.observe("2", "43", "432"))
        assertFalse(guard.observe("1", "432", "4321"))
        assertTrue(guard.observe(".", "4321", "4321."))
    }

    @Test fun partialHistoryMatchesCannotResumeSecretSequence() {
        val guard = DirectEntrySequence("4321.")
        assertFalse(guard.observe("4", "", "4"))
        // External history mutation changes the expression while our
        // contiguous direct-entry candidate still records only the first key.
        assertFalse(guard.observe(".", "4321", "4321."))
        assertFalse(guard.observe(".", "4321.", "4321.."))
    }
}
