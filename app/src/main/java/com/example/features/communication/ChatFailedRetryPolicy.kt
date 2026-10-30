package com.example.features.communication

import com.example.core.database.MessageEntity

/**
 * Select only this user's failed messages in the currently authenticated thread.
 * The existing repository retryMessage preserves the original message ID and
 * E2EE payload, so this feature never creates a new plaintext or duplicate send.
 */
internal object ChatFailedRetryPolicy {
    const val MAX_BATCH = 20

    fun select(
        messages: List<MessageEntity>,
        activeConversationId: String?,
        screenConversationId: String,
        ownId: String
    ): List<String> {
        if (screenConversationId.isBlank() || ownId.isBlank() ||
            activeConversationId != screenConversationId) return emptyList()
        return messages.asSequence()
            .filter {
                it.conversationId == screenConversationId &&
                    it.senderPhone == ownId && it.status == "FAILED"
            }
            .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
            .map { it.id }
            .distinct()
            .take(MAX_BATCH)
            .toList()
    }
}
