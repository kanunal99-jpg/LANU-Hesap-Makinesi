package com.example.core.security

/**
 * Narrow, opt-in convenience exception for a system media/document picker.
 *
 * This MUST NOT be used for the first private-area unlock, regular home/app
 * switches, screen-off events, process restarts, or manual "Lock and return".
 * The Activity holds the in-flight picker state only in its volatile memory.
 */
object MediaPickerReturnPolicy {
    const val MAX_PICKER_SESSION_MS: Long = 2 * 60_000L

    fun mayResumeWithoutPin(
        requirePinOnReturn: Boolean,
        pickerStartedElapsedMs: Long?,
        nowElapsedMs: Long,
        isUnlocked: Boolean,
        screenWasLocked: Boolean
    ): Boolean {
        val started = pickerStartedElapsedMs ?: return false
        if (requirePinOnReturn || !isUnlocked || screenWasLocked) return false
        // Always fail closed on corrupt timestamps, clock resets, or long trips away.
        if (started < 0L || nowElapsedMs < started) return false
        return nowElapsedMs - started <= MAX_PICKER_SESSION_MS
    }
}
