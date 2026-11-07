package com.example

import com.example.core.database.*
import com.example.features.communication.PagedChatReadingPolicy
import org.junit.Assert.*
import org.junit.Test

class PagedChatReadingPolicyTest {
    @Test fun onlyAuthorizedThreadRowsCanBeRendered() {
        val message = MessageEntity("private-id", "a", "own", "Owner", "PRIVATE-TEXT", timestamp = 123456789, status = "SENT")
        assertTrue(PagedChatReadingPolicy.visible(message, "a", "a"))
        assertFalse(PagedChatReadingPolicy.visible(message, "b", "a"))
        assertFalse(PagedChatReadingPolicy.visible(message, "b", "b"))
        assertFalse(PagedChatReadingPolicy.visible(message, null, "a"))
        assertFalse(PagedChatReadingPolicy.visible(null, "a", "a"))
        assertFalse(message.toString().contains("PRIVATE-TEXT"))
        assertFalse(message.toString().contains("private-id"))
        assertFalse(MessageHistoryCursor(123456789, "private-id").toString().contains("private-id"))
        assertFalse(MessageHistoryCursor(123456789, "private-id").toString().contains("123456789"))
    }
    @Test fun incomingBurstFollowsOnlyVisiblePreviousNewestAndNeverStealsHistory() {
        assertTrue(PagedChatReadingPolicy.shouldFollow(null, "first", null))
        assertTrue(PagedChatReadingPolicy.shouldFollow("old-head", "new-head-after-200", "old-head"))
        assertFalse(PagedChatReadingPolicy.shouldFollow("old-head", "new-head", "reading-older"))
        assertFalse(PagedChatReadingPolicy.shouldFollow("same", "same", "same"))
        assertFalse(PagedChatReadingPolicy.shouldFollow("old", null, "old"))
    }
}
