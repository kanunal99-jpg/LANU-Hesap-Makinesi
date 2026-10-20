package com.example.core.security

object PinPolicy {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 30_000L
    // First lock remains compatible with existing users; repeated five-try bursts
    // cost increasingly more time, even after app restarts.
    private val LOCKOUT_TIERS_MS = longArrayOf(
        LOCKOUT_MS, 2 * 60_000L, 10 * 60_000L, 60 * 60_000L, 12 * 60 * 60_000L
    )
    const val MAX_LOCKOUT_MS = 12 * 60 * 60_000L
    const val MAX_LOCKOUT_STRIKES = 5

    fun lockoutForStrike(strike: Int): Long =
        LOCKOUT_TIERS_MS[(strike - 1).coerceIn(0, LOCKOUT_TIERS_MS.lastIndex)]

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}

data class PinAttemptStatus(val failedAttempts: Int = 0, val remainingSeconds: Int = 0)
