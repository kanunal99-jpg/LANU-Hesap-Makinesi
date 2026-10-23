package com.example

import com.example.core.messaging.SecureMediaAttachment
import com.example.core.messaging.UserCodes
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SecureMediaAttachmentTest {
    @Test fun mediaEncryptionRoundtripBetweenTwoRealIdentities() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val id = UUID.randomUUID().toString()
        val payload = ByteArray(131_071) { (it % 256).toByte() }
        val frame = SecureMediaAttachment.encrypt(alice, bob.id, bob.publicKey, id, payload)
        assertFalse(payload.contentEquals(frame.ciphertext))
        assertEquals(frame.sha256, SecureMediaAttachment.sha256(frame.ciphertext))
        assertArrayEquals(payload, SecureMediaAttachment.decrypt(
            bob, alice.id, alice.publicKey, id, alice.id, frame.ciphertext, frame.sha256))
        assertArrayEquals(payload, SecureMediaAttachment.decrypt(
            alice, bob.id, bob.publicKey, id, alice.id, frame.ciphertext, frame.sha256))
    }

    @Test fun tamperedCiphertextWrongRecipientAndMessageIdNeverDecrypt() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val mallory = UserCodes.create()
        val id = UUID.randomUUID().toString()
        val frame = SecureMediaAttachment.encrypt(alice, bob.id, bob.publicKey, id, "secret file".toByteArray())
        val altered = frame.ciphertext.clone().also { it[14] = (it[14].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            SecureMediaAttachment.decrypt(bob, alice.id, alice.publicKey, id, alice.id, altered, frame.sha256)
        }
        assertThrows(SecurityException::class.java) {
            SecureMediaAttachment.decrypt(mallory, alice.id, alice.publicKey, id, alice.id,
                frame.ciphertext, frame.sha256)
        }
        assertThrows(SecurityException::class.java) {
            SecureMediaAttachment.decrypt(bob, alice.id, alice.publicKey,
                UUID.randomUUID().toString(), alice.id, frame.ciphertext, frame.sha256)
        }
    }

    @Test fun rejectsOversizedAndEmptyFilesBeforeEncryption() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val id = UUID.randomUUID().toString()
        for (bytes in listOf(ByteArray(0), ByteArray(SecureMediaAttachment.MAX_CLEAR_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                SecureMediaAttachment.encrypt(alice, bob.id, bob.publicKey, id, bytes)
            }
        }
    }

    @Test fun encryptedManifestValidatesNameMimeSizeAndDigest() {
        val digest = "a".repeat(64)
        val manifest = SecureMediaAttachment.Manifest("fotoğraf.jpg", "image/jpeg", 31, digest)
        assertEquals(manifest, SecureMediaAttachment.parseManifest(manifest.serialize()))
        assertNull(SecureMediaAttachment.parseManifest("Normal şifreli sohbet mesajı"))
        for (bad in listOf(
            SecureMediaAttachment.Manifest("../hidden", "image/jpeg", 31, digest),
            SecureMediaAttachment.Manifest("evil.html", "text/html", 31, digest),
            SecureMediaAttachment.Manifest("tiny.pdf", "application/pdf", 10000000, digest),
            SecureMediaAttachment.Manifest("file.pdf", "application/pdf", 200, "wrong")
        )) {
            assertThrows(IllegalArgumentException::class.java) { bad.serialize() }
        }
    }
}
