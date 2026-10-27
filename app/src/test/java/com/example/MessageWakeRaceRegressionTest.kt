package com.example

import com.example.core.messaging.MessageWakePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notification race regression: no private payload, no duplicate delivery, no alert after opt-out. */
class MessageWakeRaceRegressionTest {
    @Test fun optOutBeforePostNeverPosts() {
        var posts = 0
        val ok = MessageWakePolicy.postIfStillEnabled({ false }, { posts++ }, {})
        assertFalse(ok)
        assertEquals(0, posts)
    }

    @Test fun optOutDuringPostRetractsNotification() {
        var enabled = true
        var posts = 0
        var cancels = 0
        val ok = MessageWakePolicy.postIfStillEnabled(
            { enabled },
            { posts++; enabled = false },
            { cancels++ }
        )
        assertFalse(ok)
        assertEquals(1, posts)
        assertEquals(1, cancels)
    }

    @Test fun consentMaintainedPostsWithoutCancellation() {
        var posts = 0
        var cancels = 0
        assertTrue(MessageWakePolicy.postIfStillEnabled({ true }, { posts++ }, { cancels++ }))
        assertEquals(1, posts)
        assertEquals(0, cancels)
    }

    @Test fun malformedOrPrivateFcmPayloadIsRejected() {
        val now = 1_710_000_000_000L
        val valid = mapOf("type" to MessageWakePolicy.TYPE, "at" to now.toString())
        assertTrue(MessageWakePolicy.eligible(valid, now))
        assertFalse(MessageWakePolicy.eligible(valid + ("sender" to "private"), now))
        assertFalse(MessageWakePolicy.eligible(valid + ("body" to "private"), now))
        assertFalse(MessageWakePolicy.eligible(valid + ("conversation_id" to "private"), now))
        assertFalse(MessageWakePolicy.eligible(valid + ("at" to "NaN"), now))
    }

    @Test fun platformFailureReturnsSafeDefault() {
        assertFalse(MessageWakePolicy.safeShow { throw SecurityException("permission denied") })
        assertFalse(MessageWakePolicy.safeShow { throw IllegalStateException("notification service unavailable") })
    }
}
