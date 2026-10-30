package com.example.core.repository

import com.example.core.database.MessageEntity

/**
 * Fail-closed eligibility check shared by single-message and thread-bound batch retry.
 * Batch callers must bind the original conversation ID; an ID from another private
 * thread must never be requeued after navigation or a stale menu callback.
 */
internal object MessageRetryEligibility {
    fun canRetry(message: MessageEntity, ownId: String, expectedConversationId: String?): Boolean =
        ownId.isNotBlank() &&
            message.senderPhone == ownId &&
            message.status == "FAILED" &&
            (expectedConversationId == null ||
                (expectedConversationId.isNotBlank() &&
                    message.conversationId == expectedConversationId))
}
