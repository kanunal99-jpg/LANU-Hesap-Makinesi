package com.example.core.calls

import org.webrtc.PeerConnection

/**
 * Native FAILED/DISCONNECTED states revoke media evidence, but are not proof
 * that an authenticated session is irrecoverable. The foreground call owner
 * controls one signed ICE restart and a finite 25-second recovery deadline.
 * This policy does not enable background media, auto-answer or TURN.
 */
internal object CallTransportRecoveryPolicy {
    fun isConnected(state: PeerConnection.PeerConnectionState): Boolean =
        state == PeerConnection.PeerConnectionState.CONNECTED

    fun isRecoverableTransportLoss(state: PeerConnection.PeerConnectionState): Boolean =
        state == PeerConnection.PeerConnectionState.FAILED ||
            state == PeerConnection.PeerConnectionState.DISCONNECTED
}
