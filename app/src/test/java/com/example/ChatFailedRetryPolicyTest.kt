package com.example

import com.example.core.database.MessageEntity
import com.example.features.communication.ChatFailedRetryPolicy
import org.junit.Assert.*
import org.junit.Test

class ChatFailedRetryPolicyTest {
    private fun msg(
        id: String, conversation: String = "chat-a", sender: String = "own",
        status: String = "FAILED", timestamp: Long = 10L
    ) = MessageEntity(
        id = id, conversationId = conversation, senderPhone = sender,
        senderName = "synthetic", content = "encrypted-placeholder",
        timestamp = timestamp, status = status
    )

    @Test fun onlyFailedOwnMessagesInActiveThreadAreRetryable() {
        val messages = listOf(
            msg("old", timestamp = 1),
            msg("sent", status = "SENT"),
            msg("pending", status = "PENDING"),
            msg("foreign", sender = "peer"),
            msg("other-thread", conversation = "chat-b"),
            msg("new", timestamp = 20),
            msg("old", timestamp = 1)
        )
        assertEquals(listOf("old", "new"),
            ChatFailedRetryPolicy.select(messages, "chat-a", "chat-a", "own"))
    }

    @Test fun navigationAndMissingIdentityFailClosed() {
        val messages = listOf(msg("private"))
        assertTrue(ChatFailedRetryPolicy.select(messages, "chat-b", "chat-a", "own").isEmpty())
        assertTrue(ChatFailedRetryPolicy.select(messages, null, "chat-a", "own").isEmpty())
        assertTrue(ChatFailedRetryPolicy.select(messages, "chat-a", "chat-a", "").isEmpty())
        assertTrue(ChatFailedRetryPolicy.select(messages, "chat-a", "", "own").isEmpty())
    }

    @Test fun largeOfflineOutboxRetriesOldestTwentyOnly() {
        val messages = (0..29).map { msg("m-$it", timestamp = it.toLong()) }
        val selected = ChatFailedRetryPolicy.select(messages.reversed(), "chat-a", "chat-a", "own")
        assertEquals(ChatFailedRetryPolicy.MAX_BATCH, selected.size)
        assertEquals("m-0", selected.first())
        assertEquals("m-19", selected.last())
    }
}
