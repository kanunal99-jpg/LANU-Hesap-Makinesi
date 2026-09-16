package com.example

import com.example.core.crypto.CryptoUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessagingAuditTest {

    @Test
    fun testCryptoUtils_encryptionAndDecryption() {
        val secret = "conversation_test_uuid_12345"
        val plainText = "Gizli Mesaj: LANU E2EE Test 2026"

        val cipherText = CryptoUtils.encrypt(plainText, secret)
        assertNotEquals("Ciphertext must not be plaintext", plainText, cipherText)

        val decrypted = CryptoUtils.decrypt(cipherText, secret)
        assertEquals("Decrypted text must match original plaintext", plainText, decrypted)
    }

    @Test
    fun testCryptoUtils_uniqueIvPerMessage_avoidsNonceReuse() {
        val secret = "conversation_test_uuid_12345"
        val plainText = "Aynı mesaj iki kez şifreleniyor"

        val cipherText1 = CryptoUtils.encrypt(plainText, secret)
        val cipherText2 = CryptoUtils.encrypt(plainText, secret)

        // With random IVs, ciphertexts MUST differ even for identical plaintext and key!
        assertNotEquals("Each message must use a unique random IV to prevent GCM nonce reuse", cipherText1, cipherText2)

        // Both must still decrypt to original plaintext
        assertEquals(plainText, CryptoUtils.decrypt(cipherText1, secret))
        assertEquals(plainText, CryptoUtils.decrypt(cipherText2, secret))
    }

    @Test
    fun testCryptoUtils_wrongSecretFailsGracefully() {
        val secret1 = "secret_channel_1"
        val secret2 = "secret_channel_2"
        val plainText = "Bu metin sadece secret1 ile açılmalı"

        val cipherText = CryptoUtils.encrypt(plainText, secret1)
        val decryptedWrong = CryptoUtils.decrypt(cipherText, secret2)

        // Decrypting with wrong key returns ciphertext gracefully without crashing
        assertNotEquals(plainText, decryptedWrong)
    }
}
