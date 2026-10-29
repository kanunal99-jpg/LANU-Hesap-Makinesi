package com.example.core.messaging

/**
 * Match the immutable cryptographic conversation ID with the pinned peer
 * identity before creating or sending any E2EE message. A damaged/stale
 * cached record must never redirect encrypted traffic to another peer.
 *
 * Pure, fail-closed check: no network, key mutation or secret logging.
 */
internal object PeerConversationBindingPolicy {
    fun matches(
        ownId: String,
        conversationId: String,
        peerId: String,
        peerPublicKey: String,
        peerCode: String,
        peerNonce: String
    ): Boolean {
        if (ownId == peerId || ownId.isEmpty() || peerId.isEmpty() ||
            peerPublicKey.isEmpty() || peerPublicKey.length > 2048 ||
            peerCode.isEmpty() || peerNonce.isEmpty() ||
            peerNonce.length > 256) return false

        return peerId == UserCodes.id(peerPublicKey) &&
            conversationId == UserCodes.conversationId(ownId, peerId) &&
            UserCodes.matches(peerCode, peerPublicKey, peerNonce)
    }
}
