package com.example.features.communication

import com.example.core.database.MessageEntity

internal object PagedChatReadingPolicy {
    fun visible(message: MessageEntity?, activeId: String?, screenId: String): Boolean =
        screenId.isNotBlank() && activeId == screenId && message?.conversationId == screenId

    fun shouldFollow(previousNewestId: String?, newestId: String?, firstVisibleKey: Any?): Boolean =
        newestId != null && newestId != previousNewestId &&
            (previousNewestId == null || firstVisibleKey == previousNewestId)
}
