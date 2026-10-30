package com.example

import com.example.core.database.MessageEntity
import com.example.core.repository.MessageRetryEligibility
import org.junit.Assert.*
import org.junit.Test

class MessageRetryEligibilityTest {
    private fun message(sender: String = "own", status: String = "FAILED", thread: String = "chat-a") =
        MessageEntity(
            id = "synthetic-id", conversationId = thread, senderPhone = sender,
            senderName = "synthetic", content = "synthetic-encrypted-payload",
            timestamp = 1L, status = status
        )

    @Test fun originalThreadCanRetryOnlyFailedOwnMessage() {
        assertTrue(MessageRetryEligibility.canRetry(message(), "own", "chat-a"))
        assertFalse(MessageRetryEligibility.canRetry(message(status = "SENT"), "own", "chat-a"))
        assertFalse(MessageRetryEligibility.canRetry(message(status = "PENDING"), "own", "chat-a"))
        assertFalse(MessageRetryEligibility.canRetry(message(sender = "peer"), "own", "chat-a"))
    }

    @Test fun staleConversationAndMissingIdentityFailClosed() {
        assertFalse(MessageRetryEligibility.canRetry(message(), "own", "chat-b"))
        assertFalse(MessageRetryEligibility.canRetry(message(thread = "chat-b"), "own", "chat-a"))
        assertFalse(MessageRetryEligibility.canRetry(message(), "own", ""))
        assertFalse(MessageRetryEligibility.canRetry(message(), "", "chat-a"))
    }

    @Test fun legacySingleRetryStillRequiresOwnerAndFailedState() {
        assertTrue(MessageRetryEligibility.canRetry(message(), "own", null))
        assertFalse(MessageRetryEligibility.canRetry(message(sender = "peer"), "own", null))
        assertFalse(MessageRetryEligibility.canRetry(message(status = "WAITING"), "own", null))
    }
}
