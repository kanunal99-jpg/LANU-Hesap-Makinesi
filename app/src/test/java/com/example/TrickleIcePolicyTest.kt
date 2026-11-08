package com.example

import com.example.core.calls.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrickleIcePolicyTest {
    @Test fun oldOfferRemainsCompleteAndCapabilityRequiresExactAttribute() {
        val sdp = "v=0\r\na=ice-ufrag:abcd1234\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=candidate:1 1 udp 1 192.0.2.1 1234 typ host\r\n"
        val advertised = TrickleIcePolicy.advertise(sdp)
        assertTrue(advertised.contains("a=candidate:"))
        assertTrue(TrickleIcePolicy.supported(advertised))
        assertFalse(TrickleIcePolicy.supported(sdp + "a=other:a=x-lanu-trickle:1\r\n"))
        assertEquals("abcd1234", TrickleIcePolicy.ufrag(sdp))
        assertEquals(advertised, TrickleIcePolicy.advertise(advertised))
    }
    @Test fun boundedCandidateBatchRejectsInjectionAndWrongFieldTypes() {
        val candidate = JSONObject().put("mid", "0").put("index", 0).put("ufrag", "abcd1234")
            .put("candidate", "candidate:1 1 udp 1 192.0.2.1 1234 typ host")
        assertEquals(1, TrickleIcePolicy.decode("[$candidate]").size)
        assertThrows(IllegalArgumentException::class.java) { TrickleIcePolicy.decode("[]") }
        candidate.put("index", "0")
        assertThrows(IllegalArgumentException::class.java) { TrickleIcePolicy.decode("[$candidate]") }
        candidate.put("index", 0).put("candidate", "candidate:1\r\na=malformed:value")
        assertThrows(IllegalArgumentException::class.java) { TrickleIcePolicy.decode("[$candidate]") }
    }
}
