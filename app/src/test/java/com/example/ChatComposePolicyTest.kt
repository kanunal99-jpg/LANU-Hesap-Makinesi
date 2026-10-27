package com.example

import com.example.features.communication.ChatComposePolicy
import org.junit.Assert.*
import org.junit.Test

class ChatComposePolicyTest {
    @Test fun queuedValidDraftRequiresTheCorrectThread() {
        assertTrue(ChatComposePolicy.canSend("Merhaba", false, "thread-a", "thread-a"))
    }

    @Test fun staleActiveConversationCannotReceiveNextThreadsMessage() {
        assertFalse(ChatComposePolicy.canSend("Gizli mesaj", false, "thread-a", "thread-b"))
        assertFalse(ChatComposePolicy.canSend("Gizli mesaj", false, null, "thread-b"))
    }

    @Test fun sendingAndInvalidDraftCannotDispatch() {
        assertFalse(ChatComposePolicy.canSend("Yeni mesaj", true, "thread-a", "thread-a"))
        assertFalse(ChatComposePolicy.canSend("   ", false, "thread-a", "thread-a"))
        assertFalse(ChatComposePolicy.canSend("", false, "thread-a", "thread-a"))
        assertFalse(ChatComposePolicy.canSend("a".repeat(8001), false, "thread-a", "thread-a"))
        assertFalse(ChatComposePolicy.canSend("a", false, "", ""))
    }

    @Test fun maximumValidLengthCanBeQueuedWithoutRequiringConnectivity() {
        assertTrue(ChatComposePolicy.canSend("a".repeat(8000), false, "thread-a", "thread-a"))
    }

    @Test fun closingDuringDatabaseLoadAlwaysOwnsPendingPrivateThread() {
        // The coroutine may not yet have filled activeConversation.
        assertTrue(ChatComposePolicy.shouldClose("thread-b", "thread-b", null))
        assertTrue(ChatComposePolicy.shouldClose("thread-a", "thread-a", "thread-a"))
    }

    @Test fun lateOldLifecycleCallbackCannotCloseNewPrivateThread() {
        assertFalse(ChatComposePolicy.shouldClose("thread-a", "thread-b", null))
        assertFalse(ChatComposePolicy.shouldClose("thread-a", "thread-b", "thread-b"))
        assertFalse(ChatComposePolicy.shouldClose("thread-a", null, null))
    }

    @Test fun blankOrMismatchedCloseNeverTouchesExistingConversation() {
        assertFalse(ChatComposePolicy.shouldClose("", "thread-b", "thread-b"))
        assertFalse(ChatComposePolicy.shouldClose("thread-c", "thread-b", "thread-b"))
        assertTrue(ChatComposePolicy.shouldClose("thread-b", null, "thread-b"))
    }
}
