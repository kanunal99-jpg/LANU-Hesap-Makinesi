package com.example

import com.example.core.messaging.PeerMessageCrypto
import com.example.core.messaging.UserCodes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerMessageSizeContractTest {
    @Test fun `boundary plaintext produces relay compliant ciphertext and decrypts`() {
        val sender = UserCodes.create()
        val receiver = UserCodes.create()
        val messageId = UUID.randomUUID().toString()
        val text = "a".repeat(37_467)
        val encrypted = PeerMessageCrypto.encrypt(sender, receiver.id, receiver.publicKey, messageId, text)
        assertTrue(encrypted.startsWith("lanu2:"))
        assertTrue(encrypted.length <= 50_000)
        assertEquals(text, PeerMessageCrypto.decrypt(receiver, sender.id, sender.publicKey, messageId, sender.id, encrypted))
    }

    @Test fun `oversized plaintext is refused before crypto processing`() {
        val sender = UserCodes.create()
        val receiver = UserCodes.create()
        val messageId = UUID.randomUUID().toString()
        try {
            PeerMessageCrypto.encrypt(sender, receiver.id, receiver.publicKey, messageId, "x".repeat(37_468))
            fail("Ciphertext exceeding bound must be refused")
        } catch (_: IllegalArgumentException) { }
        try {
            PeerMessageCrypto.encrypt(sender, receiver.id, receiver.publicKey, messageId, "😀".repeat(11_000))
            fail("UTF-8 multi-byte plaintext must be bounded")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `oversized malformed and short remote cipher frames are rejected early`() {
        val sender = UserCodes.create()
        val receiver = UserCodes.create()
        val id = UUID.randomUUID().toString()
        val invalid = listOf(
            "lanu2:" + "A".repeat(50_001),
            "lanu2:" + "A".repeat(37) + "#",
            "lanu2:!!!",
            "lanu2:" + "A".repeat(38) + "\n",
            "untrusted:" + "A".repeat(60)
        )
        invalid.forEach { frame ->
            try {
                PeerMessageCrypto.decrypt(receiver, sender.id, sender.publicKey, id, sender.id, frame)
                fail("Untrusted relay frame must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `peer public key cannot be substituted under a trusted identity`() {
        val sender = UserCodes.create()
        val receiver = UserCodes.create()
        val impostor = UserCodes.create()
        val id = UUID.randomUUID().toString()
        val verified = PeerMessageCrypto.encrypt(
            sender, receiver.id, receiver.publicKey, id, "güvenli"
        )
        assertEquals("güvenli", PeerMessageCrypto.decrypt(
            receiver, sender.id, sender.publicKey, id, sender.id, verified
        ))
        try {
            PeerMessageCrypto.encrypt(sender, receiver.id, impostor.publicKey, id, "güvenli")
            fail("A mismatched peer identity must not be used for ECDH")
        } catch (_: IllegalArgumentException) { }
        try {
            PeerMessageCrypto.decrypt(
                receiver, sender.id, impostor.publicKey, id, sender.id, verified
            )
            fail("An untrusted substitution must not be accepted for decryption")
        } catch (_: IllegalArgumentException) { }
        // Neither a failed send nor a failed receive rotates or replaces
        // the existing identity; the original peer still decrypts as before.
        assertEquals("güvenli", PeerMessageCrypto.decrypt(
            receiver, sender.id, sender.publicKey, id, sender.id, verified
        ))
    }

    @Test fun `normal Turkish text and empty authenticated message remain compatible`() {
        val sender = UserCodes.create()
        val receiver = UserCodes.create()
        val id = UUID.randomUUID().toString()
        listOf("", "Merhaba! Şifreli mesaj denemesi — ĞÜŞİÖÇ").forEach { clear ->
            val encrypted = PeerMessageCrypto.encrypt(sender, receiver.id, receiver.publicKey, id, clear)
            assertEquals(clear, PeerMessageCrypto.decrypt(receiver, sender.id, sender.publicKey, id, sender.id, encrypted))
        }
    }
}
