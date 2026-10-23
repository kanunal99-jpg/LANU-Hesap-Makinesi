package com.example

import com.example.core.media.PrivateMediaCrypto
import com.example.core.media.PrivateMediaKeyEnvelope
import com.example.core.messaging.PeerMessageCrypto
import com.example.core.messaging.UserCodes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateMediaKeyEnvelopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Unauthenticated, corrupt or malformed key envelope must be rejected.")
        } catch (_: Exception) {
            // Parsing, AES GCM auth, peer trust or bad manifest MUST all fail closed.
        }
    }

    @Test fun encryptedPeerEnvelopeDeliversOneTimeKeyAndVerifiedMediaToReceiver() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val dir = tmp.newFolder("private")
        val content = ByteArray(147_029) { i -> (i * 31 + 19).toByte() }
        val packageOnSender = PrivateMediaCrypto.encryptToPrivateFile(
            ByteArrayInputStream(content), content.size.toLong(), "video/mp4", dir
        )
        val id = UUID.randomUUID().toString()
        val encryptedPeerMessage = PrivateMediaKeyEnvelope.encryptForPeer(
            alice, bob.id, bob.publicKey, id,
            packageOnSender.manifest, packageOnSender.keyMaterial
        )
        val rawSecretText = UserCodes.encode(packageOnSender.keyMaterial)
        assertTrue(encryptedPeerMessage.startsWith("lanu2:"))
        assertFalse(encryptedPeerMessage.contains(rawSecretText))
        assertFalse(encryptedPeerMessage.contains(packageOnSender.manifest.cipherSha256))
        assertFalse(encryptedPeerMessage.contains("\"fileId\""))

        val opened = PrivateMediaKeyEnvelope.decryptFromPeer(
            bob, alice.id, alice.publicKey, id, alice.id, encryptedPeerMessage
        )
        try {
            assertEquals(packageOnSender.manifest, opened.manifest)
            assertArrayEquals(packageOnSender.keyMaterial, opened.keyMaterial)
            val recoveredFile = PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                packageOnSender.ciphertextFile, opened.manifest, opened.keyMaterial, dir
            )
            assertArrayEquals(content, recoveredFile.readBytes())
            assertTrue(recoveredFile.delete())
        } finally {
            opened.destroyKey()
            packageOnSender.destroyKey()
            assertTrue(opened.keyMaterial.all { it == 0.toByte() })
            assertTrue(packageOnSender.keyMaterial.all { it == 0.toByte() })
        }
    }

    @Test fun anotherRecipientOrWrongMessageIdCannotDecryptEnvelope() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val charlie = UserCodes.create()
        val m = sampleManifest()
        val key = ByteArray(32) { 0x6c }
        val id = UUID.randomUUID().toString()
        val ciphertext = PrivateMediaKeyEnvelope.encryptForPeer(
            alice, bob.id, bob.publicKey, id, m, key
        )
        reject { PrivateMediaKeyEnvelope.decryptFromPeer(charlie, alice.id, alice.publicKey, id, alice.id, ciphertext) }
        reject { PrivateMediaKeyEnvelope.decryptFromPeer(bob, alice.id, alice.publicKey, UUID.randomUUID().toString(), alice.id, ciphertext) }
        reject { PrivateMediaKeyEnvelope.decryptFromPeer(bob, charlie.id, charlie.publicKey, id, alice.id, ciphertext) }
        reject { PrivateMediaKeyEnvelope.decryptFromPeer(bob, alice.id, alice.publicKey, id, charlie.id, ciphertext) }
        reject { PrivateMediaKeyEnvelope.decryptFromPeer(bob, alice.id, alice.publicKey, id, alice.id, ciphertext.dropLast(1) + "A") }
    }

    @Test fun forgedOrExtraFieldsAndBadBase64FailClosedEvenWhenSignedBySender() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val m = sampleManifest()
        val secret = ByteArray(32) { 3 }
        val msgId = UUID.randomUUID().toString()
        val valid = PrivateMediaKeyEnvelope.encryptForPeer(
            alice, bob.id, bob.publicKey, msgId, m, secret
        )
        val plain = PeerMessageCrypto.decrypt(
            bob, alice.id, alice.publicKey, msgId, alice.id, valid
        )
        val formats = listOf(
            JSONObject(plain).put("extra", "forbidden"),
            JSONObject(plain).put("key", "abc"),
            JSONObject(plain).put("mime", "application/x-executable"),
            JSONObject(plain).put("version", 999),
            JSONObject(plain).put("cipherSha256", "0".repeat(63)),
            JSONObject(plain).put("plainBytes", 0)
        )
        for ((index, payload) in formats.withIndex()) {
            val id = UUID.randomUUID().toString()
            val frame = PeerMessageCrypto.encrypt(
                alice, bob.id, bob.publicKey, id, payload.toString()
            )
            reject { PrivateMediaKeyEnvelope.decryptFromPeer(bob, alice.id, alice.publicKey, id, alice.id, frame) }
        }
    }

    @Test fun missingOrShortKeyDoesNotProduceARealAttachmentToken() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        reject {
            PrivateMediaKeyEnvelope.encryptForPeer(
                alice, bob.id, bob.publicKey, UUID.randomUUID().toString(),
                sampleManifest(), ByteArray(16)
            )
        }
        reject {
            PrivateMediaKeyEnvelope.encryptForPeer(
                alice, bob.id, bob.publicKey, UUID.randomUUID().toString(),
                sampleManifest().copy(chunks = 2), ByteArray(32)
            )
        }
    }

    private fun sampleManifest() = PrivateMediaCrypto.Manifest(
        version = 1, fileId = "a".repeat(32),
        mime = "application/pdf", plainBytes = 1200, chunks = 1,
        noncePrefix = "b".repeat(16), cipherSha256 = "c".repeat(64)
    )
}
