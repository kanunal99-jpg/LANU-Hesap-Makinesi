package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.messaging.OlmRatchetNative
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OlmRatchetNativeRuntimeTest {
    @Test fun actualJniEncryptsInBothDirectionsWithEncryptedStateAndRejectsReplay() {
        val ak=ByteArray(32){3}; val bk=ByteArray(32){9}
        fun request(op:String)=JSONObject().put("op",op)
        try {
            val a=OlmRatchetNative.process(request("create"),ak)
            val b=OlmRatchetNative.process(request("create"),bk)
            val first=OlmRatchetNative.process(request("start")
                .put("account",a.getString("account"))
                .put("peerIdentity",b.getString("identity"))
                .put("peerOneTime",b.getString("oneTime"))
                .put("plaintext","native fixture one"),ak)
            val accepted=OlmRatchetNative.process(request("accept")
                .put("account",b.getString("account"))
                .put("peerIdentity",a.getString("identity"))
                .put("message",first.getJSONObject("message")),bk)
            assertEquals("native fixture one",accepted.getString("plaintext"))
            assertEquals(first.getString("sessionId"),accepted.getString("sessionId"))
            assertFalse(first.getString("session").contains("native fixture one"))
            val reply=OlmRatchetNative.process(request("send")
                .put("session",accepted.getString("session"))
                .put("sessionId",accepted.getString("sessionId"))
                .put("plaintext","native fixture two"),bk)
            val receive=request("receive").put("session",first.getString("session"))
                .put("sessionId",first.getString("sessionId"))
                .put("message",reply.getJSONObject("message"))
            val received=OlmRatchetNative.process(receive,ak)
            assertEquals("native fixture two",received.getString("plaintext"))
            assertTrue(runCatching { OlmRatchetNative.process(
                receive.put("session",received.getString("session")),ak) }.isFailure)
            assertTrue(runCatching { OlmRatchetNative.process(receive,bk) }.isFailure)
        } finally { ak.fill(0); bk.fill(0) }
    }
}
