package com.example.features.communication

/**
 * Keeps incoming private messages from stealing the reader's position.
 * Indices are taken from LazyListState before applying a new scroll request.
 * Pure policy: no message content, read receipts or network side effects.
 */
internal object ChatReadingPositionPolicy {
    fun isNearLatest(messageCount: Int, lastVisibleIndex: Int): Boolean =
        messageCount > 0 && lastVisibleIndex >= messageCount - 2

    fun shouldAutoScroll(
        previousCount: Int,
        newCount: Int,
        lastVisibleIndex: Int
    ): Boolean {
        if (newCount <= 0) return false
        // Opening a conversation should still reveal the latest message.
        if (previousCount <= 0) return true
        // Read receipts, edits and conversation clearing must not jump.
        if (newCount <= previousCount) return false
        return isNearLatest(previousCount, lastVisibleIndex)
    }
}
