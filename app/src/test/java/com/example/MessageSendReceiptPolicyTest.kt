package com.example

import com.example.core.messaging.MessageSendReceiptPolicy
import com.example.core.messaging.RelayException
import org.junit.Assert.*
import org.junit.Test

class MessageSendReceiptPolicyTest {
    @Test fun allRealRelayAcknowledgementsAreAcceptedWithoutDowngrade() {
        for (status in listOf("SENT", "DELIVERED", "READ")) {
            assertEquals(status, MessageSendReceiptPolicy.requireAccepted(status))
        }
    }

    @Test fun emptyMalformedAndForgedStatusesCannotMakeAnOutboxMessageSent() {
        for (status in listOf("", "PENDING", "WAITING", "FAILED", "SENDING", "sent",
            " SENT", "SENT ", "SUCCESS", "OK", "queued", "READ\\n", "private message or peer details")) {
            try {
                MessageSendReceiptPolicy.requireAccepted(status)
                fail("Invalid or ambiguous receipt must fail closed")
            } catch (e: RelayException) {
                assertEquals(503, e.status)
                assertFalse(e.message.orEmpty().contains(status.takeIf { it.length > 3 } ?: "\u0000"))
            }
        }
    }

    @Test fun retryFailureKeepsAStablePrivateErrorAndDefaultBound() {
        val failure = try {
            MessageSendReceiptPolicy.requireAccepted("{}")
            fail("Missing status must not become SENT")
            return
        } catch (e: RelayException) { e }
        assertEquals(503, failure.status)
        assertEquals(60_000L, failure.retryAfterMillis)
        assertEquals("Mesaj teslim onayı doğrulanamadı. Mesajınız korunuyor.", failure.message)
    }
}
