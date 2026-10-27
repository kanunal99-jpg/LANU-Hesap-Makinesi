package com.example

import com.example.features.communication.ChatReadingPositionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadingPositionPolicyTest {
    @Test fun openingThreadShowsNewestButEmptyThreadDoesNotScroll() {
        assertTrue(ChatReadingPositionPolicy.shouldAutoScroll(0, 30, -1))
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(0, 0, -1))
    }

    @Test fun incomingMessagesDoNotStealViewportWhileReadingHistory() {
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(30, 31, 9))
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(30, 35, -1))
        assertFalse(ChatReadingPositionPolicy.isNearLatest(30, 9))
    }

    @Test fun incomingMessagesScrollOnlyForReadersAtLatest() {
        assertTrue(ChatReadingPositionPolicy.shouldAutoScroll(30, 31, 29))
        assertTrue(ChatReadingPositionPolicy.shouldAutoScroll(30, 31, 28))
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(30, 31, 27))
        assertTrue(ChatReadingPositionPolicy.isNearLatest(31, 30))
    }

    @Test fun editsReadReceiptsAndDeletionsDoNotForceJump() {
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(30, 30, 29))
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(30, 29, 28))
        assertFalse(ChatReadingPositionPolicy.shouldAutoScroll(30, 0, 29))
    }

    @Test fun singleMessageBoundaryAndStaleScrollState() {
        assertTrue(ChatReadingPositionPolicy.shouldAutoScroll(1, 2, 0))
        assertFalse(ChatReadingPositionPolicy.isNearLatest(0, 0))
        assertFalse(ChatReadingPositionPolicy.isNearLatest(12, -1))
    }
}
