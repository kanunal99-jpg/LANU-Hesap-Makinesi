package com.example.core.security

object PinPolicy {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 30_000L

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}

data class PinAttemptStatus(val failedAttempts: Int = 0, val remainingSeconds: Int = 0)
