package com.example.features.communication

/**
 * The private-area account-erasure confirmation is deliberate and exact.
 * UI strings are never a substitute for the owner's signed relay request,
 * the server's checked current code, or the production feature gate.
 */
internal object AccountDeletionConsentPolicy {
    const val PHRASE = "DELETE_ACCOUNT"

    fun accepted(
        currentCode: String,
        enteredCode: String,
        enteredPhrase: String,
        backupAcknowledged: Boolean,
        preflightReady: Boolean
    ): Boolean =
        currentCode.isNotBlank() &&
        enteredCode == currentCode &&
        enteredPhrase == PHRASE &&
        backupAcknowledged &&
        preflightReady
}
