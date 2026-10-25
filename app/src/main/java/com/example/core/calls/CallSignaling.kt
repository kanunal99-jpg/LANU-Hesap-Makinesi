package com.example.core.calls

import org.json.JSONObject
import java.util.UUID

enum class CallSignalKind { INVITE, ACCEPT, REJECT, BUSY, OFFER, ANSWER, END }

/** Sensitive SDP stays only in RAM and inside authenticated peer ciphertext. */
class CallSignal(
    val id: String,
    val callId: String,
    val kind: CallSignalKind,
    val video: Boolean,
    val createdAt: Long,
    val sdp: String = ""
) {
    override fun toString() = "CallSignal(redacted)"
}

object CallSignaling {
    const val VERSION = 1
    const val TTL_MILLIS = 90_000L
    const val MAX_SDP = 18_000
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    fun validId(id: String) = uuid.matches(id)
    fun aadId(id: String): String { require(validId(id)); return "LANU-CALL-v1:$id" }

    fun create(callId: String, kind: CallSignalKind, video: Boolean, sdp: String = "", now: Long = System.currentTimeMillis()) =
        CallSignal(UUID.randomUUID().toString(), callId, kind, video, now, sdp).also { validate(it, now) }

    fun encode(signal: CallSignal): String {
        validate(signal, signal.createdAt)
        return JSONObject().put("v", VERSION).put("id", signal.id).put("call", signal.callId)
            .put("kind", signal.kind.name).put("video", signal.video).put("at", signal.createdAt)
            .put("sdp", signal.sdp).toString()
    }

    fun decode(text: String, outerId: String, outerCall: String, now: Long): CallSignal {
        require(text.length <= 24_000 && text.toByteArray(Charsets.UTF_8).size <= 24_000)
        val o = JSONObject(text)
        require(o.keys().asSequence().toSet() == setOf("v", "id", "call", "kind", "video", "at", "sdp"))
        require(o.get("v") is Int && o.getInt("v") == VERSION && o.get("video") is Boolean)
        require(o.get("at") is Long || o.get("at") is Int)
        val result = CallSignal(o.getString("id"), o.getString("call"), CallSignalKind.valueOf(o.getString("kind")),
            o.getBoolean("video"), o.getLong("at"), o.getString("sdp"))
        require(result.id == outerId && result.callId == outerCall)
        validate(result, now)
        return result
    }

    fun validate(s: CallSignal, now: Long) {
        require(validId(s.id) && validId(s.callId))
        require(s.createdAt >= 0 && s.createdAt <= now + 30_000 && now - s.createdAt <= TTL_MILLIS)
        if (s.kind == CallSignalKind.OFFER || s.kind == CallSignalKind.ANSWER) {
            require(s.sdp.length in 1..MAX_SDP && s.sdp.toByteArray(Charsets.UTF_8).size <= MAX_SDP)
            require(!s.sdp.contains('\u0000') && s.sdp.startsWith("v=0") &&
                s.sdp.contains("a=fingerprint:sha-256 ") && s.sdp.contains("m=audio "))
        } else require(s.sdp.isEmpty())
    }
}

class CallPeer(val id: String, val code: String, val name: String, internal val publicKey: String) {
    override fun toString() = "CallPeer(redacted)"
}
class ReceivedCallSignal(val peer: CallPeer, val signal: CallSignal)

/** A connection badge requires actual transport and audio packet counters. */
data class CallMediaEvidence(val transportConnected: Boolean = false, val receivedAudio: Long = 0, val sentAudio: Long = 0) {
    val connected: Boolean get() = transportConnected && receivedAudio > 0 && sentAudio > 0
}
