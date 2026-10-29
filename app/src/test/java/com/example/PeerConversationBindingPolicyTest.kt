package com.example

import com.example.core.messaging.PeerConversationBindingPolicy
import com.example.core.messaging.UserCodes
import org.junit.Assert.*
import org.junit.Test

class PeerConversationBindingPolicyTest {
    private val own = UserCodes.id("own-test-key")
    private val publicKey = "independent-test-public-key"
    private val peer = UserCodes.id(publicKey)
    private val nonce = "fixed-test-nonce"
    private val code = UserCodes.code(publicKey, nonce)
    private val thread = UserCodes.conversationId(own, peer)

    private fun matches(
        conversationId: String = thread,
        peerId: String = peer,
        key: String = publicKey,
        peerCode: String = code,
        peerNonce: String = nonce,
        localId: String = own
    ) = PeerConversationBindingPolicy.matches(localId, conversationId, peerId, key, peerCode, peerNonce)

    @Test fun validPinnedPeerCanUseItsOwnConversation() {
        assertTrue(matches())
        assertEquals(thread, UserCodes.conversationId(peer, own))
    }

    @Test fun wrongThreadOrPeerIdentityNeverPassesEvenWithValidDisplayCode() {
        assertFalse(matches(conversationId = UserCodes.conversationId(own, UserCodes.id("other"))))
        assertFalse(matches(peerId = UserCodes.id("other")))
        assertFalse(matches(localId = peer))
    }

    @Test fun substitutedPublicKeyOrChangedCodeIsRejected() {
        assertFalse(matches(key = "attacker-key"))
        assertFalse(matches(peerCode = UserCodes.code(publicKey, "different")))
        assertFalse(matches(peerNonce = "different"))
        assertFalse(matches(peerId = own, localId = own))
    }

    @Test fun corruptOrOversizedPeerRecordsFailClosedWithoutLookup() {
        assertFalse(matches(key = ""))
        assertFalse(matches(key = "x".repeat(2049)))
        assertFalse(matches(peerCode = ""))
        assertFalse(matches(peerNonce = ""))
        assertFalse(matches(peerNonce = "x".repeat(257)))
        assertFalse(matches(localId = ""))
    }
}
