package com.example

import android.app.Application
import com.example.core.calls.*
import com.example.core.messaging.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CallSignalingTest {
    private val now=1_790_000_000_000L
    private val call=UUID.randomUUID().toString()
    private val sdp="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fingerprint:sha-256 AA:BB\r\n"
    @Test fun signalCodecBindsVersionEnvelopeCallAndShortLifetime() {
        val source=CallSignaling.create(call,CallSignalKind.INVITE,true,now=now)
        val encoded=CallSignaling.encode(source)
        val restored=CallSignaling.decode(encoded,source.id,call,now+1000)
        assertEquals(CallSignalKind.INVITE,restored.kind);assertTrue(restored.video)
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode(encoded,UUID.randomUUID().toString(),call,now) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode(encoded,source.id,UUID.randomUUID().toString(),now) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode(encoded,source.id,call,now+91_000) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode(encoded,source.id,call,now-31_000) }
    }
    @Test fun callSignalWindowRejectsClockOverflowAndKeepsInclusiveBoundaries() {
        val id = UUID.randomUUID().toString()
        fun validate(at: Long, clock: Long) {
            CallSignaling.validate(CallSignal(id, call, CallSignalKind.INVITE,
                false, at), clock)
        }
        validate(now - CallSignaling.TTL_MILLIS, now)
        validate(now + 30_000L, now)
        assertThrows(IllegalArgumentException::class.java) {
            validate(now - CallSignaling.TTL_MILLIS - 1L, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(now + 30_001L, now)
        }
        // Long.MAX_VALUE used to overflow 'now + 30_000L'.
        validate(Long.MAX_VALUE, Long.MAX_VALUE)
        validate(Long.MAX_VALUE, Long.MAX_VALUE - 30_000L)
        assertThrows(IllegalArgumentException::class.java) {
            validate(Long.MAX_VALUE, Long.MAX_VALUE - 30_001L)
        }
        validate(Long.MAX_VALUE - CallSignaling.TTL_MILLIS, Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {
            validate(Long.MAX_VALUE - CallSignaling.TTL_MILLIS - 1L, Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(now, Long.MIN_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(Long.MIN_VALUE, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(0L, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(now, 0L)
        }
    }

    @Test fun malformedOversizedAndDowngradedSignalsAreRejected() {
        val source=CallSignaling.create(call,CallSignalKind.OFFER,false,sdp,now)
        val encoded=CallSignaling.encode(source)
        assertEquals(sdp,CallSignaling.decode(encoded,source.id,call,now).sdp)
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode(encoded.replace("\"v\":1","\"v\":2"),source.id,call,now) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode(encoded.replace("\"v\":1","\"v\":1.5"),source.id,call,now) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.create(call,CallSignalKind.OFFER,false,"v=0\r\nm=audio 9 x\r\n",now) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.create(call,CallSignalKind.END,false,sdp,now) }
        assertThrows(IllegalArgumentException::class.java) { CallSignaling.decode("x".repeat(24001),source.id,call,now) }
    }
    @Test fun callCiphertextCannotBeReplayedAsChatOrForAnotherPeerOrSignal() {
        val a=UserCodes.create();val b=UserCodes.create();val id=UUID.randomUUID().toString()
        val cipher=PeerMessageCrypto.encrypt(a,b.id,b.publicKey,CallSignaling.aadId(id),"signal")
        assertEquals("signal",PeerMessageCrypto.decrypt(b,a.id,a.publicKey,CallSignaling.aadId(id),a.id,cipher))
        assertThrows(Exception::class.java) { PeerMessageCrypto.decrypt(b,a.id,a.publicKey,id,a.id,cipher) }
        assertThrows(Exception::class.java) { PeerMessageCrypto.decrypt(b,a.id,a.publicKey,CallSignaling.aadId(UUID.randomUUID().toString()),a.id,cipher) }
        val stranger=UserCodes.create()
        assertThrows(Exception::class.java) { PeerMessageCrypto.decrypt(stranger,a.id,a.publicKey,CallSignaling.aadId(id),a.id,cipher) }
    }
    @Test fun connectedBadgeRequiresTransportAndRealBidirectionalAudioPackets() {
        assertFalse(CallMediaEvidence(true,0,10).connected)
        assertFalse(CallMediaEvidence(true,10,0).connected)
        assertFalse(CallMediaEvidence(false,10,10).connected)
        assertTrue(CallMediaEvidence(true,1,1).connected)
    }
}
