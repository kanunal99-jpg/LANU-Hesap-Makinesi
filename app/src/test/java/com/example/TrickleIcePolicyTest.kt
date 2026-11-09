package com.example

import com.example.core.calls.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
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
    @Test fun audioVideoCredentialsAreBoundToBothMediaIdAndIndex() {
        val sdp = "v=0\r\na=ice-ufrag:session1\r\nm=audio 9 RTP/SAVPF 111\r\na=mid:audio\r\na=ice-ufrag:audio123\r\nm=video 9 RTP/SAVPF 96\r\na=mid:video\r\na=ice-ufrag:video456\r\n"
        assertEquals("audio123", TrickleIcePolicy.candidateUfrag(sdp, "audio", 0))
        assertEquals("video456", TrickleIcePolicy.candidateUfrag(sdp, "video", 1))
        assertEquals("", TrickleIcePolicy.candidateUfrag(sdp, "audio", 1))
        assertEquals("", TrickleIcePolicy.candidateUfrag(sdp, "missing", 0))
        val candidate = JSONObject().put("mid", "video").put("index", 1).put("ufrag", "video456")
            .put("candidate", "candidate:1 1 udp 1 192.0.2.1 1234 typ host")
        assertTrue(TrickleIcePolicy.matchesEpoch(candidate, sdp))
        candidate.put("ufrag", "oldVideo")
        assertFalse(TrickleIcePolicy.matchesEpoch(candidate, sdp))
        val inherited = "v=0\r\na=ice-ufrag:session1\r\nm=audio 9 RTP/SAVPF 111\r\na=mid:0\r\n"
        assertEquals("session1", TrickleIcePolicy.candidateUfrag(inherited, "0", 0))
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
