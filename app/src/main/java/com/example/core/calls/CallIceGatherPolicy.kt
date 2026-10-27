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
        // Candidate may not have been inserted into the local description yet.
        val sdp = localSdp ?: return false
        if (!sdp.contains("a=candidate:")) return false
        if (state == PeerConnection.IceGatheringState.COMPLETE &&
            lastCandidateAgoMs >= COMPLETE_QUIET_MS) return true
        if (elapsedMs < FAST_MIN_ELAPSED_MS || lastCandidateAgoMs < FAST_QUIET_MS) return false
        // Reject a stale previous ICE epoch and ensure at least one public
        // server-reflexive route actually made it into the outgoing SDP.
        return sdp.lineSequence().any { line ->
            line.startsWith("a=candidate:") && line.trim().split(Regex("\\s+"))
                .windowed(2).any { parts -> parts[0] == "typ" && parts[1] == "srflx" }
        }
    }
}
