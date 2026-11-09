package com.example.core.calls

import org.json.JSONArray
import org.json.JSONObject

/** Versioned in-SDP capability: old peers receive a complete, ordinary first offer. */
object TrickleIcePolicy {
    const val MARKER = "a=x-lanu-trickle:1"
    fun supported(sdp: String) = sdp.lineSequence().any { it.trimEnd() == MARKER }
    fun advertise(sdp: String) = if (supported(sdp)) sdp else sdp.trimEnd() + "\r\n$MARKER\r\n"
    fun ufrag(sdp: String) = sdp.lineSequence().firstOrNull { it.startsWith("a=ice-ufrag:") }
        ?.removePrefix("a=ice-ufrag:")?.trim().orEmpty()
    /** Match both mid and m-line index; use session credentials only as an RFC fallback. */
    fun candidateUfrag(sdp: String, mid: String, index: Int): String {
        if (sdp.length > 24000 || index !in 0..8 || mid.length !in 1..64) return ""
        var sessionUfrag = ""
        var section = -1
        var selectedMid = ""
        var selectedUfrag = ""
        for (raw in sdp.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("m=")) {
                if (section == index) break
                section++
            } else if (line.startsWith("a=ice-ufrag:")) {
                if (section < 0) sessionUfrag = line.removePrefix("a=ice-ufrag:")
                else if (section == index) selectedUfrag = line.removePrefix("a=ice-ufrag:")
            } else if (section == index && line.startsWith("a=mid:")) selectedMid = line.removePrefix("a=mid:")
        }
        if (section != index || selectedMid != mid) return ""
        return selectedUfrag.ifEmpty { sessionUfrag }
    }
    fun matchesEpoch(candidate: JSONObject, remoteSdp: String): Boolean {
        validate(candidate)
        return candidate.getString("ufrag") == candidateUfrag(remoteSdp,
            candidate.getString("mid"), candidate.getInt("index"))
    }
    fun validate(candidate: JSONObject) {
        require(candidate.keys().asSequence().toSet() == setOf("mid", "index", "candidate", "ufrag"))
        require(candidate.opt("mid") is String && candidate.getString("mid").length in 1..64)
        require(candidate.opt("index") is Int && candidate.getInt("index") in 0..8)
        require(candidate.opt("ufrag") is String && candidate.getString("ufrag").matches(Regex("[A-Za-z0-9+/]{4,256}")))
        require(candidate.opt("candidate") is String)
        val text = candidate.getString("candidate")
        require(text.length in 20..2048 && text.startsWith("candidate:") &&
            !text.contains('\n') && !text.contains('\r') && !text.contains('\u0000'))
    }
    fun decode(text: String): List<JSONObject> {
        require(text.length <= 12000)
        val list = JSONArray(text)
        require(list.length() in 1..4)
        return (0 until list.length()).map { list.getJSONObject(it).also(::validate) }
    }
}
