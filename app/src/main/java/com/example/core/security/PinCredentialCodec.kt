package com.example.core.security

import android.util.Base64
import com.example.core.crypto.CryptoUtils
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import com.example.core.messaging.IdentityBackup

/**
 * Versioned, per-user salted PIN verifier. Migration is performed ONLY after
 * the legacy PIN is successfully authenticated; never erase the old record first.
 * Short PINs still require device-level data protection and a rate limit.
 */
object PinCredentialCodec {
    private const val VERSION = "pbkdf2-sha256"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private val random = SecureRandom()

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, HASH_BYTES * 8)
        return try {
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } catch (_: java.security.NoSuchAlgorithmException) {
                // Android 23-25 devices may not provide the JCA provider.
                IdentityBackup.softwarePbkdf2(pin, salt, rounds)
            }
        } finally {
            spec.clearPassword()
        }
    }

    fun create(pin: String): String {
        require(PinPolicy.isValid(pin)) { "Geçersiz PIN." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return "$VERSION:$ITERATIONS:${encode(salt)}:${encode(derive(pin, salt, ITERATIONS))}"
    }

    fun isModern(value: String): Boolean = value.startsWith("$VERSION:")

    /** Unknown/invalid record formats fail closed, without resetting the PIN. */
    fun verify(pin: String, stored: String): Boolean {
        if (!PinPolicy.isValid(pin)) return false
        if (isModern(stored)) {
            val parts = stored.split(':')
            if (parts.size != 4 || parts[0] != VERSION || parts[1] != ITERATIONS.toString()) return false
            return try {
                val salt = decode(parts[2])
                val expected = decode(parts[3])
                salt.size == SALT_BYTES && expected.size == HASH_BYTES &&
                    MessageDigest.isEqual(expected, derive(pin, salt, ITERATIONS))
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        // Legacy SHA-256 record is accepted only for a one-time authenticated migration.
        if (!Regex("[A-Za-z0-9+/]{43}=").matches(stored)) return false
        return MessageDigest.isEqual(
            stored.toByteArray(Charsets.US_ASCII),
            CryptoUtils.hashPin(pin).toByteArray(Charsets.US_ASCII)
        )
    }
}
