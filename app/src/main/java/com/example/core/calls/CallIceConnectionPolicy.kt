package com.example.core.calls

import org.webrtc.PeerConnection

/**
 * A failed ICE transport is not equivalent to a user-ended call.
 * The foreground call owner decides whether to issue its one permitted
 * encrypted ICE restart, and enforces the existing 25-second deadline.
 * A native CLOSED connection (without an owner-initiated close) is terminal.
 * No SDP, IP address, contact or media payload is logged or persisted.
 */
internal object CallIceConnectionPolicy {
    enum class Action { MEDIA_CONNECTED, RECOVER_WITH_DEADLINE, WAIT_FOR_CONNECTION, TERMINATE }

    fun classify(state: PeerConnection.PeerConnectionState): Action = when (state) {
        PeerConnection.PeerConnectionState.CONNECTED -> Action.MEDIA_CONNECTED
        PeerConnection.PeerConnectionState.DISCONNECTED,
        PeerConnection.PeerConnectionState.FAILED -> Action.RECOVER_WITH_DEADLINE
        PeerConnection.PeerConnectionState.CLOSED -> Action.TERMINATE
        else -> Action.WAIT_FOR_CONNECTION
    }
}
