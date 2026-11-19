package com.example

import com.example.core.messaging.MessageQuarantine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageQuarantineTest {
    @Test fun metadataIsBoundedAndOnlyAuthenticatedRevisionCanClearIt() {
        var raw: String? = null
        repeat(200) { raw = MessageQuarantine.record(raw, "message-$it", "event-$it", 2, MessageQuarantine.Reason.MISSING_SENDER_KEY) }
        val items = JSONObject(raw!!)
        assertEquals(128, items.length())
        val id = items.keys().next()
        assertEquals(setOf("eventId", "revision", "reason"), items.getJSONObject(id).keys().asSequence().toSet())
        raw = MessageQuarantine.record(raw, id, "older-event", 1, MessageQuarantine.Reason.INVALID_CIPHERTEXT)
        assertEquals(2, JSONObject(raw!!).getJSONObject(id).getInt("revision"))
        assertTrue(JSONObject(MessageQuarantine.clearThrough(raw, id, 1)!!).has(id))
        assertFalse(JSONObject(MessageQuarantine.clearThrough(raw, id, 2)!!).has(id))
        assertNull(MessageQuarantine.clearThrough(MessageQuarantine.record(null, id, "event", 1,
            MessageQuarantine.Reason.MISSING_SENDER_KEY), id, 1))
    }
}
