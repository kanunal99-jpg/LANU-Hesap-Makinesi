package com.example.core.messaging

/**
 * A transport-level 2xx is not proof that the authenticated relay accepted
 * the encrypted message. Keep the original message ID and ciphertext queued
 * when the returned receipt is absent or not an explicitly supported state.
 *
 * No recipient, payload, server response, or private metadata is logged.
 */
internal object MessageSendReceiptPolicy {
    private val accepted = setOf("SENT", "DELIVERED", "READ")

    fun requireAccepted(status: String): String {
        if (status !in accepted) {
            // 503 is treated as a bounded transient failure by the existing
            // durable outbox; a retry reuses message.id and ciphertext.
            throw RelayException(503, "Mesaj teslim onayı doğrulanamadı. Mesajınız korunuyor.")
        }
        return status
    }
}
