package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Malicious ciphertext metadata must not cause an unbounded decode allocation.
 * The legacy AES-GCM wire shape and any existing Room/PIN records are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyCryptoInputBudgetTest {
    @Test fun newMessageWireFormatAndEmptyPlaintextStillRoundTrip() {
        for (plaintext in listOf("", "LANU mesaj", "2026 veri korunur")) {
            val encoded = CryptoUtils.encrypt(plaintext, "synthetic-test-secret")
            assertEquals(plaintext, CryptoUtils.decrypt(encoded, "synthetic-test-secret"))
        }
    }

    @Test fun oversizedHostileCiphertextIsRejectedWithoutDecodingOrLeakingContent() {
        val oversized = "A".repeat(CryptoUtils.MAX_CIPHERTEXT_BASE64_CHARS + 1)
        try {
            CryptoUtils.decrypt(oversized, "synthetic-test-secret")
            fail("Oversized legacy payload must be rejected before Base64 decoding")
        } catch (failure: SecurityException) {
            assertEquals("Şifreli içerik doğrulanamadı.", failure.message)
            assertFalse(failure.message.orEmpty().contains(oversized.take(64)))
        }
    }

    @Test fun invalidTagOrWrongSecretNeverReturnsDecryptedContent() {
        val encoded = CryptoUtils.encrypt("Özel mesaj", "alice-test")
        for (wrong in listOf("bob-test", "another-key")) {
            try {
                CryptoUtils.decrypt(encoded, wrong)
                fail("Wrong key must not authenticate a message")
            } catch (_: SecurityException) { }
        }
    }
}
