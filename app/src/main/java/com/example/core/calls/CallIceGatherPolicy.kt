package com.example.core.calls

import org.webrtc.PeerConnection

/**
 * A conservative fast path for LANU's existing non-trickle encrypted SDP.
 *
 * Never signal an SDP with zero candidates or only an unverified, transient
 * local host address. Once at least one server-reflexive candidate has reached
 * the actual local SDP and candidate discovery has been quiet long enough,
 * we can send the already-encrypted offer/answer without waiting on a slow
 * secondary STUN resolver. Failure to obtain srflx still uses the original
 * complete-or-eight-second bounded fallback.
 *
 * This does NOT enable trickle ICE or add billable TURN infrastructure.
 */
internal object CallIceGatherPolicy {
    const val FAST_MIN_ELAPSED_MS = 1_400L
    const val FAST_QUIET_MS = 650L
    const val COMPLETE_QUIET_MS = 250L
    const val GATHER_TIMEOUT_MS = 8_000L

    fun ready(
        localSdp: String?,
        state: PeerConnection.IceGatheringState,
        elapsedMs: Long,
        lastCandidateAgoMs: Long?
    ): Boolean {
        // Reject impossible clock samples rather than prematurely signaling stale SDP.
        if (elapsedMs < 0 || lastCandidateAgoMs == null ||
            lastCandidateAgoMs < 0 || lastCandidateAgoMs > elapsedMs) return false
        // Only an actual, structurally valid SDP candidate line is evidence.
        // A token embedded in an unrelated attribute or a truncated OEM
        // candidate must not start non-trickle signaling prematurely.
        val sdp = localSdp ?: return false
        if (!hasUsableCandidate(sdp)) return false
        if (state == PeerConnection.IceGatheringState.COMPLETE &&
            lastCandidateAgoMs >= COMPLETE_QUIET_MS) return true
        if (elapsedMs < FAST_MIN_ELAPSED_MS || lastCandidateAgoMs < FAST_QUIET_MS) return false
        // Fast path still needs a real server-reflexive candidate; complete
        // gathering may use a valid host candidate for local-network calls.
        return sdp.lineSequence().any { candidateType(it) == "srflx" }
    }

    /** Used by both early ICE readiness and the eight-second fallback gate. */
    fun hasUsableCandidate(localSdp: String?): Boolean =
        localSdp?.lineSequence()?.any { candidateType(it) != null } == true

    private fun candidateType(line: String): String? {
        if (!line.startsWith("a=candidate:")) return null
        // RFC 5245 candidate grammar: foundation, component, transport,
        // priority, address, port, "typ", candidate-type, optional extensions.
        val fields = line.removePrefix("a=candidate:").trim().split(Regex("\\s+"))
        if (fields.size < 8 || fields[0].isBlank() ||
            (fields[1].toIntOrNull() ?: 0) !in 1..256 ||
            fields[2].lowercase() !in setOf("udp", "tcp") ||
            (fields[3].toLongOrNull() ?: -1L) < 0L || fields[4].isBlank() ||
            (fields[5].toIntOrNull() ?: 0) !in 1..65535 ||
            fields[6] != "typ") return null
        return fields[7].takeIf { it in setOf("host", "srflx", "prflx", "relay") }
    }
}
