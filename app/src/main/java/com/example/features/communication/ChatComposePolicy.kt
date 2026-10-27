package com.example.features.communication

/**
 * UI and sending guard for cross-conversation navigation. A composer must
 * never dispatch to a stale selected peer while the new thread is loading.
 * Offline delivery is deliberately allowed: encrypted Room queue owns it.
 */
internal object ChatComposePolicy {
    fun canSend(
        draft: String,
        sending: Boolean,
        activeConversationId: String?,
        screenConversationId: String
    ): Boolean = !sending && draft.isNotBlank() && draft.length <= 8000 &&
        screenConversationId.isNotBlank() && activeConversationId == screenConversationId
}
