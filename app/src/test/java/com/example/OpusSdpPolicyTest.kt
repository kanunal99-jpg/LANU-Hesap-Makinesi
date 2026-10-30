package com.example

import com.example.core.calls.OpusSdpPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packet-loss mitigation must never corrupt unrelated media sections. */
class OpusSdpPolicyTest {
    private val offer = listOf(
        "v=0", "m=audio 9 UDP/TLS/RTP/SAVPF 111 103",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10",
        "a=rtpmap:103 ISAC/16000",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "a=rtpmap:96 VP8/90000", "a=fmtp:96 x-google-start-bitrate=900", ""
    ).joinToString("\r\n")

    @Test fun fecIsAddedOnlyToOpusAndKeepsOtherCodecs() {
        val changed = OpusSdpPolicy.preferReceiveFec(offer)
        assertTrue(changed.contains("a=fmtp:111 minptime=10;useinbandfec=1\r\n"))
        assertTrue(changed.contains("a=fmtp:96 x-google-start-bitrate=900"))
        assertTrue(changed.endsWith("\r\n"))
        assertEquals(changed, OpusSdpPolicy.preferReceiveFec(changed))
        assertFalse(changed.contains("usedtx=1"))
    }

    @Test fun unknownAudioOrMissingOpusIsLeftUntouched() {
        val input = "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 103\na=rtpmap:103 ISAC/16000\n"
        assertEquals(input, OpusSdpPolicy.preferReceiveFec(input))
        assertEquals("v=0\n", OpusSdpPolicy.preferReceiveFec("v=0\n"))
    }

    @Test fun missingFmtpIsInsertedOnlyWithinAudio() {
        val input = offer.replace("a=fmtp:111 minptime=10\r\n", "")
        val changed = OpusSdpPolicy.preferReceiveFec(input)
        assertTrue(changed.contains("a=rtpmap:111 opus/48000/2\r\na=fmtp:111 useinbandfec=1\r\n"))
        assertTrue(changed.contains("m=video 9 UDP/TLS/RTP/SAVPF 96"))
    }

    @Test fun explicitNoFecIsNotOverriddenAndDtxRequiresOptIn() {
        val s = offer.replace("minptime=10", "useinbandfec=0")
        assertEquals(s, OpusSdpPolicy.preferReceiveFec(s))
        val opt = OpusSdpPolicy.preferReceiveFec(s, enableDtx = true)
        assertTrue(opt.contains("useinbandfec=0;usedtx=1"))
        assertEquals(opt, OpusSdpPolicy.preferReceiveFec(opt, enableDtx = true))
    }

    @Test fun otherAudioPayloadFmtpCannotImpersonateOpus() {
        val input = offer.replace("a=fmtp:111 minptime=10\r\n",
            "a=fmtp:112 minptime=10\r\n")
        val changed = OpusSdpPolicy.preferReceiveFec(input)
        assertTrue(changed.contains("a=fmtp:111 useinbandfec=1"))
        assertTrue(changed.contains("a=fmtp:112 minptime=10"))
    }
}
