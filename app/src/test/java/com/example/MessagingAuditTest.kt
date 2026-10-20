package com.example

import android.util.Base64
import com.example.core.crypto.CryptoUtils
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    fun testCryptoUtils_wrongSecretFailsClosed() {
        val secret1 = "secret_channel_1"
        val secret2 = "secret_channel_2"
        val plainText = "Bu metin sadece secret1 ile açılmalı"

        val cipherText = CryptoUtils.encrypt(plainText, secret1)
        try {
            CryptoUtils.decrypt(cipherText, secret2)
            fail("Wrong secret must reject tampered ciphertext, not silently return its bytes")
        } catch (_: SecurityException) {
            // Authentication failure must be explicit and never look like decrypted data.
        }
    }

    @Test
    fun testCryptoUtils_emptyPlaintext_hasMinimumValidGcmFrameAndRoundTrips() {
        val secret = "lanu-gcm-empty-boundary-secret"
        val encrypted = CryptoUtils.encrypt("", secret)
        assertEquals("AES-GCM empty payload is 12-byte IV + 16-byte tag", 28,
            Base64.decode(encrypted, Base64.NO_WRAP).size)
        assertEquals("", CryptoUtils.decrypt(encrypted, secret))
    }

    @Test
    fun testCryptoUtils_tamperedMinimumGcmFrameFailsClosed() {
        val secret = "lanu-gcm-tamper-test-secret"
        val frame = Base64.decode(CryptoUtils.encrypt("", secret), Base64.NO_WRAP)
        frame[0] = (frame[0].toInt() xor 1).toByte()
        val tampered = Base64.encodeToString(frame, Base64.NO_WRAP)
        try {
            CryptoUtils.decrypt(tampered, secret)
            fail("Tampered GCM IV must fail authentication")
        } catch (_: SecurityException) { }
    }

    @Test
    fun testCryptoUtils_truncatedGcmFrameFailsClosed() {
        val secret = "lanu-gcm-truncated-frame-test"
        val frame = Base64.decode(CryptoUtils.encrypt("", secret), Base64.NO_WRAP)
        val truncated = Base64.encodeToString(frame.copyOf(frame.size - 1), Base64.NO_WRAP)
        try {
            CryptoUtils.decrypt(truncated, secret)
            fail("Truncated authentication tag must not be accepted")
        } catch (_: SecurityException) { }
    }

    @Test
    fun testCryptoUtils_historicDeterministicIvCiphertextStillDecrypts() {
        val secret = "legacy-room-message-compat"
        val iv = MessageDigest.getInstance("MD5").digest(secret.toByteArray()).copyOf(12)
        val key = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encoded = Base64.encodeToString(
            cipher.doFinal("Eski kayit korunur".toByteArray()), Base64.NO_WRAP)
        assertEquals("Eski kayit korunur", CryptoUtils.decrypt(encoded, secret))
    }
}
