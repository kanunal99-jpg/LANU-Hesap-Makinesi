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

    /**
     * A LifecycleResumeEffect may dispose while Room is still loading a chat.
     * In that state the selected recipient is pending but activeConversation
     * is null. Closing must still cancel its collector and clear its draft.
     *
     * A delayed pause from an OLD screen must not close a newer chat.
     */
    fun shouldClose(
        exitingConversationId: String,
        selectedConversationId: String?,
        loadedConversationId: String?
    ): Boolean = exitingConversationId.isNotBlank() &&
        (exitingConversationId == selectedConversationId ||
            exitingConversationId == loadedConversationId)
}
